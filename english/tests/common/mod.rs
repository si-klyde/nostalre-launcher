//! Fixture paths and tree helpers shared by the integration tests.
#![allow(dead_code)]

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

pub fn fixtures() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures")
}

/// A fake ROenglishRE checkout: a few items, skills, textures and compatibility folders.
pub fn checkout() -> PathBuf {
    fixtures().join("checkout")
}

/// A fake rAthena `db/re`: item_db_*.yml and skill_db.yml.
pub fn rathena() -> PathBuf {
    fixtures().join("rathena")
}

/// What the old Python importer (scripts/import-english.py, removed) wrote for the fixture checkout (its `data/` folder).
pub fn expected() -> PathBuf {
    fixtures().join("expected")
}

/// The file marking a folder as the tool's own output; not part of the translation, so trees leave it out.
pub const OWNER_MARKER: &str = ".nostalre-english";

/// Every file under `root` but [`OWNER_MARKER`], by its `/`-joined relative path, with its bytes.
pub fn tree(root: &Path) -> BTreeMap<String, Vec<u8>> {
    let mut files = BTreeMap::new();
    walk(root, root, &mut files);
    files
}

fn walk(root: &Path, dir: &Path, files: &mut BTreeMap<String, Vec<u8>>) {
    for entry in fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        if path.is_dir() {
            walk(root, &path, files);
        } else {
            let rel = path.strip_prefix(root).unwrap();
            let key = rel
                .components()
                .map(|c| c.as_os_str().to_string_lossy())
                .collect::<Vec<_>>()
                .join("/");
            if key != OWNER_MARKER {
                files.insert(key, fs::read(&path).unwrap());
            }
        }
    }
}

/// Every file under `root` as relative `/`-joined paths.
pub fn paths(root: &Path) -> Vec<String> {
    tree(root).into_keys().collect()
}

/// Copies `from` into `to` (created), files only.
pub fn copy_dir(from: &Path, to: &Path) {
    for (rel, bytes) in tree(from) {
        let dest = to.join(&rel);
        fs::create_dir_all(dest.parent().unwrap()).unwrap();
        fs::write(dest, bytes).unwrap();
    }
}

/// Asserts two trees hold the same files with the same bytes, naming the first difference.
pub fn assert_same_tree(actual: &Path, expected: &Path) {
    let (a, e) = (tree(actual), tree(expected));
    let a_names: Vec<_> = a.keys().collect();
    let e_names: Vec<_> = e.keys().collect();
    assert_eq!(a_names, e_names, "file sets differ");
    for (name, bytes) in &e {
        assert!(
            a[name] == *bytes,
            "{name} differs:\n--- actual\n{}\n--- expected\n{}",
            String::from_utf8_lossy(&a[name]),
            String::from_utf8_lossy(bytes)
        );
    }
}

pub fn read(path: &Path) -> String {
    String::from_utf8(fs::read(path).unwrap()).unwrap()
}
