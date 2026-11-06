//! Turns a ROenglishRE checkout (the English fan translation of kRO, downloaded by the player's app, never shipped)
//! into the loose `data/` files nostalRE and grf-server read over kRO's Korean ones: text tables, books, textures,
//! world map labels, item and skill tables, and `iteminfo_en.lub`.
//!
//! The output is all or nothing: it is built beside `out` and swapped in only when complete.

pub mod lua;
pub mod select;

mod copy;
mod items;
mod lub;
mod rathena;
mod skills;
mod text;

use std::fmt;
use std::fs;
use std::path::{Path, PathBuf};

/// What an import wrote
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Report {
    pub copied: usize,
    pub items: usize,
    pub skills: usize,
}

#[derive(Debug)]
pub enum ImportError {
    Missing(PathBuf),
    Io {
        path: PathBuf,
        source: std::io::Error,
    },
    Lua {
        path: PathBuf,
        source: lua::LuaError,
    },
    Yaml {
        path: PathBuf,
        message: String,
    },
    Compile {
        path: PathBuf,
        message: String,
    },
    Symlink(PathBuf),
    OutsideOutput(PathBuf),
    /// A non-empty folder without [`MARKER`]: never replaced, it holds files the tool did not write
    NotOwned(PathBuf),
}

impl ImportError {
    fn io(path: &Path, source: std::io::Error) -> Self {
        ImportError::Io {
            path: path.to_path_buf(),
            source,
        }
    }

    fn lua(path: &Path, source: lua::LuaError) -> Self {
        ImportError::Lua {
            path: path.to_path_buf(),
            source,
        }
    }
}

impl fmt::Display for ImportError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ImportError::Missing(path) => write!(f, "missing {}", path.display()),
            ImportError::Io { path, source } => write!(f, "{}: {source}", path.display()),
            ImportError::Lua { path, source } => write!(f, "{}: {source}", path.display()),
            ImportError::Yaml { path, message } => write!(f, "{}: {message}", path.display()),
            ImportError::Compile { path, message } => write!(f, "{}: {message}", path.display()),
            ImportError::Symlink(path) => write!(f, "refusing symlink {}", path.display()),
            ImportError::OutsideOutput(path) => {
                write!(f, "refusing path outside the output: {}", path.display())
            }
            ImportError::NotOwned(path) => write!(
                f,
                "{} holds files and no {MARKER}: refusing to replace a folder this tool did not write \
                 (give it a dedicated folder)",
                path.display()
            ),
        }
    }
}

impl std::error::Error for ImportError {}

/// An input file's bytes; a missing one is [`ImportError::Missing`]
fn read_input(path: &Path) -> Result<Vec<u8>, ImportError> {
    if !path.is_file() {
        return Err(ImportError::Missing(path.to_path_buf()));
    }
    fs::read(path).map_err(|e| ImportError::io(path, e))
}

fn write(path: &Path, data: &[u8]) -> Result<(), ImportError> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| ImportError::io(parent, e))?;
    }
    fs::write(path, data).map_err(|e| ImportError::io(path, e))
}

/// The file that marks a folder as this tool's output: only such folders are ever replaced or removed
pub const MARKER: &str = ".nostalre-english";

fn owned(dir: &Path) -> bool {
    dir.join(MARKER).is_file()
}

/// Whether `dir` may be replaced: missing, empty, or marked as ours
fn replaceable(dir: &Path) -> Result<bool, ImportError> {
    match fs::read_dir(dir) {
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(true),
        Err(e) => Err(ImportError::io(dir, e)),
        Ok(mut entries) => Ok(entries.next().is_none() || owned(dir)),
    }
}

/// `.<name>.nostalre-english-<what>` beside `out`: a name no folder of the player's collides with
fn sibling(out: &Path, what: &str) -> PathBuf {
    let name = out
        .file_name()
        .map_or_else(|| "data".into(), |n| n.to_string_lossy().into_owned());
    out.with_file_name(format!(".{name}.nostalre-english-{what}"))
}

fn remove_dir(path: &Path) -> Result<(), ImportError> {
    match fs::remove_dir_all(path) {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(ImportError::io(path, e)),
        _ => Ok(()),
    }
}

/// Imports `checkout` with rAthena's Renewal db `rathena_db` (`db/re`) into `out`, replacing it whole on success and
/// leaving it as it was on failure. `out` must be the tool's own folder: missing, empty, or an earlier import
/// (it holds [`MARKER`]); any other folder is refused untouched.
pub fn import(checkout: &Path, rathena_db: &Path, out: &Path) -> Result<Report, ImportError> {
    let (tmp, old) = (sibling(out, "tmp"), sibling(out, "old"));
    recover(out, &old)?;
    if !replaceable(out)? {
        return Err(ImportError::NotOwned(out.to_path_buf()));
    }
    if !replaceable(&tmp)? {
        return Err(ImportError::NotOwned(tmp));
    }
    remove_dir(&tmp)?;
    fs::create_dir_all(&tmp).map_err(|e| ImportError::io(&tmp, e))?;
    write(&tmp.join(MARKER), b"")?;
    let report = match build(checkout, rathena_db, &tmp) {
        Ok(report) => report,
        Err(e) => {
            let _ = fs::remove_dir_all(&tmp);
            return Err(e);
        }
    };
    swap(&tmp, out, &old, |from, to| fs::rename(from, to))?;
    Ok(report)
}

/// A previous run stopped mid-swap left the output only in `old`: it goes back to `out`. Otherwise an `old` of
/// ours is a finished swap's leftover, dropped. A folder there without [`MARKER`] is not ours and is left alone.
fn recover(out: &Path, old: &Path) -> Result<(), ImportError> {
    if !owned(old) {
        return Ok(());
    }
    if !out.exists() {
        return fs::rename(old, out).map_err(|e| ImportError::io(old, e));
    }
    remove_dir(old)
}

/// Moves `out` aside to `old`, `tmp` in as `out`, then drops `old`; when `tmp` cannot move in, `old` goes back
fn swap(
    tmp: &Path,
    out: &Path,
    old: &Path,
    rename: impl Fn(&Path, &Path) -> std::io::Result<()>,
) -> Result<(), ImportError> {
    let had_output = out.exists();
    if had_output {
        rename(out, old).map_err(|e| ImportError::io(out, e))?;
    }
    if let Err(e) = rename(tmp, out) {
        if had_output {
            let _ = rename(old, out);
        }
        let _ = fs::remove_dir_all(tmp);
        return Err(ImportError::io(tmp, e));
    }
    if had_output { remove_dir(old) } else { Ok(()) }
}

/// Writes everything into `out`, in the old script's order: the copied files first, so the generated tables
/// replace any copied table of the same name
fn build(checkout: &Path, rathena_db: &Path, out: &Path) -> Result<Report, ImportError> {
    use select::{PRE_RENEWAL_ITEM_INFO, RENEWAL_ITEM_INFO};
    let renewal_path = checkout.join(RENEWAL_ITEM_INFO);
    let pre_renewal_path = checkout.join(PRE_RENEWAL_ITEM_INFO);
    let renewal_items = read_input(&renewal_path)?;
    let pre_renewal_items = read_input(&pre_renewal_path)?;
    let server_skills = rathena::skills(rathena_db)?;

    let copied = copy::copy_translation(checkout, out)?;
    let items = items::write_item_tables(
        (&renewal_path, &renewal_items),
        (&pre_renewal_path, &pre_renewal_items),
        &rathena::item_names(rathena_db)?,
        out,
    )?;

    let views = rathena::view_tables(rathena_db)?;
    for (file, table) in [
        ("shieldviewtable.txt", &views.shields),
        ("headgearviewtable.txt", &views.headgear),
    ] {
        let data: String = table
            .iter()
            .map(|(id, view)| format!("{id}#{view}#\n"))
            .collect();
        write(&out.join(file), data.as_bytes())?;
    }
    let cards: String = views.cards.iter().map(|id| format!("{id}#\n")).collect();
    write(&out.join("rathenacardtable.txt"), cards.as_bytes())?;

    let chunk = lub::compile(&renewal_items, &renewal_path)?;
    write(
        &out.join("luafiles514/lua files/datainfo/iteminfo_en.lub"),
        &chunk,
    )?;

    let skills = skills::write_skill_tables(checkout, &server_skills, out)?;
    Ok(Report {
        copied,
        items,
        skills,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn failed_move_in_puts_the_old_output_back() {
        let dir = tempfile::tempdir().unwrap();
        let (tmp, out, old) = (
            dir.path().join(".data.nostalre-english-tmp"),
            dir.path().join("data"),
            dir.path().join(".data.nostalre-english-old"),
        );
        for (path, file) in [(&tmp, "new.txt"), (&out, "old.txt")] {
            fs::create_dir_all(path).unwrap();
            fs::write(path.join(file), "x").unwrap();
        }
        let failing = |from: &Path, to: &Path| {
            if from == tmp.as_path() {
                Err(std::io::Error::other("disk gone"))
            } else {
                fs::rename(from, to)
            }
        };
        assert!(swap(&tmp, &out, &old, failing).is_err());
        assert!(out.join("old.txt").exists());
        assert!(!old.exists());
        assert!(!tmp.exists());
    }
}
