//! The skill tables the client reads, from the translation's skillinfoz lubs (Lua source despite the name):
//! - skillnametable.txt (`code#Name#`): Renewal SkillName, else the Pre-Renewal table's, else rAthena's description
//!   (the server's own sub-skills, `DK_SERVANTWEAPON_ATK`, have no name in kRO's tables)
//! - skilldesctable.txt (Pre-Renewal, for roBrowser) and skilldesctable_renewal.txt (Renewal over Pre-Renewal)
//! - leveluseskillspamount.txt: the skills kRO lets pick a level (`bSeperateLv`), with the SP rAthena charges

use crate::lua::{Key, Lua, parse_table};
use crate::rathena::Skill;
use crate::select::{
    PRE_RENEWAL_SKILL_DESCRIPTIONS, PRE_RENEWAL_SKILL_NAMES, RENEWAL_SKILL_DESCRIPTIONS,
    RENEWAL_SKILL_LIST,
};
use crate::text::replace;
use crate::{ImportError, read_input, write};
use indexmap::IndexMap;
use std::collections::BTreeMap;
use std::path::Path;

/// Skill code -> its description lines, in the file's order
fn descriptions(path: &Path) -> Result<IndexMap<String, Vec<Vec<u8>>>, ImportError> {
    Ok(entries(path, "SKILL_DESCRIPT")?
        .into_iter()
        .map(|(code, lines)| (code, lines.lines()))
        .collect())
}

/// The named table's entries keyed by skill code, in the file's order
fn entries(path: &Path, table: &str) -> Result<IndexMap<String, Lua>, ImportError> {
    let src = read_input(path)?;
    let Lua::Map(map) = parse_table(&src, table).map_err(|e| ImportError::lua(path, e))? else {
        return Ok(IndexMap::new());
    };
    Ok(map
        .into_iter()
        .filter_map(|(key, value)| match key {
            Key::Name(code) => Some((code, value)),
            Key::Str(code) => Some((String::from_utf8_lossy(&code).into_owned(), value)),
            Key::Int(_) => None,
        })
        .collect())
}

fn write_descriptions(
    out: &Path,
    file: &str,
    skills: &IndexMap<String, Vec<Vec<u8>>>,
) -> Result<(), ImportError> {
    let mut data = Vec::new();
    for (code, lines) in skills {
        data.extend_from_slice(code.as_bytes());
        data.extend_from_slice(b"#\n");
        let body: Vec<Vec<u8>> = lines.iter().map(|l| replace(l, b'#', b'_')).collect();
        data.extend(body.join(&b'\n'));
        data.extend_from_slice(b"\n#\n");
    }
    write(&out.join(file), &data)
}

/// Writes the four skill tables; how many skills have a name
pub fn write_skill_tables(
    checkout: &Path,
    server: &BTreeMap<String, Skill>,
    out: &Path,
) -> Result<usize, ImportError> {
    let classic = descriptions(&checkout.join(PRE_RENEWAL_SKILL_DESCRIPTIONS))?;
    let info = entries(&checkout.join(RENEWAL_SKILL_LIST), "SKILL_INFO_LIST")?;
    let renewal_descriptions = descriptions(&checkout.join(RENEWAL_SKILL_DESCRIPTIONS))?;

    write_descriptions(out, "skilldesctable.txt", &classic)?;
    let mut renewal = classic;
    renewal.extend(renewal_descriptions);
    write_descriptions(out, "skilldesctable_renewal.txt", &renewal)?;

    let mut names: BTreeMap<String, Vec<u8>> = server
        .iter()
        .map(|(code, s)| (code.clone(), s.name.clone()))
        .collect();
    let old_names = checkout.join(PRE_RENEWAL_SKILL_NAMES);
    if old_names.is_file() {
        for line in splitlines(&read_input(&old_names)?) {
            let mut parts = line.split(|&b| b == b'#');
            let (Some(code), Some(name)) = (parts.next(), parts.next()) else {
                continue;
            };
            let (code, name) = (strip(code), strip(name));
            if !code.is_empty() && !name.is_empty() {
                names.insert(
                    String::from_utf8_lossy(code).into_owned(),
                    replace(name, b'_', b' '),
                );
            }
        }
    }
    for (code, entry) in &info {
        if let Some(name) = entry
            .get("SkillName")
            .filter(|n| n.truthy())
            .and_then(Lua::as_bytes)
        {
            names.insert(code.clone(), name.to_vec());
        }
    }
    let mut data = Vec::new();
    for (code, name) in &names {
        data.extend_from_slice(code.as_bytes());
        data.push(b'#');
        data.extend(replace(&replace(name, b'#', b'_'), b' ', b'_'));
        data.extend_from_slice(b"#\n");
    }
    write(&out.join("skillnametable.txt"), &data)?;

    let mut data = Vec::new();
    for (code, entry) in &info {
        if !entry.get("bSeperateLv").is_some_and(Lua::truthy) {
            continue;
        }
        let sp: Vec<i64> = match server.get(code) {
            Some(skill) => skill.sp.clone(),
            None => entry.get("SpAmount").map_or_else(Vec::new, |l| {
                l.items()
                    .iter()
                    .filter_map(|v| if let Lua::Int(n) = v { Some(*n) } else { None })
                    .collect()
            }),
        };
        data.extend_from_slice(code.as_bytes());
        data.extend_from_slice(b"#\n");
        for amount in sp {
            data.extend_from_slice(format!("{amount}#\n").as_bytes());
        }
        data.extend_from_slice(b"@\n");
    }
    write(&out.join("leveluseskillspamount.txt"), &data)?;
    Ok(names.len())
}

/// `bytes` without Python's `bytes.strip()` whitespace around it: ASCII whitespace and `\x0b`
fn strip(bytes: &[u8]) -> &[u8] {
    let space = |b: &u8| b.is_ascii_whitespace() || *b == 0x0b;
    let start = bytes.iter().position(|b| !space(b)).unwrap_or(bytes.len());
    let end = bytes
        .iter()
        .rposition(|b| !space(b))
        .map_or(start, |i| i + 1);
    &bytes[start..end]
}

/// Lines split on `\n`, `\r\n` or a lone `\r`, as Python's `bytes.splitlines()`
fn splitlines(data: &[u8]) -> Vec<&[u8]> {
    let mut lines = Vec::new();
    let mut start = 0;
    let mut i = 0;
    while i < data.len() {
        if data[i] == b'\n' || data[i] == b'\r' {
            lines.push(&data[start..i]);
            if data[i] == b'\r' && data.get(i + 1) == Some(&b'\n') {
                i += 1;
            }
            start = i + 1;
        }
        i += 1;
    }
    if start < data.len() {
        lines.push(&data[start..]);
    }
    lines
}
