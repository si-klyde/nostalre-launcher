//! Which files of a ROenglishRE checkout the import reads: the one list both the download and the copy use.

use std::path::Path;

/// ClientGenerator.bat's dated compatibility folders for a 2022-04-06 client, in its order (it skips 2020-01-22)
pub const COMPATIBILITY: [&str; 8] = [
    "2017-06-14",
    "2017-12-13",
    "2018-06-20",
    "2019-06-05",
    "2020-09-02",
    "2021-10-28",
    "2022-03-30",
    "2022-04-06",
];

pub const RENEWAL_DATA: &str = "Translation/Renewal/data";
pub const RENEWAL_ITEM_INFO: &str = "Translation/Renewal/SystemEN/LuaFiles514/itemInfo.lua";
pub const PRE_RENEWAL_ITEM_INFO: &str = "Translation/Pre-Renewal/SystemEN/LuaFiles514/itemInfo.lua";
pub const PRE_RENEWAL_SKILL_DESCRIPTIONS: &str =
    "Translation/Pre-Renewal/data/lua files/skillinfoz/skilldescript.lub";
pub const PRE_RENEWAL_SKILL_NAMES: &str = "Translation/Pre-Renewal/data/skillnametable.txt";
pub const RENEWAL_SKILL_LIST: &str =
    "Translation/Renewal/data/luafiles514/lua files/skillinfoz/skillinfolist.lub";
pub const RENEWAL_SKILL_DESCRIPTIONS: &str =
    "Translation/Renewal/data/luafiles514/lua files/skillinfoz/skilldescript.lub";

const INPUTS: [&str; 6] = [
    RENEWAL_ITEM_INFO,
    PRE_RENEWAL_ITEM_INFO,
    PRE_RENEWAL_SKILL_DESCRIPTIONS,
    PRE_RENEWAL_SKILL_NAMES,
    RENEWAL_SKILL_LIST,
    RENEWAL_SKILL_DESCRIPTIONS,
];

const KEEP_DIRS: [&str; 2] = ["texture", "book"];
const KEEP_EXT: [&str; 8] = ["txt", "xml", "bmp", "tga", "jpg", "png", "spr", "act"];

/// Whether the import reads the checkout file at `path` (`/`-separated, relative): its inputs, and the files
/// [`keep`] takes from the Renewal data folder and each compatibility one. Paths that could leave the checkout are not.
pub fn needed(path: &str) -> bool {
    if !safe_parts(path) {
        return false;
    }
    let parts: Vec<&str> = path.split('/').collect();
    if INPUTS.contains(&path) {
        return true;
    }
    data_roots().any(|root| {
        let root: Vec<&str> = root.split('/').collect();
        parts.len() > root.len() && parts[..root.len()] == root[..] && keep(&parts[root.len()..])
    })
}

/// A relative `/`-separated path that cannot leave the checkout: no `\\`, no empty, `.` or `..` part
fn safe_parts(path: &str) -> bool {
    !path.contains('\\')
        && !path
            .split('/')
            .any(|part| part.is_empty() || part == "." || part == "..")
}

/// The files of a full checkout listing (`blobs`, in listing order) to download, as the import takes them: the
/// [`needed`] ones, but for a date with a `Renewal/` folder none of its plain `data/` (the import reads that date's
/// `Renewal/data` instead); plus one file of each such `Renewal/` none of the others is in, so the folder exists in
/// the download as it does in the full checkout.
pub fn needed_in_listing<'a>(blobs: &[&'a str]) -> Vec<&'a str> {
    let safe: Vec<&str> = blobs.iter().copied().filter(|p| safe_parts(p)).collect();
    let renewal_dates: Vec<&str> = COMPATIBILITY
        .iter()
        .copied()
        .filter(|date| {
            let renewal = format!("Translation/Compatibility/{date}/Renewal/");
            safe.iter().any(|p| p.starts_with(&renewal))
        })
        .collect();
    let unread = |p: &str| {
        renewal_dates
            .iter()
            .any(|date| p.starts_with(&format!("Translation/Compatibility/{date}/data/")))
    };
    let mut wanted: Vec<&str> = safe
        .iter()
        .copied()
        .filter(|p| needed(p) && !unread(p))
        .collect();
    for date in renewal_dates {
        let renewal = format!("Translation/Compatibility/{date}/Renewal/");
        if !wanted.iter().any(|p| p.starts_with(&renewal))
            && let Some(marker) = safe.iter().find(|p| p.starts_with(&renewal))
        {
            wanted.push(marker);
        }
    }
    wanted
}

/// The data folders copied, in copy order (later ones win)
pub fn data_roots() -> impl Iterator<Item = String> {
    std::iter::once(RENEWAL_DATA.to_string()).chain(COMPATIBILITY.iter().flat_map(|date| {
        [
            format!("Translation/Compatibility/{date}/data"),
            format!("Translation/Compatibility/{date}/Renewal/data"),
        ]
    }))
}

/// Whether a file at `rel` (its path parts) inside a translation data folder is taken: top-level text tables, the
/// world map labels (roBrowser loads them through its Lua engine) and the textures and books
pub fn keep(rel: &[&str]) -> bool {
    let Some(last) = rel.last() else { return false };
    let ext = Path::new(last)
        .extension()
        .map(|e| e.to_string_lossy().to_ascii_lowercase());
    let ext = ext.as_deref();
    let top_level_text = rel.len() == 1 && matches!(ext, Some("txt" | "xml"));
    let world_map = rel.len() >= 3
        && rel[..3].iter().map(|p| p.to_lowercase()).eq([
            "luafiles514",
            "lua files",
            "worldviewdata",
        ]);
    let kept = KEEP_DIRS.contains(&rel[0]) && ext.is_some_and(|e| KEEP_EXT.contains(&e));
    top_level_text || world_map || kept
}
