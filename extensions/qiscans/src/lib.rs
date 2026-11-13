//! Qi Scans (`https://qimanga.com`) — Rust port of the Keiyoushi Tachiyomi
//! extension, which builds on the shared `EZManhwa` multi-source base.
//!
//! All data comes from `api.qimanga.com`'s JSON API:
//!
//! | operation | endpoint |
//! |---|---|
//! | popular / latest / browse | `/api/v1/series?page&perPage&sort` |
//! | search | `/api/v1/series/search?q` |
//! | details | `/api/v1/series/{slug}` |
//! | chapters | `/api/v1/series/{slug}/chapters?page&perPage&sort` |
//! | pages | `/api/v1/series/{slug}/chapters/{chapterSlug}` |

use anyhow::{Context, Result, anyhow, bail};
use networking::{RateLimitedAgent, build_rate_limited_ureq_agent};
use serde::Deserialize;
use tanoshi_lib::prelude::{ChapterInfo, Extension, Input, Lang, MangaInfo, SourceInfo};

mod dto;
use dto::*;

extension_utils::export_extension!(register, QiScans, NAME);

const ID: i64 = 5806040666300479660;
const NAME: &str = "QiScans";
const URL: &str = "https://qimanga.com";
const API_URL: &str = "https://api.qimanga.com/api/v1";
const ICON_URL: &str = "https://qimanga.com/favicon.ico";
const VERSION: &str = env!("CARGO_PKG_VERSION");
const PER_PAGE: i64 = 20;
/// The chapter listing is paginated at 100 entries per page.
const CHAPTER_PAGE_SIZE: i64 = 100;
/// Guard against a runaway chapter listing.
const MAX_CHAPTER_PAGES: i64 = 200;

pub struct QiScans {
    client: RateLimitedAgent,
}

impl Default for QiScans {
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
            "ONGOING" | "MASS_RELEASED" => "Ongoing",
            "COMPLETED" => "Completed",
            "DROPPED" => "Cancelled",
            "HIATUS" => "Hiatus",
            _ => "Unknown",
        }
        .to_string(),
    )
}

impl QiScans {
    fn get_json<T: for<'de> Deserialize<'de>>(&self, url: &str) -> Result<T> {
        log::debug!("{NAME}: GET {url}");
        self.client
            .fetch_json::<T>(url)
            .with_context(|| format!("requesting {url}"))
    }

    /// Parse one page of the series listing, dropping novel entries as the
    /// original does.
    fn parse_series(&self, url: &str) -> Result<Vec<MangaInfo>> {
        let page: SeriesListResponse = self.get_json(url)?;
        Ok(page
            .data
            .into_iter()
            .filter(|series| series.kind.as_deref() != Some("NOVEL"))
            .map(|series| series.into_manga())
            .collect())
    }
}

impl Extension for QiScans {
    fn get_source_info(&self) -> SourceInfo {
        SourceInfo {
            id: ID,
            name: NAME.to_string(),
            url: URL.to_string(),
            version: VERSION,
            icon: ICON_URL,
            languages: Lang::Single("en".to_string()),
            nsfw: false,
        }
    }

    fn get_popular_manga(&self, page: i64) -> Result<Vec<MangaInfo>> {
        let url = format!("{API_URL}/series?page={}&perPage={PER_PAGE}&sort=popular", page.max(1));
        self.parse_series(&url)
    }

    fn get_latest_manga(&self, page: i64) -> Result<Vec<MangaInfo>> {
        let url = format!("{API_URL}/series?page={}&perPage={PER_PAGE}&sort=latest", page.max(1));
        self.parse_series(&url)
    }

    fn search_manga(
        &self,
        page: i64,
        query: Option<String>,
        _filters: Option<Vec<Input>>,
    ) -> Result<Vec<MangaInfo>> {
        let page = page.max(1);
        // The search endpoint ignores filters entirely, so only the keyword is
        // forwarded; a blank query falls back to the latest listing.
        let url = match query.as_deref().map(str::trim).filter(|q| !q.is_empty()) {
            Some(query) => format!(
                "{API_URL}/series/search?page={page}&perPage={PER_PAGE}&q={}",
                urlencoding::encode(query)
            ),
            None => format!("{API_URL}/series?page={page}&perPage={PER_PAGE}&sort=latest"),
        };
        self.parse_series(&url)
    }

    fn get_manga_detail(&self, path: String) -> Result<MangaInfo> {
        // The detail endpoint returns the series object directly, while the
        // list endpoints wrap it in `data`.
        let url = format!("{API_URL}/series/{path}");
        let series: SeriesDto = self.get_json(&url)?;
        Ok(series.into_manga())
    }

    fn get_chapters(&self, path: String) -> Result<Vec<ChapterInfo>> {
        let mut chapters: Vec<ChapterInfo> = Vec::new();
        let mut page = 1;
        let mut total_pages = 1;

        while page <= total_pages && page <= MAX_CHAPTER_PAGES {
            let url = format!(
                "{API_URL}/series/{path}/chapters?page={page}&perPage={CHAPTER_PAGE_SIZE}&sort=desc"
            );
            let data: ChapterListResponse = self.get_json(&url)?;

            if page == 1 {
                total_pages = data.total_pages.max(1);
            }

            for chapter in data.data {
                // Locked chapters are hidden unless requested, matching the
                // original's default preference.
                if chapter.requires_purchase == Some(true) {
                    continue;
                }
                chapters.push(chapter.into_chapter(&path));
            }

            page += 1;
        }

        Ok(chapters)
    }

    fn get_pages(&self, path: String) -> Result<Vec<String>> {
        let url = format!("{API_URL}/{path}");
        let data: PageListResponse = self.get_json(&url)?;

        if data.requires_purchase == Some(true) {
            bail!(
                "{NAME}: chapter requires purchase ({} pages); buy it on the website to read it",
                data.total_images.unwrap_or(0)
            );
        }

        let images = data
            .images
            .ok_or_else(|| anyhow!("{NAME}: chapter has no images (locked or login required)"))?;

        if images.is_empty() {
            return Err(anyhow!("{NAME}: chapter returned an empty page list"));
        }

        Ok(images.into_iter().map(|image| image.url).collect())
    }

    fn get_image_bytes(&self, url: String) -> Result<extension_utils::bytes::Bytes> {
        extension_utils::fetch_direct_image(&self.client, NAME, url, URL, NAME)
    }
}

/// Strip inline HTML from a description.
///
/// `Html::parse_fragment` has no `body` element, so collect the fragment's
/// text nodes directly and collapse whitespace.
pub(crate) fn html_to_text(html: &str) -> String {
    let document = scraper::Html::parse_fragment(html);
    document
        .root_element()
        .text()
        .collect::<Vec<_>>()
        .join(" ")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}
