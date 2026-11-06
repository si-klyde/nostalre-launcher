//! The item text tables (`data/*itemdisplaynametable.txt`, `*itemdesctable.txt`) roBrowser and the client read, from
//! the translation's itemInfo.lua files.

use crate::lua::{Key, Lua, parse_table};
use crate::text::replace;
use crate::{ImportError, write};
use std::collections::BTreeMap;
use std::path::Path;

/// One item's texts
#[derive(Default)]
struct Item {
    identified_name: Vec<u8>,
    unidentified_name: Vec<u8>,
    identified_desc: Vec<Vec<u8>>,
    unidentified_desc: Vec<Vec<u8>>,
}

impl Item {
    fn from_lua(entry: &Lua) -> Self {
        let text = |key: &str| {
            entry
                .get(key)
                .and_then(Lua::as_bytes)
                .unwrap_or_default()
                .to_vec()
        };
        let lines = |key: &str| entry.get(key).map_or_else(Vec::new, Lua::lines);
        Item {
            identified_name: text("identifiedDisplayName"),
            unidentified_name: text("unidentifiedDisplayName"),
            identified_desc: lines("identifiedDescriptionName"),
            unidentified_desc: lines("unidentifiedDescriptionName"),
        }
    }
}

/// Item id -> entry of an itemInfo.lua's `tbl` (`src`, read from `path`)
fn read_item_info(path: &Path, src: &[u8]) -> Result<Vec<(i64, Lua)>, ImportError> {
    let tbl = parse_table(src, "tbl").map_err(|e| ImportError::lua(path, e))?;
    let Lua::Map(map) = tbl else {
        return Ok(Vec::new());
    };
    Ok(map
        .into_iter()
        .filter_map(|(k, v)| {
            if let Key::Int(id) = k {
                Some((id, v))
            } else {
                None
            }
        })
        .collect())
}

/// Writes the four tables: Renewal entries, overlaid whole by Pre-Renewal ones (the server is pre-renewal), then
/// rAthena's names for the items neither has
pub fn write_item_tables(
    renewal: (&Path, &[u8]),
    pre_renewal: (&Path, &[u8]),
    rathena_names: &BTreeMap<i64, Vec<u8>>,
    out: &Path,
) -> Result<usize, ImportError> {
    let mut items: BTreeMap<i64, Item> = BTreeMap::new();
    for (path, src) in [renewal, pre_renewal] {
        for (id, entry) in read_item_info(path, src)? {
            items.insert(id, Item::from_lua(&entry));
        }
    }
    for (id, name) in rathena_names {
        items.entry(*id).or_insert_with(|| Item {
            identified_name: name.clone(),
            unidentified_name: name.clone(),
            ..Item::default()
        });
    }

    let line = |text: &[u8]| replace(text, b'#', b'_');
    let name = |text: &[u8]| [line(text), b"#".to_vec()].concat();
    let desc = |lines: &[Vec<u8>]| {
        let body: Vec<Vec<u8>> = lines.iter().map(|l| line(l)).collect();
        [b"\n".to_vec(), body.join(&b'\n'), b"\n#".to_vec()].concat()
    };
    type Format<'a> = &'a dyn Fn(&Item) -> Vec<u8>;
    let tables: [(&str, Format); 4] = [
        ("idnum2itemdisplaynametable.txt", &|it| {
            name(&it.identified_name)
        }),
        ("num2itemdisplaynametable.txt", &|it| {
            name(&it.unidentified_name)
        }),
        ("idnum2itemdesctable.txt", &|it| desc(&it.identified_desc)),
        ("num2itemdesctable.txt", &|it| desc(&it.unidentified_desc)),
    ];
    for (file, format) in tables {
        let mut data = Vec::new();
        for (id, item) in &items {
            data.extend_from_slice(id.to_string().as_bytes());
            data.push(b'#');
            data.extend(format(item));
            data.push(b'\n');
        }
        write(&out.join(file), &data)?;
    }
    Ok(items.len())
}
