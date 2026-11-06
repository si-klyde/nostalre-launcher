//! Inputs the old script read through Python's text handling: CRLF yml, lone-CR text tables.

mod common;

use common::*;
use nostalre_english::import;
use std::fs;

#[test]
fn crlf_rathena_db_gives_the_same_tables_as_lf() {
    let tmp = tempfile::tempdir().unwrap();
    let crlf = tmp.path().join("db");
    copy_dir(&rathena(), &crlf);
    for (rel, bytes) in tree(&crlf) {
        let text = String::from_utf8(bytes).unwrap().replace('\n', "\r\n");
        fs::write(crlf.join(rel), text).unwrap();
    }
    let out = tmp.path().join("data");
    import(&checkout(), &crlf, &out).unwrap();
    assert_same_tree(&out, &expected());
}

#[test]
fn lone_cr_separates_pre_renewal_skill_name_lines() {
    // Python's bytes.splitlines() splits on a lone \r too: the second line's name is read
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("checkout");
    copy_dir(&checkout(), &root);
    fs::write(
        root.join("Translation/Pre-Renewal/data/skillnametable.txt"),
        "MG_FIREBOLT#Fire_Bolt#\rDK_SERVANTWEAPON_ATK#Servant_Old#\r",
    )
    .unwrap();
    let out = tmp.path().join("data");
    import(&root, &rathena(), &out).unwrap();
    let names = read(&out.join("skillnametable.txt"));
    assert!(
        names.contains("DK_SERVANTWEAPON_ATK#Servant_Old#\n"),
        "{names}"
    );
    assert!(names.contains("MG_FIREBOLT#Fire_Bolt#\n"), "{names}");
}

#[test]
fn vertical_tab_is_trimmed_from_pre_renewal_skill_names() {
    // Python's bytes.strip() takes \x0b, Rust's trim_ascii() does not
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("checkout");
    copy_dir(&checkout(), &root);
    fs::write(
        root.join("Translation/Pre-Renewal/data/skillnametable.txt"),
        "\x0bDK_SERVANTWEAPON_ATK\x0b#\x0bServant_Old\x0b#\n",
    )
    .unwrap();
    let out = tmp.path().join("data");
    import(&root, &rathena(), &out).unwrap();
    let names = read(&out.join("skillnametable.txt"));
    assert!(
        names.contains("\nDK_SERVANTWEAPON_ATK#Servant_Old#\n")
            || names.starts_with("DK_SERVANTWEAPON_ATK#Servant_Old#\n"),
        "{names}"
    );
}

#[test]
fn shebang_line_of_item_info_is_skipped_like_luac() {
    // luac skips a first line starting with `#` but counts it, as an empty line, in the line numbers that function
    // headers keep even when stripped (checked against luac5.1 -s)
    let lub = |first_line: &str| {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("checkout");
        copy_dir(&checkout(), &root);
        let info = root.join("Translation/Renewal/SystemEN/LuaFiles514/itemInfo.lua");
        let mut src = first_line.as_bytes().to_vec();
        src.extend(fs::read(&info).unwrap());
        fs::write(&info, src).unwrap();
        let out = tmp.path().join("data");
        import(&root, &rathena(), &out).unwrap();
        fs::read(out.join("luafiles514/lua files/datainfo/iteminfo_en.lub")).unwrap()
    };
    assert_eq!(lub("#!/usr/bin/env lua\n"), lub("\n"));
}
