//! HentaiNexus (`https://hentainexus.com`) — Rust port of the Keiyoushi
//! Tachiyomi extension.
//!
//! This is an HTML scraper. Listings come from `/`, `/explore/hot` and
//! `/page/<n>`, details from `/view/<id>`, and the reader at `/read/<id>`
//! embeds an encrypted JSON payload that [`decrypt`] unwraps.
//!
//! The site lists one "chapter" per gallery, so each manga yields a single
//! chapter pointing at its reader page.

use anyhow::{Context, Result, anyhow};
use networking::{RateLimitedAgent, build_rate_limited_ureq_agent};
use serde_json::Value;
use tanoshi_lib::prelude::{ChapterInfo, Extension, Input, Lang, MangaInfo, SourceInfo};

mod decrypt;
mod parse;

extension_utils::export_extension!(register, HentaiNexus, NAME);

/// Published source id in Keiyoushi's index.
const ID: i64 = 7719798645438596394;
const NAME: &str = "HentaiNexus";
const URL: &str = "https://hentainexus.com";
const ICON_URL: &str = "";
const VERSION: &str = env!("CARGO_PKG_VERSION");
/// `/explore/hot` is the "popular now" listing.
const POPULAR_NOW_PATH: &str = "/explore/hot";
/// Searching `id:<n>` resolves a single gallery by id.
const ID_SEARCH_PREFIX: &str = "id:";
/// The reader JSON exposes several renditions; use the WebP fallback by default
/// (original quality requires an account).
const IMAGE_FIELD: &str = "image_fallback";

pub struct HentaiNexus {
    client: RateLimitedAgent,
}

impl Default for HentaiNexus {
    fn default() -> Self {
        Self {
            client: build_rate_limited_ureq_agent(
                Some(concat!("Tanoshi-Extension/", env!("CARGO_PKG_VERSION"))),
                Some(1.0),
            ),
        }
    }
}

impl HentaiNexus {
    fn fetch_html(&self, url: &str) -> Result<String> {
        log::debug!("{NAME}: GET {url}");
        self.client
            .fetch_text(url)
            .with_context(|| format!("requesting {url}"))
    }

    /// Resolve a stored path or URL to an absolute URL on this site.
    fn absolute(&self, path: &str) -> String {
        if path.starts_with("http://") || path.starts_with("https://") {
            path.to_string()
        } else {
            format!("{URL}/{}", path.trim_start_matches('/'))
        }
    }
}

impl Extension for HentaiNexus {
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
        let page = page.max(1);
        // Page 1 uses the curated "hot" listing; later pages fall back to a
        // popularity-sorted search, exactly as the original does.
        if page == 1 {
            let html = self.fetch_html(&self.absolute(POPULAR_NOW_PATH))?;
            return Ok(parse::listing(&html));
        }
        let url = format!("{URL}/page/{}?q={}", page - 1, urlencoding::encode("sort:popular"));
        let html = self.fetch_html(&url)?;
        Ok(parse::listing(&html))
    }

    fn get_latest_manga(&self, page: i64) -> Result<Vec<MangaInfo>> {
        let page = page.max(1);
        let url = if page > 1 {
            format!("{URL}/page/{page}")
        } else {
            URL.to_string()
        };
        let html = self.fetch_html(&url)?;
        Ok(parse::listing(&html))
    }

    fn search_manga(
        &self,
        page: i64,
        query: Option<String>,
        _filters: Option<Vec<Input>>,
    ) -> Result<Vec<MangaInfo>> {
        let query = query.unwrap_or_default();
        let trimmed = query.trim();

        // `id:<n>` resolves one gallery directly.
        if let Some(id) = trimmed.strip_prefix(ID_SEARCH_PREFIX) {
            let id = id.trim();
            if !id.is_empty() {
                let html = self.fetch_html(&format!("{URL}/view/{id}"))?;
                let mut manga = parse::detail(&html, id)?;
                manga.path = id.to_string();
                return Ok(vec![manga]);
            }
        }

        let page = page.max(1);
        let mut url = if page > 1 {
            format!("{URL}/page/{page}")
        } else {
            URL.to_string()
        };
        if !trimmed.is_empty() {
            url.push_str(&format!("?q={}", urlencoding::encode(trimmed)));
        }
        let html = self.fetch_html(&url)?;
        Ok(parse::listing(&html))
    }

    fn get_manga_detail(&self, path: String) -> Result<MangaInfo> {
        let id = parse::gallery_id(&path);
        if id.is_empty() {
            return Err(anyhow!("{NAME}: empty gallery id"));
        }
        let html = self.fetch_html(&format!("{URL}/view/{id}"))?;
        parse::detail(&html, &id)
    }

    fn get_chapters(&self, path: String) -> Result<Vec<ChapterInfo>> {
        let id = parse::gallery_id(&path);
        if id.is_empty() {
            return Err(anyhow!("{NAME}: empty gallery id"));
        }
        let html = self.fetch_html(&format!("{URL}/view/{id}"))?;
        Ok(parse::chapters(&html, &id))
    }

    fn get_pages(&self, path: String) -> Result<Vec<String>> {
        // `path` is stored as `read/<id>`.
        let id = parse::gallery_id(&path);
        if id.is_empty() {
            return Err(anyhow!("{NAME}: empty gallery id"));
        }
        let url = format!("{URL}/read/{id}");
        let html = self.fetch_html(&url)?;

        let payload = parse::reader_payload(&html)
            .ok_or_else(|| anyhow!("{NAME}: could not find initReader payload at {url}"))?;

        let json = decrypt::decrypt_payload(&payload)
            .with_context(|| format!("decrypting reader payload for gallery {id}"))?;

        let entries: Value = serde_json::from_str(&json)
            .context("decoding decrypted reader JSON")?;
        let items = entries
            .as_array()
            .ok_or_else(|| anyhow!("{NAME}: reader JSON was not an array"))?;

        let mut pages: Vec<String> = Vec::new();
        for item in items {
            if item.get("type").and_then(Value::as_str) != Some("image") {
                continue;
            }
            match item.get(IMAGE_FIELD).and_then(Value::as_str) {
                Some(url) => pages.push(url.to_string()),
                None => {
                    return Err(anyhow!(
                        "{NAME}: the selected image quality is unavailable for this gallery"
                    ));
                }
            }
        }

        if pages.is_empty() {
            return Err(anyhow!("{NAME}: no image entries in reader payload"));
        }
        Ok(pages)
    }

    fn get_image_bytes(&self, url: String) -> Result<extension_utils::bytes::Bytes> {
        extension_utils::fetch_direct_image(&self.client, NAME, url, URL, NAME)
    }
}
