//! Search endpoint construction for Atsumaru.
//!
//! Ported from `getSearchMangaList`, which queries the Meilisearch-style
//! `collections/manga/documents/search` endpoint when a keyword is supplied and
//! otherwise falls back to a filter-only browse.
//!
//! Tanoshi exposes filters as generic `Input` values rather than the typed
//! Keiyoushi classes, so this port keeps the query-building contract (the
//! fixed filter clauses the site requires) and accepts the keyword. Typed
//! filter mapping can be layered on once the source declares `filter_list()`.

use anyhow::Result;

use crate::{BROWSE_MEDIUMS, BROWSE_TYPES, URL};

/// Path segment used by the browse fallback when no keyword is given.
const PER_PAGE: i64 = 40;

/// Build the search URL for `page`.
pub fn search_url(page: i64, query: &str) -> Result<String> {
    let page = page.max(1);
    let query = query.trim();

    if query.is_empty() {
        // Without a keyword the original still hits the browse listing.
        return Ok(format!(
            "{URL}/api/home2/recentlyUpdated?offset={}&limit={PER_PAGE}&types={BROWSE_TYPES}&mediums={BROWSE_MEDIUMS}",
            (page - 1) * PER_PAGE,
        ));
    }

    // Clauses the site requires for a well-formed browse: hide flagged entries,
    // keep the default content ratings, and exclude novels/listicles.
    let filter_by = [
        "hidden:!=true".to_string(),
        "isAdult:=false".to_string(),
        "(mbContentRating:=[`Safe`,`Suggestive`,`Erotica`] || mbContentRating:!=*)".to_string(),
        "medium:!=[`Novel`]".to_string(),
        "views:>0".to_string(),
    ]
    .join(" && ");

    Ok(format!(
        "{URL}/collections/manga/documents/search?q={}&filter_by={}&query_by=title,englishTitle,otherNames,authors&query_by_weights=4,3,2,1&num_typos=4,3,2,1&page={page}&per_page={PER_PAGE}",
        urlencoding::encode(query),
        urlencoding::encode(&filter_by),
    ))
}
