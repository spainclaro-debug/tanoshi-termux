//! HTML scraping for HentaiNexus.
//!
//! Selectors follow the live markup: gallery cards use `.container .column`
//! with a `.card-header-title`, and detail rows are
//! `<td class="viewcolumn">Label</td><td>value</td>` pairs.
//!
//! `scraper` has no `:contains()` pseudo-class (that is a Jsoup extension), so
//! table rows are matched by comparing the label text.

use anyhow::Result;
use scraper::{ElementRef, Html, Selector};
use tanoshi_lib::prelude::{ChapterInfo, MangaInfo};

use crate::{ID, NAME, URL};

fn sel(value: &str) -> Selector {
    Selector::parse(value).expect("static selector")
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

fn absolute(url: &str) -> String {
    let url = url.trim();
    if url.is_empty() {
        return String::new();
    }
    if url.starts_with("http://") || url.starts_with("https://") {
        url.to_string()
    } else if let Some(rest) = url.strip_prefix("//") {
        format!("https://{rest}")
    } else {
        format!("{URL}/{}", url.trim_start_matches('/'))
    }
}

/// Strip a trailing count such as `(5,665)` from a tag or link label.
fn strip_count(text: &str) -> String {
    let mut out = text.trim();
    if out.ends_with(')')
        && let Some(open) = out.rfind('(')
    {
        let inside = &out[open + 1..out.len() - 1];
        if !inside.is_empty() && inside.chars().all(|c| c.is_ascii_digit() || c == ',' || c == ' ')
        {
            out = out[..open].trim();
        }
    }
    out.to_string()
}

/// Extract the gallery id from a path or URL.
///
/// Accepts `22403`, `view/22403`, `/view/22403` and `read/22403`.
pub fn gallery_id(path: &str) -> String {
    let trimmed = path.trim().trim_end_matches('/');
    let without_scheme = trimmed
        .trim_start_matches("https://")
        .trim_start_matches("http://");
    let parts: Vec<&str> = without_scheme
        .split('/')
        .filter(|part| !part.is_empty())
        .collect();

    // Drop the host when a full URL was stored.
    let parts: Vec<&str> = if parts.first().is_some_and(|first| first.contains('.')) {
        parts[1..].to_vec()
    } else {
        parts
    };

    match parts.as_slice() {
        [kind, id] if *kind == "view" || *kind == "read" => (*id).to_string(),
        [id] => (*id).to_string(),
        _ => parts.last().copied().unwrap_or_default().to_string(),
    }
}

/// Parse a gallery listing page ([`get_popular_manga`](crate::HentaiNexus) etc).
pub fn listing(html: &str) -> Vec<MangaInfo> {
    let document = Html::parse_document(html);
    let mut out: Vec<MangaInfo> = Vec::new();

    for column in document.select(&sel(".container .column")) {
        // Gallery cards always carry a title; other columns (navigation) do not.
        let Some(title_element) = column.select(&sel(".card-header-title")).next() else {
            continue;
        };
        let title = text_of(&title_element);
        if title.is_empty() {
            continue;
        }

        let Some(link) = column.select(&sel("a")).next() else {
            continue;
        };
        let Some(href) = attr(&link, "href") else {
            continue;
        };
        let id = gallery_id(&href);
        if id.is_empty() || id == "random" || out.iter().any(|m| m.path == id) {
            continue;
        }

        let cover_url = column
            .select(&sel(".card-image img"))
            .next()
            .and_then(|img| attr(&img, "src").or_else(|| attr(&img, "data-src")))
            .map(|src| absolute(&src))
            .unwrap_or_default();

        out.push(MangaInfo {
            source_id: ID,
            title,
            author: Vec::new(),
            genre: Vec::new(),
            status: Some("Completed".to_string()),
            description: None,
            path: id,
            cover_url,
        });
    }

    out
}

/// Find the value cell that follows the `label` row inside `.view-page-details`.
fn row_value(document: &Html, label: &str) -> Option<String> {
    for cell in document.select(&sel("td.viewcolumn")) {
        if text_of(&cell).eq_ignore_ascii_case(label) {
            let value = cell.next_siblings().find_map(|node| {
                let element = ElementRef::wrap(node)?;
                (element.value().name() == "td").then_some(element)
            })?;
            let text = text_of(&value);
            if !text.is_empty() {
                return Some(text);
            }
            // Fall back to the first link when the cell only holds markup.
            return value.select(&sel("a")).next().map(|a| text_of(&a));
        }
    }
    None
}

/// Collect the link labels from the row named `label` (artists, circles, tags).
fn row_links(document: &Html, label: &str) -> Vec<String> {
    for cell in document.select(&sel("td.viewcolumn")) {
        if !text_of(&cell).eq_ignore_ascii_case(label) {
            continue;
        }
        let Some(value) = cell.next_siblings().find_map(|node| {
            let element = ElementRef::wrap(node)?;
            (element.value().name() == "td").then_some(element)
        }) else {
            return Vec::new();
        };

        let mut out: Vec<String> = Vec::new();
        for link in value.select(&sel("a")) {
            let name = strip_count(&text_of(&link));
            if !name.is_empty() && !out.contains(&name) {
                out.push(name);
            }
        }
        if out.is_empty() {
            let name = strip_count(&text_of(&value));
            if !name.is_empty() {
                out.push(name);
            }
        }
        return out;
    }
    Vec::new()
}

/// Parse a gallery detail page.
pub fn detail(html: &str, id: &str) -> Result<MangaInfo> {
    let document = Html::parse_document(html);

    let title = document
        .select(&sel("h1.title"))
        .next()
        .map(|element| text_of(&element))
        .filter(|title| !title.is_empty())
        .unwrap_or_else(|| format!("Gallery {id}"));

    let cover_url = document
        .select(&sel("figure.image img"))
        .next()
        .and_then(|img| attr(&img, "src").or_else(|| attr(&img, "data-src")))
        .map(|src| absolute(&src))
        .unwrap_or_default();

    // Authors: Author first, then Circle, then Artist (matching upstream).
    let mut author: Vec<String> = Vec::new();
    for label in ["Author", "Circle", "Artist"] {
        for name in row_links(&document, label) {
            if !author.contains(&name) {
                author.push(name);
            }
        }
    }

    // Tags live in `span.tag a`; the text carries a gallery count.
    let mut genre: Vec<String> = Vec::new();
    for tag in document.select(&sel("span.tag a")) {
        let name = strip_count(&text_of(&tag));
        if !name.is_empty() && !genre.contains(&name) {
            genre.push(name);
        }
    }

    let mut description = String::new();
    for label in ["Circle", "Event", "Magazine", "Parody", "Publisher", "Pages", "Favorites"] {
        if let Some(value) = row_value(&document, label) {
            // These rows link to a filtered list and carry the item count in a
            // child span, which upstream omits; drop it.
            description.push_str(&format!("{label}: {}\n", strip_count(&value)));
        }
    }
    if let Some(text) = row_value(&document, "Description") {
        description.push('\n');
        description.push_str(&text);
    }

    log::debug!("{NAME}: decoded gallery {id}");

    Ok(MangaInfo {
        source_id: ID,
        title,
        author,
        genre,
        status: Some("Completed".to_string()),
        description: {
            let trimmed = description.trim();
            (!trimmed.is_empty()).then(|| trimmed.to_string())
        },
        path: id.to_string(),
        cover_url,
    })
}

/// Parse the single chapter a gallery provides.
///
/// Upstream reports one chapter per gallery; the reader URL is `/read/<id>` and
/// the upload date comes from the `Published` row.
pub fn chapters(html: &str, id: &str) -> Vec<ChapterInfo> {
    let document = Html::parse_document(html);

    let uploaded = row_value(&document, "Published")
        .map(|value| parse_published(&value))
        .unwrap_or(0);

    vec![ChapterInfo {
        source_id: ID,
        title: "Chapter".to_string(),
        path: format!("read/{id}"),
        number: 1.0,
        scanlator: None,
        uploaded,
    }]
}

/// `Published` is formatted as `d MMMM yyyy` (e.g. `3 March 2021`).
fn parse_published(value: &str) -> i64 {
    let cleaned = value.trim();
    if let Ok(date) = chrono::NaiveDate::parse_from_str(cleaned, "%e %B %Y") {
        return date.and_hms_opt(0, 0, 0).map(|dt| dt.and_utc().timestamp()).unwrap_or(0);
    }
    if let Ok(date) = chrono::NaiveDate::parse_from_str(cleaned, "%d %B %Y") {
        return date.and_hms_opt(0, 0, 0).map(|dt| dt.and_utc().timestamp()).unwrap_or(0);
    }
    0
}

/// Extract the base64 argument of `initReader("...")` from the reader page.
pub fn reader_payload(html: &str) -> Option<String> {
    let document = Html::parse_document(html);

    // The payload sits in an inline <script>; scan script bodies for the call.
    for script in document.select(&sel("script")) {
        let body = script.inner_html();
        if let Some(start) = body.find("initReader(\"") {
            let rest = &body[start + "initReader(\"".len()..];
            if let Some(end) = rest.find('"') {
                let payload = &rest[..end];
                if !payload.is_empty() {
                    return Some(payload.to_string());
                }
            }
        }
    }

    // Fallback: match against the raw source in case the markup is unusual.
    let start = html.find("initReader(\"")? + "initReader(\"".len();
    let rest = &html[start..];
    let end = rest.find('"')?;
    Some(rest[..end].to_string())
}
