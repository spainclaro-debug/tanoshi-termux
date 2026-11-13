//! HTML scraping for MadaraDex.
//!
//! Selectors follow the live Madara theme markup (verified against the archive
//! ajax payload, a manga detail page and a chapter page).

use scraper::{ElementRef, Html, Selector};
use tanoshi_lib::prelude::{ChapterInfo, MangaInfo};

use crate::{ID, MANGA_SUBSTRING, URL};

/// Column selectors used by the theme for archive cards.
const CARD_SELECTORS: [&str; 3] = [
    "div.page-item-detail",
    ".manga__item",
    ".c-tabs-item__content",
];

fn selector(value: &str) -> Selector {
    Selector::parse(value).expect("static selector")
}

/// Extract the manga slug from a stored path or a full URL.
pub fn slug(path: &str) -> String {
    let trimmed = path.trim().trim_end_matches('/');
    let without_scheme = trimmed
        .trim_start_matches("https://")
        .trim_start_matches("http://");
    let parts: Vec<&str> = without_scheme.split('/').filter(|p| !p.is_empty()).collect();
    match parts.as_slice() {
        // host/title/slug
        [host, dir, slug] if *host == "madaradex.org" && *dir == MANGA_SUBSTRING => (*slug).to_string(),
        // title/slug
        [dir, slug] if *dir == MANGA_SUBSTRING => (*slug).to_string(),
        _ => parts.last().copied().unwrap_or(trimmed).to_string(),
    }
}

fn text_of(element: &ElementRef<'_>) -> String {
    element
        .text()
        .collect::<Vec<_>>()
        .join(" ")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

fn attr(element: &ElementRef<'_>, name: &str) -> Option<String> {
    element.value().attr(name).map(str::to_string)
}

/// Prefer a lazyloaded image source over the placeholder `src`.
fn image_src(img: &ElementRef<'_>) -> Option<String> {
    ["data-src", "data-lazy-src", "src"]
        .iter()
        .filter_map(|name| attr(img, name))
        .find(|value| !value.contains("dflazy") && !value.starts_with("data:"))
        .map(|value| value.trim().to_string())
}

fn absolute(url: &str) -> String {
    if url.starts_with("http") {
        url.to_string()
    } else {
        format!("{URL}/{}", url.trim_start_matches('/'))
    }
}

/// Parse the manga cards from an archive listing fragment.
pub fn archive_items(fragment: &Html) -> Vec<MangaInfo> {
    let mut out: Vec<MangaInfo> = Vec::new();

    for card_selector in CARD_SELECTORS {
        let cards = selector(card_selector);
        let mut found_any = false;

        for card in fragment.select(&cards) {
            let Some(link) = card.select(&selector(".post-title a")).next() else {
                continue;
            };
            let Some(href) = attr(&link, "href") else {
                continue;
            };

            let slug = slug(&href);
            if slug.is_empty() || out.iter().any(|m| m.path == slug) {
                continue;
            }

            let title = text_of(&link);
            let cover_url = card
                .select(&selector("img"))
                .next()
                .and_then(|img| image_src(&img))
                .map(|src| absolute(&src))
                .unwrap_or_default();

            out.push(MangaInfo {
                source_id: ID,
                title,
                author: Vec::new(),
                genre: Vec::new(),
                status: None,
                description: None,
                path: slug,
                cover_url,
            });
            found_any = true;
        }

        if found_any {
            break;
        }
    }

    out
}

/// Parse a manga detail page.
pub fn manga_detail(document: &Html, slug: &str) -> MangaInfo {
    let title = document
        .select(&selector("div.post-title h1"))
        .next()
        .map(|element| text_of(&element))
        .filter(|title| !title.is_empty())
        .unwrap_or_else(|| slug.replace('-', " "));

    let mut author: Vec<String> = Vec::new();
    for selector_text in ["div.author-content > a", "div.manga-authors > a"] {
        for element in document.select(&selector(selector_text)) {
            let name = text_of(&element);
            if !name.is_empty() && !author.contains(&name) {
                author.push(name);
            }
        }
    }

    let cover_url = document
        .select(&selector("div.summary_image img"))
        .next()
        .and_then(|img| image_src(&img))
        .map(|src| absolute(&src))
        .unwrap_or_default();

    let mut genre: Vec<String> = Vec::new();
    for element in document.select(&selector("div.genres-content a")) {
        let name = text_of(&element);
        if !name.is_empty() && !genre.contains(&name) {
            genre.push(name);
        }
    }

    let description = document
        .select(&selector(
            "div.description-summary div.summary__content, div.summary_content div.post-content_item > h5 + div, div.summary_content div.manga-excerpt",
        ))
        .next()
        .map(|element| text_of(&element))
        .filter(|text| !text.is_empty());

    // `:contains()` is a Jsoup extension that `scraper` does not implement, so
    // find the info row whose heading reads "Status" by hand.
    let status = find_summary_content(document, "status").map(|value| normalize_status(&value));

    MangaInfo {
        source_id: ID,
        title,
        author,
        genre,
        status: status.flatten(),
        description,
        path: slug.to_string(),
        cover_url,
    }
}

fn normalize_status(value: &str) -> Option<String> {
    let lower = value.to_ascii_lowercase();
    if lower.contains("ongoing") {
        Some("Ongoing".to_string())
    } else if lower.contains("completed") {
        Some("Completed".to_string())
    } else if lower.contains("dropped") || lower.contains("canceled") || lower.contains("cancelled") {
        Some("Cancelled".to_string())
    } else if lower.contains("on hold") || lower.contains("hiatus") {
        Some("Hiatus".to_string())
    } else if value.trim().is_empty() {
        None
    } else {
        Some(value.trim().to_string())
    }
}

/// Parse the chapter list, newest first, deduplicated by chapter path.
pub fn chapters(document: &Html, slug: &str) -> Vec<ChapterInfo> {
    let mut out: Vec<ChapterInfo> = Vec::new();
    let mut seen: Vec<String> = Vec::new();

    for link in document.select(&selector("li.wp-manga-chapter > a, .chapter-item .chapter > a")) {
        let Some(href) = attr(&link, "href") else {
            continue;
        };
        let path = href
            .trim_start_matches(URL)
            .trim_matches('/')
            .to_string();

        // Skip navigation pseudo-entries and duplicates.
        if path.is_empty() || seen.contains(&path) || href.contains("/chapter-0/") && seen.is_empty() {
            continue;
        }
        seen.push(path.clone());

        let raw_title = text_of(&link);
        let number = chapter_number(&raw_title).unwrap_or(-1.0);
        out.push(ChapterInfo {
            source_id: ID,
            title: if raw_title.is_empty() {
                format!("Chapter {number}")
            } else {
                raw_title
            },
            path,
            number,
            scanlator: None,
            uploaded: 0,
        });
    }

    let _ = slug;
    out
}

/// Extract page image URLs from a chapter page.
pub fn chapter_pages(document: &Html, _chapter_url: &str) -> anyhow::Result<Vec<String>> {
    let mut out: Vec<String> = Vec::new();

    for img in document.select(&selector(
        "div.reading-content div.page-break img, div.reading-content img",
    )) {
        if let Some(src) = image_src(&img) {
            let src = absolute(&src);
            if !out.contains(&src) {
                out.push(src);
            }
        }
    }

    Ok(out)
}

/// Pull a chapter number out of a label such as `Chapter 12.5` or `Vol. 2 Ch. 7`.
pub fn chapter_number(label: &str) -> Option<f64> {
    let mut best: Option<f64> = None;
    let mut current = String::new();

    for ch in label.chars() {
        if ch.is_ascii_digit() || (ch == '.' && !current.is_empty() && !current.contains('.')) {
            current.push(ch);
        } else {
            if let Some(value) = current.parse::<f64>().ok() {
                // Prefer the number that follows a chapter marker.
                best = Some(value);
            }
            current.clear();
        }
    }
    if let Some(value) = current.parse::<f64>().ok() {
        best = Some(value);
    }
    best
}

/// Value of a Madara `post-content_item` row whose heading matches `label`.
fn find_summary_content(document: &Html, label: &str) -> Option<String> {
    for item in document.select(&selector("div.post-content_item")) {
        let heading = item
            .select(&selector(".summary-heading, h5"))
            .next()
            .map(|element| text_of(&element).to_ascii_lowercase())
            .unwrap_or_default();

        if heading.contains(label) {
            if let Some(value) = item.select(&selector(".summary-content")).next() {
                let text = text_of(&value);
                if !text.is_empty() {
                    return Some(text);
                }
            }
        }
    }
    None
}
