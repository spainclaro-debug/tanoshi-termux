//! Wire types for the Qi Scans API (`api.qimanga.com/api/v1`).
//!
//! Ported from the Keiyoushi `EZManhwa` DTOs; field names and optionality
//! match the live payloads.

use serde::Deserialize;
use tanoshi_lib::prelude::{ChapterInfo, MangaInfo};

use crate::{ID, html_to_text, to_status};

#[derive(Debug, Deserialize)]
pub struct SeriesListResponse {
    pub data: Vec<SeriesDto>,
    #[serde(rename = "totalPages", default)]
    #[allow(dead_code)]
    pub total_pages: i64,
    #[serde(rename = "current", default)]
    #[allow(dead_code)]
    pub current_page: i64,
}

#[derive(Debug, Deserialize)]
pub struct ChapterListResponse {
    pub data: Vec<ChapterDto>,
    #[serde(rename = "totalPages", default = "one")]
    pub total_pages: i64,
}

fn one() -> i64 {
    1
}

#[derive(Debug, Deserialize)]
pub struct PageListResponse {
    #[serde(default)]
    pub images: Option<Vec<ImageDto>>,
    #[serde(rename = "requiresPurchase", default)]
    pub requires_purchase: Option<bool>,
    #[serde(rename = "totalImages", default)]
    pub total_images: Option<i64>,
}

#[derive(Debug, Deserialize)]
pub struct ImageDto {
    pub url: String,
}

#[derive(Debug, Deserialize)]
pub struct GenreDto {
    pub name: String,
}

/// One series, from either the list (partial) or detail (full) endpoint.
#[derive(Debug, Deserialize)]
pub struct SeriesDto {
    pub slug: String,
    pub title: String,
    #[serde(default)]
    pub cover: Option<String>,
    #[serde(rename = "type", default)]
    pub kind: Option<String>,
    #[serde(default)]
    pub status: Option<String>,
    #[serde(rename = "alternativeTitles", default)]
    pub alternative_titles: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub author: Option<String>,
    #[serde(default)]
    pub artist: Option<String>,
    #[serde(default)]
    pub genres: Option<Vec<GenreDto>>,
}

impl SeriesDto {
    pub fn into_manga(self) -> MangaInfo {
        let mut description = String::new();
        if let Some(raw) = self.description.as_deref().filter(|d| !d.trim().is_empty()) {
            description.push_str(&html_to_text(raw));
        }
        if let Some(alternatives) = self
            .alternative_titles
            .as_deref()
            .filter(|a| !a.trim().is_empty())
        {
            if !description.is_empty() {
                description.push_str("\n\n");
            }
            description.push_str(&format!("Alternative Titles: {alternatives}"));
        }

        let mut author: Vec<String> = Vec::new();
        if let Some(name) = self.author.as_deref().map(str::trim).filter(|a| !a.is_empty()) {
            author.push(name.to_string());
        }
        if let Some(name) = self.artist.as_deref().map(str::trim).filter(|a| !a.is_empty())
            && !author.iter().any(|existing| existing == name)
        {
            author.push(name.to_string());
        }

        MangaInfo {
            source_id: ID,
            title: self.title,
            author,
            genre: self
                .genres
                .unwrap_or_default()
                .into_iter()
                .map(|genre| genre.name)
                .collect(),
            status: to_status(self.status.as_deref()),
            description: (!description.trim().is_empty()).then_some(description.trim().to_string()),
            cover_url: self.cover.unwrap_or_default(),
            path: self.slug,
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct ChapterDto {
    pub slug: String,
    #[serde(default)]
    pub number: Option<f64>,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(rename = "requiresPurchase", default)]
    pub requires_purchase: Option<bool>,
    #[serde(rename = "createdAt", default)]
    pub created_at: Option<String>,
    #[serde(rename = "becameFreeAt", default)]
    pub became_free_at: Option<String>,
}

impl ChapterDto {
    pub fn into_chapter(self, series_slug: &str) -> ChapterInfo {
        let number = self.number.unwrap_or(-1.0);

        // Render the title the way the original does: prefer a clean
        // "Chapter N" unless the site supplied a real subtitle.
        let name = match self.title.as_deref().map(str::trim).filter(|t| !t.is_empty()) {
            Some(title) => {
                let number_text = if number.fract() == 0.0 {
                    format!("{}", number as i64)
                } else {
                    number.to_string()
                };
                if title == number_text || title.contains(&number_text) {
                    title.to_string()
                } else if number < 0.0 {
                    title.to_string()
                } else {
                    format!("Chapter {number_text} - {title}")
                }
            }
            None if number >= 0.0 => {
                if number.fract() == 0.0 {
                    format!("Chapter {}", number as i64)
                } else {
                    format!("Chapter {number}")
                }
            }
            None => "Chapter".to_string(),
        };

        let uploaded = parse_timestamp(self.became_free_at.as_deref().or(self.created_at.as_deref()));

        ChapterInfo {
            source_id: ID,
            title: name,
            path: format!("series/{series_slug}/chapters/{}", self.slug),
            number,
            scanlator: None,
            uploaded,
        }
    }
}

fn parse_timestamp(value: Option<&str>) -> i64 {
    value
        .and_then(|text| chrono::DateTime::parse_from_rfc3339(text).ok())
        .map(|dt| dt.timestamp())
        .unwrap_or(0)
}
