//! Copies the kept files of the translation's data folders into the output, Renewal first, then each compatibility
//! date over it (the date's `Renewal/` when it has one), as ClientGenerator.bat stages a 2022-04-06 client.

use crate::ImportError;
use crate::select::{COMPATIBILITY, RENEWAL_DATA, keep};
use std::fs;
use std::path::{Component, Path, PathBuf};

/// How many files were copied from `checkout` into `out`
pub fn copy_translation(checkout: &Path, out: &Path) -> Result<usize, ImportError> {
    let mut copied = copy_data(&checkout.join(RENEWAL_DATA), out)?;
    for date in COMPATIBILITY {
        let dated = checkout.join("Translation/Compatibility").join(date);
        let base = if dated.join("Renewal").is_dir() {
            dated.join("Renewal")
        } else {
            dated
        };
        let data = base.join("data");
        if data.is_dir() {
            copied += copy_data(&data, out)?;
        }
    }
    Ok(copied)
}

/// The kept files of one data folder; a symlink anywhere in it is refused (a checkout never has one)
fn copy_data(source: &Path, out: &Path) -> Result<usize, ImportError> {
    let mut files = Vec::new();
    walk(source, &mut files)?;
    files.sort();
    let mut copied = 0;
    for path in files {
        let rel = path.strip_prefix(source).expect("walked under source");
        let parts: Vec<String> = rel
            .components()
            .map(|c| c.as_os_str().to_string_lossy().into_owned())
            .collect();
        let parts: Vec<&str> = parts.iter().map(String::as_str).collect();
        if !keep(&parts) {
            continue;
        }
        if !rel.components().all(|c| matches!(c, Component::Normal(_))) {
            return Err(ImportError::OutsideOutput(rel.to_path_buf()));
        }
        // Korean names stay spelled as the repo has them (CP949 bytes read as Latin-1, in UTF-8): Android's shared
        // storage only takes UTF-8 names, grf-server turns them back into the GRF's bytes
        let dest = out.join(rel);
        if let Some(parent) = dest.parent() {
            fs::create_dir_all(parent).map_err(|e| ImportError::io(parent, e))?;
        }
        fs::copy(&path, &dest).map_err(|e| ImportError::io(&path, e))?;
        copied += 1;
    }
    Ok(copied)
}

fn walk(dir: &Path, files: &mut Vec<PathBuf>) -> Result<(), ImportError> {
    for entry in fs::read_dir(dir).map_err(|e| ImportError::io(dir, e))? {
        let path = entry.map_err(|e| ImportError::io(dir, e))?.path();
        let kind = fs::symlink_metadata(&path)
            .map_err(|e| ImportError::io(&path, e))?
            .file_type();
        if kind.is_symlink() {
            return Err(ImportError::Symlink(path));
        }
        if kind.is_dir() {
            walk(&path, files)?;
        } else if kind.is_file() {
            files.push(path);
        }
    }
    Ok(())
}
