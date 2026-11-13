//! Manga Ball (`https://mangaball.com`) — Rust port of the Keiyoushi Tachiyomi
//! extension.
//!
//! The site is a Next.js app backed by a JSON API at `/api/v1`:
//!
//! | operation | endpoint |
//! |---|---|
//! | browse / search | `GET /api/v1/title/search-advanced` |
//! | details | `GET /api/v1/title/detail/{id}` |
//! | chapters | `POST /api/v1/chapter/chapter-listing-by-title-id` |
//! | pages | `GET /api/v1/chapter-detail?chapter_id` |
//!
//! Keiyoushi publishes one source per language for this extension. Tanoshi
//! sources are single-language, so this port mirrors the English source and
//! keeps chapters whose language is English.

use anyhow::{Context, Result};
use networking::{RateLimitedAgent, build_rate_limited_ureq_agent};
use serde::Deserialize;
use tanoshi_lib::prelude::{ChapterInfo, Extension, Input, Lang, MangaInfo, SourceInfo};

mod dto;
use dto::*;

extension_utils::export_extension!(register, MangaBall, NAME);

/// Source id of the English source in Keiyoushi's index.
const ID: i64 = 1448906013733277368;
const NAME: &str = "Manga Ball";
const URL: &str = "https://mangaball.com";
const ICON_URL: &str = "https://mangaball.com/images/favicon.png";
const VERSION: &str = env!("CARGO_PKG_VERSION");
const PER_PAGE: i64 = 24;

/// Languages accepted for this source, mirroring Keiyoushi's `siteLang` for
/// English.
const SITE_LANGS: &[&str] = &["en"];

pub struct MangaBall {
    client: RateLimitedAgent,
}

impl Default for MangaBall {
    fn default() -> Self {
        Self {
            client: build_rate_limited_ureq_agent(
                Some(concat!("Tanoshi-Extension/", env!("CARGO_PKG_VERSION"))),
                None,
            ),
        }
    }
}

fn to_status(status: Option<&str>) -> Option<String> {
    let status = status?;
    Some(
        match status {
            "ongoing" => "Ongoing",
            "completed" => "Completed",
            "hiatus" => "Hiatus",
            "cancelled" => "Cancelled",
            _ => "Unknown",
        }
        .to_string(),
    )
}

impl MangaBall {
    fn get_json<T: for<'de> Deserialize<'de>>(&self, url: &str) -> Result<T> {
        log::debug!("{NAME}: GET {url}");
        self.client
            .fetch_json::<T>(url)
            .with_context(|| format!("requesting {url}"))
    }

    /// `GET /title/search-advanced` backs popular, latest and search alike.
    fn search_advanced(
        &self,
        page: i64,
        query: &str,
        sort_by: Option<&str>,
    ) -> Result<Vec<MangaInfo>> {
        let mut url = format!(
            "{URL}/api/v1/title/search-advanced?page={}&limit={PER_PAGE}&tag_mode=AND&adult_mode=all&chapterLanguage={}",
            page.max(1),
            urlencoding::encode(&SITE_LANGS.join(",")),
        );

        if let Some(sort_by) = sort_by {
            url.push_str(&format!("&sort_by={sort_by}&sort_order=desc"));
        }

        let query = query.trim();
        if !query.is_empty() {
            url.push_str(&format!("&keyword={}", urlencoding::encode(query)));
        }

        let response: SearchResponse = self.get_json(&url)?;
        Ok(response.data.into_iter().map(|m| m.into_manga()).collect())
    }

    fn get_title(&self, id_or_slug: &str) -> Result<MangaInfo> {
        let url = format!("{URL}/api/v1/title/detail/{id_or_slug}");
        let response: TitleResponse = self.get_json(&url)?;
        Ok(response.data.into_manga())
    }
}

impl Extension for MangaBall {
    fn get_source_info(&self) -> SourceInfo {
        SourceInfo {
            id: ID,
            name: NAME.to_string(),
            url: URL.to_string(),
            version: VERSION,
            icon: ICON_URL,
            languages: Lang::Single("en".to_string()),
            nsfw: true,
        }
    }

    fn get_popular_manga(&self, page: i64) -> Result<Vec<MangaInfo>> {
        self.search_advanced(page, "", Some("views"))
    }

    fn get_latest_manga(&self, page: i64) -> Result<Vec<MangaInfo>> {
        self.search_advanced(page, "", Some("lastupdate"))
    }

    fn search_manga(
        &self,
        page: i64,
        query: Option<String>,
        _filters: Option<Vec<Input>>,
    ) -> Result<Vec<MangaInfo>> {
        let query = query.unwrap_or_default();
        // Keyword-less browse falls back to last-updated so it stays ordered;
        // with a keyword the API orders by relevance when sort_by is omitted.
        let sort_by = if query.trim().is_empty() {
            Some("lastupdate")
        } else {
            None
        };
        self.search_advanced(page, &query, sort_by)
    }

    fn get_manga_detail(&self, path: String) -> Result<MangaInfo> {
        self.get_title(&path)
    }

    fn get_chapters(&self, path: String) -> Result<Vec<ChapterInfo>> {
        let body = serde_json::json!({ "title_id": path }).to_string();
        let url = format!("{URL}/api/v1/chapter/chapter-listing-by-title-id");

        let response: ChapterListResponse = self
            .client
            .fetch_json_post(&url, &body)
            .with_context(|| format!("requesting {url}"))?;

        let mut chapters: Vec<ChapterInfo> = response
            .data
            .into_iter()
            .filter_map(|chapter| chapter.into_chapter(SITE_LANGS))
            .collect();

        chapters.sort_by(|a, b| {
            b.number
                .partial_cmp(&a.number)
                .unwrap_or(std::cmp::Ordering::Equal)
        });
        Ok(chapters)
    }

    fn get_pages(&self, path: String) -> Result<Vec<String>> {
        let url = format!("{URL}/api/v1/chapter-detail?chapter_id={path}");
        let response: ChapterDetailResponse = self.get_json(&url)?;
        Ok(response.data.chapter.pages)
    }

    fn get_image_bytes(&self, url: String) -> Result<extension_utils::bytes::Bytes> {
        extension_utils::fetch_direct_image(&self.client, NAME, url, URL, NAME)
    }
}
