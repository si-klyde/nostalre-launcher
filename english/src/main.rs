//! nostalre-english import <ROenglishRE checkout> <rAthena db/re> <out data dir>
//! nostalre-english needed <GitHub tree listing JSON>   (prints the checkout paths the import reads)

use serde::Deserialize;
use std::path::Path;
use std::process::ExitCode;

const USAGE: &str = "usage: nostalre-english import <ROenglishRE checkout> <rAthena db/re> <out dir>
       nostalre-english needed <tree.json>
  <out dir>: a dedicated folder, replaced whole by each import (never the game's data/ folder)
  <checkout>: a full checkout, or the files `needed` lists (a sparse clone misses compatibility art)";

#[derive(Deserialize)]
struct Tree {
    #[serde(default)]
    truncated: bool,
    tree: Vec<TreeEntry>,
}

#[derive(Deserialize)]
struct TreeEntry {
    path: String,
    #[serde(rename = "type")]
    kind: String,
}

fn needed(listing: &Path) -> Result<String, String> {
    let text =
        std::fs::read_to_string(listing).map_err(|e| format!("{}: {e}", listing.display()))?;
    let tree: Tree =
        serde_json::from_str(&text).map_err(|e| format!("{}: {e}", listing.display()))?;
    if tree.truncated {
        return Err(format!(
            "{}: tree listing truncated, cannot tell every file",
            listing.display()
        ));
    }
    let blobs: Vec<&str> = tree
        .tree
        .iter()
        .filter(|e| e.kind == "blob")
        .map(|e| e.path.as_str())
        .collect();
    Ok(nostalre_english::select::needed_in_listing(&blobs)
        .iter()
        .map(|p| format!("{p}\n"))
        .collect())
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let args: Vec<&str> = args.iter().map(String::as_str).collect();
    let result = match args.as_slice() {
        ["import", checkout, db, out] => {
            nostalre_english::import(Path::new(checkout), Path::new(db), Path::new(out))
                .map(|r| {
                    format!(
                        "copied {} files, {} items, {} skills into {out}\n",
                        r.copied, r.items, r.skills
                    )
                })
                .map_err(|e| e.to_string())
        }
        ["needed", listing] => needed(Path::new(listing)),
        _ => Err(USAGE.to_string()),
    };
    match result {
        Ok(text) => {
            print!("{text}");
            ExitCode::SUCCESS
        }
        Err(message) => {
            eprintln!("nostalre-english: {message}");
            ExitCode::FAILURE
        }
    }
}
