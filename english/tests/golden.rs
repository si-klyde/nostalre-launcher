//! The fixture checkout imports to what the old Python importer (removed) wrote for it (AC1 on the fixture), and the rules
//! behind those files (AC3, AC4, AC6 compatibility order).

mod common;

use common::*;
use nostalre_english::import;

fn imported() -> (tempfile::TempDir, std::path::PathBuf) {
    let tmp = tempfile::tempdir().unwrap();
    let out = tmp.path().join("data");
    import(&checkout(), &rathena(), &out).expect("fixture imports");
    (tmp, out)
}

#[test]
fn fixture_matches_python_output_byte_for_byte() {
    let (_tmp, out) = imported();
    assert_same_tree(&out, &expected());
}

#[test]
fn pre_renewal_item_text_wins_over_renewal() {
    let (_tmp, out) = imported();
    let names = read(&out.join("idnum2itemdisplaynametable.txt"));
    assert!(names.contains("501#Red Potion#\n"), "{names}");
    assert!(!names.contains("Renewal)"), "{names}");
    let desc = read(&out.join("idnum2itemdesctable.txt"));
    assert!(desc.contains("501#\nPre-Renewal text\n#\n"), "{desc}");
}

#[test]
fn rathena_names_fill_items_the_translation_lacks() {
    let (_tmp, out) = imported();
    for table in [
        "idnum2itemdisplaynametable.txt",
        "num2itemdisplaynametable.txt",
    ] {
        let names = read(&out.join(table));
        // trailing YAML comment and quotes are dropped
        assert!(
            names.contains("100001#Brand New Item#\n"),
            "{table}: {names}"
        );
        assert!(names.contains("19500#Costume Hat#\n"), "{table}: {names}");
    }
}

#[test]
fn hash_in_item_text_becomes_underscore() {
    let (_tmp, out) = imported();
    let desc = read(&out.join("idnum2itemdesctable.txt"));
    assert!(desc.contains("Line\twith tab _2\n"), "{desc}");
}

#[test]
fn rathena_view_and_card_tables() {
    let (_tmp, out) = imported();
    assert_eq!(read(&out.join("shieldviewtable.txt")), "2101#1#\n");
    assert_eq!(
        read(&out.join("headgearviewtable.txt")),
        "2220#16#\n19500#900#\n"
    );
    assert_eq!(read(&out.join("rathenacardtable.txt")), "4001#\n");
}

#[test]
fn skill_names_prefer_renewal_then_pre_renewal_then_rathena() {
    let (_tmp, out) = imported();
    assert_eq!(
        read(&out.join("skillnametable.txt")),
        "DK_SERVANTWEAPON_ATK#Servant_Weapon_Attack#\n\
         MG_FIREBOLT#Fire_Bolt#\n\
         NV_BASIC#Basic_Skill#\n\
         SM_BASH#Bash#\n"
    );
}

#[test]
fn level_pick_table_lists_only_seperate_lv_skills_with_rathena_sp() {
    let (_tmp, out) = imported();
    // SM_BASH: flat SpCost 8 for both levels; MG_FIREBOLT: rAthena's 12/15 over the lub's 12/14
    assert_eq!(
        read(&out.join("leveluseskillspamount.txt")),
        "SM_BASH#\n8#\n8#\n@\nMG_FIREBOLT#\n12#\n15#\n@\n"
    );
}

#[test]
fn skill_descriptions_renewal_over_pre_renewal() {
    let (_tmp, out) = imported();
    assert!(read(&out.join("skilldesctable.txt")).contains("SM_BASH#\nBash\nOld text\n#\n"));
    assert!(
        read(&out.join("skilldesctable_renewal.txt"))
            .contains("SM_BASH#\nBash\nRenewal _text_\n#\n")
    );
}

#[test]
fn compatibility_folders_override_renewal_in_date_order() {
    let (_tmp, out) = imported();
    // 2018-06-20 then 2020-09-02's Renewal/ (its plain data/ is ignored); 2020-01-22 is not a date taken
    assert_eq!(
        read(&out.join("texture/ui/btn.bmp")),
        "compat 2020 renewal\n"
    );
    assert!(!out.join("texture/ui/skip.bmp").exists());
    assert_eq!(read(&out.join("texture/ui/keep.tga")), "renewal only\n");
}

#[test]
fn only_text_textures_books_and_world_map_are_copied() {
    let (_tmp, out) = imported();
    assert!(out.join("msgstringtable.txt").exists());
    assert!(out.join("book/intro.txt").exists());
    assert!(
        out.join("luafiles514/lua files/worldviewdata/worldviewdata_f.lub")
            .exists()
    );
    assert!(!out.join("model/junk.rsm").exists());
    assert!(!out.join("texture/ui/notes.lua").exists());
    assert!(
        !out.join("luafiles514/lua files/datainfo/other.lub")
            .exists()
    );
}
