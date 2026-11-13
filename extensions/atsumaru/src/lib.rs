//! Atsumaru (`https://atsu.moe`) — Rust port of the Keiyoushi Tachiyomi extension.
//!
//! The site exposes a JSON API that the original extension drives directly, so
//! this is a faithful port of those endpoints rather than a scrape.

use anyhow::{Context, Result, anyhow};
use networking::{RateLimitedAgent, build_rate_limited_ureq_agent};
use serde::Deserialize;
use serde_json::Value;
use tanoshi_lib::prelude::{ChapterInfo, Extension, Input, Lang, MangaInfo, SourceInfo};

mod dto;
use dto::*;

mod search;

extension_utils::export_extension!(register, Atsumaru, NAME);

const ID: i64 = 2327480808438768017;
const NAME: &str = "Atsumaru";
const URL: &str = "https://atsu.moe";
const ICON_URL: &str = "https://atsu.moe/favicon.ico";
/// Keiyoushi pins `versionName = 1.6.26`; keep the numbers in step so updates
/// are visible to users.
const VERSION: &str = env!("CARGO_PKG_VERSION");
const REQUESTS_PER_SECOND: f64 = 2.0;
const BROWSE_LIMIT: i64 = 40;

/// Keiyoushi filters on these content types and media by default.
const BROWSE_TYPES: &str = "Manga,Manwha,Manhua,OEL";
const BROWSE_MEDIUMS: &str = "Comic";

pub struct Atsumaru {
    client: RateLimitedAgent,
}

impl Default for Atsumaru {
    fn default() -> Self {
        Self {
            client: build_rate_limited_ureq_agent(
                Some(concat!("Tanoshi-Extension/", env!("CARGO_PKG_VERSION"))),
                Some(REQUESTS_PER_SECOND),
            ),
        }
    }
}

/// Normalise an image path from the API into an absolute URL.
///
/// The API returns bare relative paths (`posters/x.jpg`, `/static/pages/...`)
/// and the original rewrites those onto the `cdn.` host.
const CDN_URL: &str = "https://cdn.atsu.moe";

/// Page images must come from the CDN host; the main site answers 410 for
/// them. Posters still resolve on the main host.
fn page_image_url(path: &str) -> String {
    if path.starts_with("http://") || path.starts_with("https://") {
        return path.replacen("http://", "https://", 1);
    }
    if let Some(rest) = path.strip_prefix("//") {
        return format!("https://{rest}");
    }
    let cleaned = path.trim_start_matches('/');
    format!("{CDN_URL}/{cleaned}")
}

fn image_url(base: &str, path: &str) -> String {
    if path.is_empty() {
        return String::new();
    }
    if path.starts_with("http://") || path.starts_with("https://") {
        return path.replacen("http://", "https://", 1);
    }
    if let Some(rest) = path.strip_prefix("//") {
        return format!("https://{rest}");
    }

    let cleaned = path
        .trim_start_matches('/')
        .strip_prefix("static/")
        .unwrap_or_else(|| path.trim_start_matches('/'));
    format!("{base}/static/{cleaned}")
}

fn to_status(status: Option<&str>) -> Option<String> {
    let status = status?.trim();
    if status.is_empty() {
        return None;
    }
    Some(match status.to_ascii_lowercase().as_str() {
        "ongoing" => "Ongoing",
        "completed" => "Completed",
        "hiatus" => "Hiatus",
        "cancelled" | "canceled" => "Cancelled",
        _ => "Unknown",
    }
    .to_string())
}

impl Atsumaru {
    fn get_json<T: for<'de> Deserialize<'de>>(&self, url: &str) -> Result<T> {
        log::debug!("{NAME}: GET {url}");
        self.client
            .fetch_json::<T>(url)
            .with_context(|| format!("requesting {url}"))
    }

    fn browse(&self, path: &str, extra: &str, page: i64) -> Result<Vec<MangaInfo>> {
        let offset = (page.max(1) - 1) * BROWSE_LIMIT;
        let url = format!(
            "{URL}/api/home2/{path}?offset={offset}&limit={BROWSE_LIMIT}&types={BROWSE_TYPES}&mediums={BROWSE_MEDIUMS}{extra}",
        );
        let data: BrowseResponse = self.get_json(&url)?;
        Ok(data.items.into_iter().map(|m| m.into_manga(URL)).collect())
    }
}

impl Extension for Atsumaru {
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
        self.browse("popular", "&timeframe=daily", page)
    }

    fn get_latest_manga(&self, page: i64) -> Result<Vec<MangaInfo>> {
        // `recentlyUpdated` has no timeframe parameter.
        let offset = (page.max(1) - 1) * BROWSE_LIMIT;
        let url = format!(
            "{URL}/api/home2/recentlyUpdated?offset={offset}&limit={BROWSE_LIMIT}&types={BROWSE_TYPES}&mediums={BROWSE_MEDIUMS}",
        );
        let data: BrowseResponse = self.get_json(&url)?;
        Ok(data.items.into_iter().map(|m| m.into_manga(URL)).collect())
    }

    fn search_manga(
        &self,
        page: i64,
        query: Option<String>,
        _filters: Option<Vec<Input>>,
    ) -> Result<Vec<MangaInfo>> {
        let url = search::search_url(page, query.as_deref().unwrap_or_default())?;
        let body = self.client.fetch_text(&url)?;

        // The search endpoint answers with either a Meilisearch hit list or a
        // plain browse payload; mirror the original's sniffing on `"hits"`.
        if body.contains("\"hits\"") {
            let data: SearchResponse =
                serde_json::from_str(&body).context("decoding Atsumaru search response")?;
            Ok(data
                .hits
                .into_iter()
                .map(|hit| hit.document.into_manga(URL))
                .collect())
        } else {
            let data: BrowseResponse =
                serde_json::from_str(&body).context("decoding Atsumaru browse response")?;
            Ok(data.items.into_iter().map(|m| m.into_manga(URL)).collect())
        }
    }

    fn get_manga_detail(&self, _path: String) -> Result<MangaInfo> {
        // `path` is the manga id; details carry everything the list view has.
        let url = format!("{URL}/api/manga/page?id={_path}");
        let data: MangaPageResponse = self.get_json(&url)?;
        Ok(data.manga_page.into_manga(URL))
    }

    fn get_chapters(&self, path: String) -> Result<Vec<ChapterInfo>> {
        let url = format!("{URL}/api/manga/allChapters?mangaId={path}");
        let data: AllChaptersResponse = self.get_json(&url)?;

        let mut chapters: Vec<ChapterInfo> = data
            .chapters
            .into_iter()
            .map(|chapter| {
                let uploaded = chapter.date_upload_millis() / 1000;
                ChapterInfo {
                    source_id: ID,
                    title: chapter.title,
                    path: format!("{path}/{}", chapter.id),
                    number: chapter.number,
                    scanlator: None,
                    uploaded,
                }
            })
            .collect();

        // Highest chapter first, matching the site's ordering.
        chapters.sort_by(|a, b| {
            b.number
                .partial_cmp(&a.number)
                .unwrap_or(std::cmp::Ordering::Equal)
        });
        Ok(chapters)
    }

    fn get_pages(&self, path: String) -> Result<Vec<String>> {
        let (manga_id, chapter_id) = path
            .split_once('/')
            .ok_or_else(|| anyhow!("malformed Atsumaru chapter path: {path}"))?;

        let url = format!("{URL}/api/read/chapter?mangaId={manga_id}&chapterId={chapter_id}");
        let data: PageResponse = self.get_json(&url)?;

        Ok(data
            .read_chapter
            .pages
            .into_iter()
            .map(|page| page_image_url(&page.image))
            .collect())
    }

    fn get_image_bytes(&self, url: String) -> Result<extension_utils::bytes::Bytes> {
        extension_utils::fetch_direct_image(&self.client, NAME, url, URL, NAME)
    }
}

/// Convert loosely typed JSON scalars (`Int`, `String`, or `null`) to text.
pub(crate) fn scalar_text(value: &Value) -> Option<String> {
    match value {
        Value::String(text) => Some(text.clone()),
        Value::Number(number) => Some(number.to_string()),
        Value::Bool(flag) => Some(flag.to_string()),
        _ => None,
    }
}
