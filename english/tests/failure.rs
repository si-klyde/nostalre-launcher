//! AC6 (escape), AC8 (all or nothing), AC9 (missing input).

mod common;

use common::*;
use nostalre_english::import;
use std::fs;
use std::path::Path;
use std::process::Command;

const MARKER: &str = ".nostalre-english";

/// An earlier import's output: owned by the tool (its marker), with one file of its own
fn marker(out: &Path) {
    fs::create_dir_all(out).unwrap();
    fs::write(out.join(MARKER), "").unwrap();
    fs::write(out.join("old.txt"), "old import").unwrap();
}

/// `.<name>.nostalre-english-<what>` beside `out`: names a player's own folders cannot collide with
fn sibling(out: &Path, what: &str) -> std::path::PathBuf {
    out.with_file_name(format!(
        ".{}.nostalre-english-{what}",
        out.file_name().unwrap().to_string_lossy()
    ))
}

fn tmp_sibling(out: &Path) -> std::path::PathBuf {
    sibling(out, "tmp")
}

#[test]
fn successful_import_replaces_the_old_output() {
    let tmp = tempfile::tempdir().unwrap();
    let out = tmp.path().join("data");
    marker(&out);
    import(&checkout(), &rathena(), &out).unwrap();
    assert!(!out.join("old.txt").exists());
    assert!(out.join("skillnametable.txt").exists());
    assert!(out.join(MARKER).exists());
    assert!(!tmp_sibling(&out).exists());
}

#[test]
fn import_into_a_new_or_empty_folder_works() {
    let tmp = tempfile::tempdir().unwrap();
    let empty = tmp.path().join("empty");
    fs::create_dir_all(&empty).unwrap();
    import(&checkout(), &rathena(), &empty).unwrap();
    assert!(empty.join(MARKER).exists());
    let fresh = tmp.path().join("fresh");
    import(&checkout(), &rathena(), &fresh).unwrap();
    assert!(fresh.join(MARKER).exists());
}

#[test]
fn folder_not_owned_by_the_tool_is_never_replaced() {
    let tmp = tempfile::tempdir().unwrap();
    let out = tmp.path().join("data");
    fs::create_dir_all(&out).unwrap();
    fs::write(out.join("airplane.gat"), "kRO's own").unwrap();

    let err = import(&checkout(), &rathena(), &out).unwrap_err();
    assert!(err.to_string().contains(MARKER), "{err}");
    assert_eq!(read(&out.join("airplane.gat")), "kRO's own");
    assert_eq!(paths(&out), vec!["airplane.gat".to_string()]);
    assert!(!tmp_sibling(&out).exists());
}

#[test]
fn players_own_tmp_and_old_folders_are_left_alone() {
    let tmp = tempfile::tempdir().unwrap();
    let out = tmp.path().join("data");
    for name in ["data.old", "data.tmp"] {
        fs::create_dir_all(tmp.path().join(name)).unwrap();
        fs::write(tmp.path().join(name).join("backup.txt"), "mine").unwrap();
    }
    import(&checkout(), &rathena(), &out).unwrap();
    for name in ["data.old", "data.tmp"] {
        assert_eq!(read(&tmp.path().join(name).join("backup.txt")), "mine");
    }
}

#[test]
fn players_old_folder_is_not_mistaken_for_a_crash_leftover() {
    // no marker inside: not ours, so it is not moved into place even though `out` is missing
    let tmp = tempfile::tempdir().unwrap();
    let out = tmp.path().join("data");
    let theirs = sibling(&out, "old");
    fs::create_dir_all(&theirs).unwrap();
    fs::write(theirs.join("backup.txt"), "mine").unwrap();
    import(&checkout(), &rathena(), &out).unwrap();
    assert!(!out.join("backup.txt").exists());
    assert_eq!(read(&theirs.join("backup.txt")), "mine");
}

#[test]
fn failed_import_keeps_the_old_output() {
    let tmp = tempfile::tempdir().unwrap();
    let broken = tmp.path().join("checkout");
    copy_dir(&checkout(), &broken);
    fs::write(
        broken.join("Translation/Renewal/SystemEN/LuaFiles514/itemInfo.lua"),
        "tbl = { [501] = {",
    )
    .unwrap();
    let out = tmp.path().join("data");
    marker(&out);

    assert!(import(&broken, &rathena(), &out).is_err());
    assert_eq!(read(&out.join("old.txt")), "old import");
    assert_eq!(paths(&out), vec!["old.txt".to_string()]);
    assert!(!tmp_sibling(&out).exists());
}

#[test]
fn leftover_tmp_from_a_crash_is_cleared() {
    let tmp = tempfile::tempdir().unwrap();
    let out = tmp.path().join("data");
    marker(&tmp_sibling(&out));
    fs::write(tmp_sibling(&out).join("stale.txt"), "stale").unwrap();
    import(&checkout(), &rathena(), &out).unwrap();
    assert!(!out.join("stale.txt").exists());
    assert!(!tmp_sibling(&out).exists());
}

/// A run stopped between moving `out` aside and moving the new one in leaves it only in the `old` sibling
fn crashed_mid_swap(out: &Path) -> std::path::PathBuf {
    let old = sibling(out, "old");
    marker(&old);
    old
}

#[test]
fn old_output_left_by_a_crash_survives_a_failed_import() {
    let tmp = tempfile::tempdir().unwrap();
    let broken = tmp.path().join("checkout");
    copy_dir(&checkout(), &broken);
    fs::write(
        broken.join("Translation/Renewal/SystemEN/LuaFiles514/itemInfo.lua"),
        "tbl = { [501] = {",
    )
    .unwrap();
    let out = tmp.path().join("data");
    let old = crashed_mid_swap(&out);

    assert!(import(&broken, &rathena(), &out).is_err());
    assert_eq!(read(&out.join("old.txt")), "old import");
    assert!(!old.exists());
}

#[test]
fn old_output_left_by_a_crash_is_replaced_by_a_good_import() {
    let tmp = tempfile::tempdir().unwrap();
    let out = tmp.path().join("data");
    let old = crashed_mid_swap(&out);
    import(&checkout(), &rathena(), &out).unwrap();
    assert!(!out.join("old.txt").exists());
    assert!(out.join("skillnametable.txt").exists());
    assert!(!old.exists());
}

#[cfg(unix)]
#[test]
fn symlink_in_checkout_is_refused_and_nothing_written() {
    let tmp = tempfile::tempdir().unwrap();
    let evil = tmp.path().join("checkout");
    copy_dir(&checkout(), &evil);
    let outside = tmp.path().join("secret.txt");
    fs::write(&outside, "secret").unwrap();
    std::os::unix::fs::symlink(
        &outside,
        evil.join("Translation/Renewal/data/texture/ui/evil.bmp"),
    )
    .unwrap();
    let out = tmp.path().join("data");

    let err = import(&evil, &rathena(), &out).unwrap_err();
    assert!(err.to_string().contains("evil.bmp"), "{err}");
    assert!(!out.exists());
    assert!(!tmp_sibling(&out).exists());
}

fn without(rel: &str) -> tempfile::TempDir {
    let tmp = tempfile::tempdir().unwrap();
    copy_dir(&checkout(), &tmp.path().join("checkout"));
    fs::remove_file(tmp.path().join("checkout").join(rel)).unwrap();
    tmp
}

#[test]
fn missing_item_info_names_the_path() {
    let rel = "Translation/Renewal/SystemEN/LuaFiles514/itemInfo.lua";
    let tmp = without(rel);
    let err = import(
        &tmp.path().join("checkout"),
        &rathena(),
        &tmp.path().join("data"),
    )
    .unwrap_err();
    assert!(err.to_string().contains("itemInfo.lua"), "{err}");
    assert!(!tmp.path().join("data").exists());
}

#[test]
fn missing_skill_list_names_the_path() {
    let tmp =
        without("Translation/Renewal/data/luafiles514/lua files/skillinfoz/skillinfolist.lub");
    let err = import(
        &tmp.path().join("checkout"),
        &rathena(),
        &tmp.path().join("data"),
    )
    .unwrap_err();
    assert!(err.to_string().contains("skillinfolist.lub"), "{err}");
}

#[test]
fn missing_rathena_skill_db_names_the_path() {
    let tmp = tempfile::tempdir().unwrap();
    let db = tmp.path().join("db");
    copy_dir(&rathena(), &db);
    fs::remove_file(db.join("skill_db.yml")).unwrap();
    let err = import(&checkout(), &db, &tmp.path().join("data")).unwrap_err();
    assert!(err.to_string().contains("skill_db.yml"), "{err}");
}

#[test]
fn import_command_fails_with_the_missing_path() {
    let rel = "Translation/Renewal/SystemEN/LuaFiles514/itemInfo.lua";
    let tmp = without(rel);
    let output = Command::new(env!("CARGO_BIN_EXE_nostalre-english"))
        .arg("import")
        .arg(tmp.path().join("checkout"))
        .arg(rathena())
        .arg(tmp.path().join("data"))
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert!(String::from_utf8_lossy(&output.stderr).contains("itemInfo.lua"));
}

#[test]
fn import_command_succeeds_on_the_fixture() {
    let tmp = tempfile::tempdir().unwrap();
    let out = tmp.path().join("data");
    let output = Command::new(env!("CARGO_BIN_EXE_nostalre-english"))
        .arg("import")
        .arg(checkout())
        .arg(rathena())
        .arg(&out)
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_same_tree(&out, &expected());
}

#[test]
fn usage_says_the_output_folder_is_the_tools_alone() {
    let output = Command::new(env!("CARGO_BIN_EXE_nostalre-english"))
        .output()
        .unwrap();
    let usage = String::from_utf8_lossy(&output.stderr).to_lowercase();
    assert!(usage.contains("dedicated"), "{usage}");
    assert!(usage.contains("full checkout"), "{usage}");
}

#[test]
fn unknown_command_is_a_usage_error() {
    let output = Command::new(env!("CARGO_BIN_EXE_nostalre-english"))
        .arg("frobnicate")
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert!(
        String::from_utf8_lossy(&output.stderr)
            .to_lowercase()
            .contains("usage")
    );
}
