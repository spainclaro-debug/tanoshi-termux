//! Wire types for the Atsumaru JSON API.
//!
//! Field names and optionality mirror the live payloads (verified against
//! `api/home2/popular`, `api/manga/page`, `api/manga/allChapters` and
//! `api/read/chapter`), plus the `@JsonNames` aliases the Keiyoushi extension
//! accepts so both older and newer shapes decode.

use serde::Deserialize;
use serde_json::Value;
use tanoshi_lib::prelude::MangaInfo;

use crate::{ID, NAME, image_url, scalar_text, to_status};


#[derive(Debug, Deserialize)]
pub struct BrowseResponse {
    pub items: Vec<MangaDto>,
}

#[derive(Debug, Deserialize)]
pub struct MangaPageResponse {
    #[serde(rename = "mangaPage")]
    pub manga_page: MangaDto,
}

#[derive(Debug, Deserialize)]
pub struct SearchResponse {
    pub hits: Vec<SearchHit>,
}

#[derive(Debug, Deserialize)]
pub struct SearchHit {
    pub document: MangaDto,
}

#[derive(Debug, Deserialize)]
pub struct AllChaptersResponse {
    pub chapters: Vec<ChapterDto>,
}

#[derive(Debug, Deserialize)]
pub struct PageResponse {
    #[serde(rename = "readChapter")]
    pub read_chapter: ReadChapterDto,
}

#[derive(Debug, Deserialize)]
pub struct ReadChapterDto {
    pub pages: Vec<PageDto>,
}

#[derive(Debug, Deserialize)]
pub struct PageDto {
    pub image: String,
}

/// A manga as returned by the browse, detail and search endpoints.
///
/// `poster`/`image` are aliases: list endpoints send `image`, the detail
/// endpoint sends `poster`. Either can be a string or an object.
#[derive(Debug, Deserialize)]
pub struct MangaDto {
    pub id: String,
    pub title: String,
    #[serde(alias = "poster", alias = "image", default)]
    pub large_image: Option<Value>,
    #[serde(default)]
    pub authors: Option<Value>,
    #[serde(default)]
    pub synopsis: Option<String>,
    /// The API sends `genres` and `tags` as separate arrays; `genres` is the
    /// canonical one and `tags` is only used when it is absent (the list
    /// endpoints send neither).
    #[serde(default, rename = "genres")]
    pub genres: Option<Value>,
    #[serde(default)]
    pub tags: Option<Value>,
    #[serde(default)]
    pub released: Option<i64>,
    #[serde(default)]
    pub status: Option<String>,
    #[serde(rename = "type", default)]
    pub kind: Option<String>,
    #[serde(default)]
    pub views: Option<Value>,
    #[serde(rename = "otherNames", default)]
    pub other_names: Option<Vec<String>>,
    #[serde(rename = "avgRating", default)]
    pub avg_rating: Option<f64>,
}

impl MangaDto {
    fn image_path(&self) -> Option<String> {
        match self.large_image.as_ref()? {
            Value::String(text) => Some(text.clone()),
            Value::Object(map) => map
                .get("largeImage")
                .or_else(|| map.get("image"))
                .and_then(|value| value.as_str())
                .map(str::to_string),
            _ => None,
        }
    }

    fn names(&self) -> Vec<String> {
        let element = self.genres.as_ref().or(self.tags.as_ref());
        match element {
            Some(Value::Array(items)) => items
                .iter()
                .filter_map(|item| match item {
                    Value::String(text) => Some(text.clone()),
                    Value::Object(map) => {
                        map.get("name").and_then(|v| v.as_str()).map(str::to_string)
                    }
                    _ => None,
                })
                .collect(),
            _ => Vec::new(),
        }
    }

    /// Authors carry a `type` of `Author` or `Artist`; fall back to treating
    /// untyped entries as authors, as the original does.
    fn authors_with_type(&self) -> Vec<(String, Option<String>)> {
        match self.authors.as_ref() {
            Some(Value::Array(items)) => items
                .iter()
                .filter_map(|item| match item {
                    Value::String(text) => Some((text.clone(), None)),
                    Value::Object(map) => {
                        let name = map.get("name")?.as_str()?.to_string();
                        let kind = map.get("type").and_then(|v| v.as_str()).map(str::to_string);
                        Some((name, kind))
                    }
                    _ => None,
                })
                .collect(),
            _ => Vec::new(),
        }
    }

    fn description(&self) -> Option<String> {
        let mut parts: Vec<String> = Vec::new();

        if let Some(rating) = self.avg_rating.filter(|r| *r > 0.0) {
            parts.push(format!("**Rating**: {rating:.2}/10"));
        }

        if let Some(released) = self.released.filter(|r| *r > 0) {
            let year = chrono::DateTime::from_timestamp_millis(released)
                .map(|dt| dt.format("%Y").to_string())
                .unwrap_or_default();
            if !year.is_empty() {
                parts.push(format!("**Year**: {year}"));
            }
        }

        if let Some(views) = self.views.as_ref().and_then(scalar_text) {
            parts.push(format!("**Views**: {views}"));
        }

        if let Some(synopsis) = self.synopsis.as_deref().filter(|s| !s.trim().is_empty()) {
            parts.push(String::new());
            parts.push(format!("**Synopsis**: {synopsis}"));
        }

        let alternatives: Vec<&String> = self
            .other_names
            .as_ref()
            .map(|names| names.iter().filter(|n| **n != self.title).collect())
            .unwrap_or_default();
        if !alternatives.is_empty() {
            let rendered = alternatives
                .iter()
                .map(|name| format!("- {name}"))
                .collect::<Vec<_>>()
                .join("\n");
            parts.push(String::new());
            parts.push(format!("**Alternative Names**:\n{rendered}"));
        }

        if parts.is_empty() {
            None
        } else {
            Some(parts.join("\n"))
        }
    }

    pub fn into_manga(self, base_url: &str) -> MangaInfo {
        let cover_url = self.image_path().map(|p| image_url(base_url, &p)).unwrap_or_default();

        let mut genre: Vec<String> = self.kind.clone().into_iter().collect();
        genre.extend(self.names());

        let authors = self.authors_with_type();
        let author: Vec<String> = authors
            .iter()
            .filter(|(_, kind)| kind.as_deref() != Some("Artist"))
            .map(|(name, _)| name.clone())
            .collect();
        let artist: Vec<String> = authors
            .iter()
            .filter(|(_, kind)| kind.as_deref() == Some("Artist"))
            .map(|(name, _)| name.clone())
            .collect();

        let mut authors_out = author;
        authors_out.extend(artist);
        authors_out.dedup();

        log::debug!("{NAME}: decoded manga {}", self.id);

        let description = self.description();
        let status = to_status(self.status.as_deref());

        MangaInfo {
            source_id: ID,
            title: self.title,
            author: authors_out,
            genre,
            status,
            description,
            path: self.id,
            cover_url,
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct ChapterDto {
    pub id: String,
    pub number: f64,
    pub title: String,
    /// Milliseconds since the epoch, or an ISO timestamp in some payloads.
    #[serde(rename = "createdAt", default)]
    pub created_at: Option<Value>,
}

impl ChapterDto {
    pub fn date_upload_millis(&self) -> i64 {
        match self.created_at.as_ref() {
            Some(Value::Number(number)) => number.as_i64().unwrap_or(0),
            Some(Value::String(text)) => chrono::DateTime::parse_from_rfc3339(text)
                .map(|dt| dt.timestamp_millis())
                .unwrap_or(0),
            _ => 0,
        }
    }
}
