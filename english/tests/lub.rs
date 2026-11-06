//! AC5: the compiled iteminfo_en.lub loads in nostalRE's own Lua 5.1 chunk reader.

mod common;

use common::*;
use nostalre_english::import;

#[test]
fn iteminfo_en_loads_in_client_parser() {
    let tmp = tempfile::tempdir().unwrap();
    let out = tmp.path().join("data");
    import(&checkout(), &rathena(), &out).unwrap();

    let chunk = std::fs::read(out.join("luafiles514/lua files/datainfo/iteminfo_en.lub")).unwrap();
    assert!(chunk.starts_with(b"\x1bLuaQ"), "not a Lua 5.1 chunk");
    let items =
        nostalre_formats::lua_table::parse_item_info(&chunk).expect("client parser reads it");

    // compiled from the Renewal itemInfo.lua alone
    assert_eq!(
        items[&501].identified_name.as_deref(),
        Some("Red Potion (Renewal)")
    );
    assert_eq!(
        items[&502].unidentified_name.as_deref(),
        Some("Orange Potion")
    );
    assert_eq!(items[&501].class_num, Some(0));
    assert_eq!(items.len(), 2);
}
