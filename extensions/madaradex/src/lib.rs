//! MadaraDex (`https://madaradex.org`) — Rust port of the Keiyoushi Tachiyomi
//! extension, which extends the shared `Madara` WordPress-theme scraper.
//!
//! The site is a Madara-themed WordPress install, so listings come from
//! `wp-admin/admin-ajax.php` and everything else is scraped from HTML.
//!
//! MadaraDex adds an anti-bot handshake the generic Madara scraper does not
//! have: a `mdx_fp` fingerprint cookie plus a short-lived `mdx_auth` token
//! obtained from `action=mdx_auth_refresh`. CDN images return 403 until that
//! token is present, so [`ensure_auth`](MadaraDex::ensure_auth) is run before
//! any request that may touch the CDN.

use anyhow::{Context, Result, anyhow};
use networking::{RateLimitedAgent, build_rate_limited_ureq_agent};
use scraper::Html;
use tanoshi_lib::prelude::{ChapterInfo, Extension, Input, Lang, MangaInfo, SourceInfo};




mod parse;

extension_utils::export_extension!(register, MadaraDex, NAME);

const ID: i64 = 2136088453725931591;
const NAME: &str = "MadaraDex";
const URL: &str = "https://madaradex.org";
const ICON_URL: &str = "https://madaradex.org/favicon.ico";
const VERSION: &str = env!("CARGO_PKG_VERSION");
/// MadaraDex lists items under `/title/` (the theme's `mangaSubString`).
const MANGA_SUBSTRING: &str = "title";
const PAGE_SIZE: i64 = 25;

/// WordPress `admin-ajax.php`, used for archive listings and the auth refresh.
fn ajax_url() -> String {
    format!("{URL}/wp-admin/admin-ajax.php")
}

pub struct MadaraDex {
    client: RateLimitedAgent,
    auth: std::sync::Mutex<AuthState>,
}

/// Cookies for the anti-bot handshake.
///
/// The token is also stored by the agent's jar, but CDN requests go to a
/// different registrable domain and the jar will not replay it there, so the
/// values are kept here and sent explicitly.
#[derive(Default)]
struct AuthState {
    fingerprint: Option<String>,
    token: Option<String>,
}

impl Default for MadaraDex {
    fn default() -> Self {
        Self {
            client: build_rate_limited_ureq_agent(
                Some(concat!("Tanoshi-Extension/", env!("CARGO_PKG_VERSION"))),
                None,
            ),
            auth: std::sync::Mutex::new(AuthState::default()),
        }
    }
}

/// Reference header values matching a browser XHR.
fn xhr_headers() -> [(&'static str, &'static str); 2] {
    [
        ("X-Requested-With", "XMLHttpRequest"),
        ("Referer", const_url()),
    ]
}

const fn const_url() -> &'static str {
    URL
}

/// Extract one cookie value from a `Set-Cookie` header.
fn cookie_value(header: &str, name: &str) -> Option<String> {
    header
        .split(';')
        .find_map(|part| {
            let (key, value) = part.trim().split_once('=')?;
            (key.trim() == name).then(|| value.trim().to_string())
        })
        .filter(|value| !value.is_empty())
}

/// A 32-character hex fingerprint, standing in for `SecureRandom` bytes.
fn random_fingerprint() -> String {
    use std::hash::{Hash, Hasher};
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or_default()
        .hash(&mut hasher);
    std::process::id().hash(&mut hasher);
    let first = hasher.finish();
    format!("{first:016x}{:016x}", first.rotate_left(17) ^ 0x9e37_79b9_7f4a_7c15)
}

impl MadaraDex {
    /// Run the anti-bot handshake once per agent.
    ///
    /// The first request seeds `mdx_fp`; the refresh call issues `mdx_auth`,
    /// which the agent's cookie jar then replays on subsequent requests.
    fn ensure_auth(&self) -> Result<()> {
        {
            let state = self
                .auth
                .lock()
                .map_err(|_| anyhow!("{NAME}: auth state poisoned"))?;
            if state.token.is_some() {
                return Ok(());
            }
        }

        let fingerprint = random_fingerprint();
        // Seed the fingerprint cookie, then exchange it for an auth token.
        let _ = self.client.fetch_text_with_headers(
            &format!("{URL}/"),
            &[("Cookie", &format!("mdx_fp={fingerprint}"))],
        );

        let seed_cookie = format!("mdx_fp={fingerprint}");
        let refresh = self.client.post_form_with_headers(
            &ajax_url(),
            &[("action", "mdx_auth_refresh")],
            &[
                ("X-Mdx-Auth-Refresh", "1"),
                ("X-Requested-With", "XMLHttpRequest"),
                ("Referer", URL),
                ("Cookie", seed_cookie.as_str()),
            ],
        );

        match refresh {
            Ok((body, cookies)) => {
                log::debug!("{NAME}: auth refresh: {}", body.trim());
                // `mdx_auth` arrives in Set-Cookie; keep it explicitly because
                // the agent jar will not replay it to the CDN host.
                let token = cookies
                    .iter()
                    .find_map(|cookie| cookie_value(cookie, "mdx_auth"))
                    .unwrap_or_default();
                if token.is_empty() {
                    log::warn!("{NAME}: auth refresh returned no mdx_auth cookie");
                }
                if let Ok(mut state) = self.auth.lock() {
                    state.fingerprint = Some(fingerprint);
                    state.token = Some(token);
                }
            }
            Err(error) => {
                // Not fatal: non-CDN content still works without the token.
                log::warn!("{NAME}: auth refresh failed: {error:#}");
            }
        }
        Ok(())
    }

    /// Explicit `Cookie` header for CDN requests.
    fn cdn_cookies(&self) -> Option<String> {
        let state = self.auth.lock().ok()?;
        let fingerprint = state.fingerprint.as_deref()?;
        let token = state.token.as_deref().filter(|t| !t.is_empty())?;
        Some(format!("mdx_fp={fingerprint}; mdx_auth={token}"))
    }

    fn get_page(&self, url: &str) -> Result<Html> {
        let body = self
            .client
            .fetch_text_with_headers(url, &[("Referer", URL)])
            .with_context(|| format!("requesting {url}"))?;
        Ok(Html::parse_document(&body))
    }

    /// One page of the Madara archive listing, filtered by `mode`.
    fn archive(&self, page: i64, mode: &str, query: &str) -> Result<Vec<MangaInfo>> {
        let page = page.max(1);
        let mut form: Vec<(&str, String)> = vec![
            ("action", "madara_load_more".to_string()),
            ("page", (page - 1).to_string()),
            ("template", "madara-core/content/content-archive".to_string()),
            ("vars[paged]", "1".to_string()),
            ("vars[template]", "archive".to_string()),
            ("vars[post_type]", "wp-manga".to_string()),
            ("vars[post_status]", "publish".to_string()),
            ("vars[posts_per_page]", PAGE_SIZE.to_string()),
            ("vars[manga_archives_item_layout]", "default".to_string()),
        ];

        match mode {
            "popular" => {
                form.push(("vars[orderby]", "meta_value_num".to_string()));
                form.push(("vars[order]", "desc".to_string()));
                form.push(("vars[meta_key]", "_wp_manga_views".to_string()));
            }
            "latest" => {
                form.push(("vars[orderby]", "modified".to_string()));
                form.push(("vars[order]", "desc".to_string()));
            }
            _ => {
                form.push(("vars[s]", query.to_string()));
            }
        }

        let owned: Vec<(String, String)> = form
            .iter()
            .map(|(key, value)| (key.to_string(), value.to_string()))
            .collect();
        let borrowed: Vec<(&str, &str)> = owned
            .iter()
            .map(|(key, value)| (key.as_str(), value.as_str()))
            .collect();

        let (body, _cookies) = self
            .client
            .post_form_with_headers(&ajax_url(), &borrowed, &xhr_headers())
            .context("requesting MadaraDex archive listing")?;

        let fragment = Html::parse_fragment(&body);
        Ok(parse::archive_items(&fragment))
    }
}

impl Extension for MadaraDex {
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
        self.archive(page, "popular", "")
    }

    fn get_latest_manga(&self, page: i64) -> Result<Vec<MangaInfo>> {
        self.archive(page, "latest", "")
    }

    fn search_manga(
        &self,
        page: i64,
        query: Option<String>,
        _filters: Option<Vec<Input>>,
    ) -> Result<Vec<MangaInfo>> {
        let query = query.unwrap_or_default();
        self.archive(page, "search", query.trim())
    }

    fn get_manga_detail(&self, path: String) -> Result<MangaInfo> {
        let slug = parse::slug(&path);
        let url = format!("{URL}/{MANGA_SUBSTRING}/{slug}/");
        let document = self.get_page(&url)?;
        Ok(parse::manga_detail(&document, &slug))
    }

    fn get_chapters(&self, path: String) -> Result<Vec<ChapterInfo>> {
        let slug = parse::slug(&path);
        let url = format!("{URL}/{MANGA_SUBSTRING}/{slug}/");
        let document = self.get_page(&url)?;
        Ok(parse::chapters(&document, &slug))
    }

    fn get_pages(&self, path: String) -> Result<Vec<String>> {
        // `path` is the chapter URL path, e.g. `title/<slug>/chapter-12`.
        self.ensure_auth()?;

        let url = if path.starts_with("http") {
            path
        } else {
            format!("{URL}/{}", path.trim_start_matches('/'))
        };

        let document = self.get_page(&url)?;
        let pages = parse::chapter_pages(&document, &url)?;
        if pages.is_empty() {
            return Err(anyhow!("{NAME}: no pages found at {url}"));
        }
        Ok(pages)
    }

    fn get_image_bytes(&self, url: String) -> Result<extension_utils::bytes::Bytes> {
        // The CDN requires the anti-bot cookies plus a site Referer.
        self.ensure_auth()?;
        let cookies = self.cdn_cookies();
        let headers: Vec<(&str, &str)> = match cookies.as_deref() {
            Some(cookie) => vec![("Cookie", cookie)],
            None => Vec::new(),
        };
        self.client.fetch_image_with_headers(&url, Some(URL), &headers)
    }
}
