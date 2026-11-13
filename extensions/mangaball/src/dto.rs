//! Wire types for the Manga Ball API (`mangaball.com/api/v1`).
//!
//! Ported from the Keiyoushi `Dto.kt`; the cover priority and Windows-style
//! cover paths mirror what the site and original extension do.

use serde::Deserialize;
use tanoshi_lib::prelude::{ChapterInfo, MangaInfo};

use crate::{ID, to_status};

/// Base for self-hosted covers, whose paths look like `<titleId>\\cover_1.jpg`.
const COVER_BASE_URL: &str = "https://bulbasaur.poke-black-and-white.net/covers/";

#[derive(Debug, Deserialize)]
pub struct SearchResponse {
    pub data: Vec<MangaDto>,
}

#[derive(Debug, Deserialize)]
pub struct MangaDto {
    pub id: String,
    #[serde(default)]
    #[allow(dead_code)] // kept for wire compatibility; `id` is the stable path
    pub slug: String,
    pub name: String,
    #[serde(default)]
    pub image: Option<ImageDto>,
}

impl MangaDto {
    pub fn into_manga(self) -> MangaInfo {
        let cover_url = self.image.and_then(|image| image.url()).unwrap_or_default();
        MangaInfo {
            source_id: ID,
            title: self.name,
            author: Vec::new(),
            genre: Vec::new(),
            status: None,
            description: None,
            path: self.id,
            cover_url,
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct TitleResponse {
    pub data: TitleDto,
}

#[derive(Debug, Deserialize)]
pub struct TitleDto {
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub image: Option<ImageDto>,
    #[serde(default)]
    pub description: Vec<String>,
    #[serde(rename = "alternateName", default)]
    pub alternate_name: Vec<String>,
    #[serde(default)]
    pub tags: Vec<TagDto>,
    #[serde(default)]
    pub author: Vec<AuthorDto>,
    #[serde(default)]
    pub status: Option<String>,
}

impl TitleDto {
    pub fn into_manga(self) -> MangaInfo {
        let cover_url = self
            .image
            .as_ref()
            .and_then(|image| image.url())
            .unwrap_or_default();

        let mut description = self.description.join("\n\n");
        if !self.alternate_name.is_empty() {
            let names = self
                .alternate_name
                .iter()
                .map(|name| format!("- {name}"))
                .collect::<Vec<_>>()
                .join("\n");
            description.push_str(&format!("\n\nAlternative Names: \n{names}"));
        }

        let mut author: Vec<String> = Vec::new();
        for entry in &self.author {
            let name = entry.name.trim();
            if !name.is_empty() && !author.iter().any(|existing| existing == name) {
                author.push(name.to_string());
            }
        }

        MangaInfo {
            source_id: ID,
            title: self.name,
            author,
            genre: self.tags.into_iter().map(|tag| tag.name).collect(),
            status: to_status(self.status.as_deref()),
            description: {
                let trimmed = description.trim();
                (!trimmed.is_empty()).then(|| trimmed.to_string())
            },
            path: self.id,
            cover_url,
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct ImageDto {
    #[serde(default)]
    pub file: Option<String>,
    #[serde(rename = "cdn_mangadex", default)]
    pub cdn_mangadex: Option<String>,
    #[serde(rename = "cdn_mangaupdate", default)]
    pub cdn_mangaupdate: Option<String>,
    #[serde(rename = "cdn_mangaupdates", default)]
    pub cdn_mangaupdates: Option<String>,
    #[serde(default)]
    pub cover: Option<CoverDto>,
}

impl ImageDto {
    /// Self-hosted cover first, external mirrors only as fallbacks.
    pub fn url(&self) -> Option<String> {
        self.cover
            .as_ref()
            .and_then(|cover| cover.url())
            .or_else(|| {
                [
                    self.file.as_deref(),
                    self.cdn_mangadex.as_deref(),
                    self.cdn_mangaupdate.as_deref(),
                    self.cdn_mangaupdates.as_deref(),
                ]
                .into_iter()
                .flatten()
                .find(|value| !value.trim().is_empty())
                .map(str::to_string)
            })
    }
}

#[derive(Debug, Deserialize)]
pub struct CoverDto {
    #[serde(default)]
    pub path: Option<String>,
}

impl CoverDto {
    /// Cover paths arrive with Windows separators and are relative to the CDN.
    pub fn url(&self) -> Option<String> {
        let path = self.path.as_deref()?.trim().replace('\\', "/");
        if path.is_empty() {
            return None;
        }
        if path.starts_with("http://") || path.starts_with("https://") {
            Some(path)
        } else {
            Some(format!("{COVER_BASE_URL}{path}"))
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct TagDto {
    pub name: String,
}

#[derive(Debug, Deserialize)]
pub struct AuthorDto {
    #[serde(default)]
    pub name: String,
}

#[derive(Debug, Deserialize)]
pub struct ChapterListResponse {
    pub data: Vec<ChapterDto>,
}

#[derive(Debug, Deserialize)]
pub struct ChapterDto {
    pub id: String,
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub number: Option<f64>,
    #[serde(default)]
    pub volume: f64,
    #[serde(default)]
    pub lang: String,
    #[serde(default)]
    pub group: Option<GroupDto>,
    #[serde(rename = "created_at", default)]
    pub created_at: Option<String>,
}

impl ChapterDto {
    /// `None` when the chapter is not in one of the accepted languages.
    pub fn into_chapter(self, langs: &[&str]) -> Option<ChapterInfo> {
        if !langs.contains(&self.lang.as_str()) {
            return None;
        }

        let number = self.number.unwrap_or(-1.0);
        let title = self.name.as_deref().unwrap_or_default().trim().to_string();

        let mut label = String::new();
        if self.volume > 0.0 {
            label.push_str(&format!("Vol. {} ", self.volume));
        }

        let number_text = if number.fract() == 0.0 {
            format!("{}", number as i64)
        } else {
            number.to_string()
        };

        if number >= 0.0 && title.contains(&number_text) {
            label.push_str(&title);
        } else {
            label.push_str(&format!("Ch. {number_text}"));
            if !title.is_empty() {
                label.push(' ');
                label.push_str(&title);
            }
        }

        Some(ChapterInfo {
            source_id: ID,
            title: label.trim().to_string(),
            path: self.id,
            number,
            scanlator: self.group.map(|group| group.name),
            uploaded: parse_timestamp(self.created_at.as_deref()),
        })
    }
}

#[derive(Debug, Deserialize)]
pub struct GroupDto {
    #[serde(default)]
    pub name: String,
}

#[derive(Debug, Deserialize)]
pub struct ChapterDetailResponse {
    pub data: ChapterDetailData,
}

#[derive(Debug, Deserialize)]
pub struct ChapterDetailData {
    pub chapter: ChapterDetailDto,
}

#[derive(Debug, Deserialize)]
pub struct ChapterDetailDto {
    #[serde(default)]
    pub pages: Vec<String>,
}

/// `created_at` is an ISO local date-time without a zone; treat it as UTC.
fn parse_timestamp(value: Option<&str>) -> i64 {
    let Some(text) = value else { return 0 };
    if let Ok(dt) = chrono::DateTime::parse_from_rfc3339(text) {
        return dt.timestamp();
    }
    chrono::NaiveDateTime::parse_from_str(text, "%Y-%m-%dT%H:%M:%S%.f")
        .map(|naive| naive.and_utc().timestamp())
        .unwrap_or(0)
}
