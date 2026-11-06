//! AC7: `needed` names exactly what the import reads, so a download of only those files imports the same.

mod common;

use common::*;
use nostalre_english::{import, select::needed};
use std::process::Command;

#[test]
fn importing_only_needed_files_gives_the_same_output() {
    let tmp = tempfile::tempdir().unwrap();
    let partial = tmp.path().join("partial");
    let all = paths(&checkout());
    let wanted: Vec<&String> = all.iter().filter(|p| needed(p)).collect();
    assert!(
        wanted.len() < all.len(),
        "some fixture files are not needed"
    );
    for rel in &wanted {
        let dest = partial.join(rel);
        std::fs::create_dir_all(dest.parent().unwrap()).unwrap();
        std::fs::copy(checkout().join(rel), dest).unwrap();
    }
    let (full_out, partial_out) = (tmp.path().join("full/data"), tmp.path().join("part/data"));
    import(&checkout(), &rathena(), &full_out).unwrap();
    import(&partial, &rathena(), &partial_out).unwrap();
    assert_same_tree(&partial_out, &full_out);
}

#[test]
fn inputs_are_needed() {
    for path in [
        "Translation/Renewal/SystemEN/LuaFiles514/itemInfo.lua",
        "Translation/Pre-Renewal/SystemEN/LuaFiles514/itemInfo.lua",
        "Translation/Pre-Renewal/data/lua files/skillinfoz/skilldescript.lub",
        "Translation/Pre-Renewal/data/skillnametable.txt",
        "Translation/Renewal/data/luafiles514/lua files/skillinfoz/skillinfolist.lub",
        "Translation/Renewal/data/luafiles514/lua files/skillinfoz/skilldescript.lub",
        "Translation/Renewal/data/msgstringtable.txt",
        "Translation/Renewal/data/texture/ui/btn.bmp",
        "Translation/Renewal/data/book/intro.txt",
        "Translation/Renewal/data/luafiles514/lua files/worldviewdata/worldviewdata_f.lub",
        "Translation/Compatibility/2018-06-20/data/texture/ui/btn.bmp",
        "Translation/Compatibility/2020-09-02/Renewal/data/texture/ui/btn.bmp",
    ] {
        assert!(needed(path), "{path}");
    }
}

#[test]
fn other_files_are_not_needed() {
    for path in [
        "README.md",
        "Additions/data/2009rwc_01.gnd",
        "Translation/Renewal/data/model/junk.rsm",
        "Translation/Renewal/data/texture/ui/notes.lua",
        "Translation/Renewal/data/luafiles514/lua files/datainfo/other.lub",
        "Translation/Renewal/data/texture/sub/deeper.txt.exe",
        "Translation/Compatibility/2020-01-22/data/texture/ui/skip.bmp",
        "Translation/Renewal/data/sub/nested.txt",
    ] {
        assert!(!needed(path), "{path}");
    }
}

#[test]
fn paths_leaving_the_checkout_are_never_needed() {
    for path in [
        "Translation/Renewal/data/texture/../../../../../etc/x.bmp",
        "/Translation/Renewal/data/texture/x.bmp",
        "Translation/Renewal/data/texture/./x.bmp",
        "Translation\\Renewal\\data\\texture\\x.bmp",
    ] {
        assert!(!needed(path), "{path}");
    }
}

#[test]
fn needed_command_prints_needed_blobs_of_a_tree_listing() {
    let tmp = tempfile::tempdir().unwrap();
    let tree = tmp.path().join("tree.json");
    std::fs::write(
        &tree,
        r#"{"sha":"x","truncated":false,"tree":[
            {"path":"Translation/Renewal/data","type":"tree"},
            {"path":"Translation/Renewal/data/msgstringtable.txt","type":"blob","sha":"a","size":3},
            {"path":"Additions/data/2009rwc_01.gnd","type":"blob","sha":"b","size":3},
            {"path":"Translation/Renewal/data/texture/ui/btn.bmp","type":"blob","sha":"c","size":3}
        ]}"#,
    )
    .unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_nostalre-english"))
        .arg("needed")
        .arg(&tree)
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_eq!(
        String::from_utf8(output.stdout).unwrap(),
        "Translation/Renewal/data/msgstringtable.txt\nTranslation/Renewal/data/texture/ui/btn.bmp\n"
    );
}

/// A GitHub tree listing of every file under `root`
fn listing(root: &std::path::Path) -> String {
    let entries: Vec<String> = paths(root)
        .iter()
        .map(|p| format!(r#"{{"path":{p:?},"type":"blob"}}"#))
        .collect();
    format!(r#"{{"truncated":false,"tree":[{}]}}"#, entries.join(","))
}

fn needed_paths(tree_json: &str) -> Vec<String> {
    let tmp = tempfile::tempdir().unwrap();
    let tree = tmp.path().join("tree.json");
    std::fs::write(&tree, tree_json).unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_nostalre-english"))
        .arg("needed")
        .arg(&tree)
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    String::from_utf8(output.stdout)
        .unwrap()
        .lines()
        .map(String::from)
        .collect()
}

/// The fixture plus a date whose `Renewal/` holds nothing kept: the old script takes nothing from that date (its
/// `Renewal/data` is absent), not the plain `data/` beside it
fn checkout_with_empty_renewal(root: &std::path::Path) {
    copy_dir(&checkout(), root);
    let date = root.join("Translation/Compatibility/2021-10-28");
    std::fs::create_dir_all(date.join("Renewal")).unwrap();
    std::fs::write(date.join("Renewal/readme.md"), "notes").unwrap();
    std::fs::create_dir_all(date.join("data/texture/ui")).unwrap();
    std::fs::write(
        date.join("data/texture/ui/btn.bmp"),
        "plain 2021, ignored\n",
    )
    .unwrap();
}

#[test]
fn needed_command_keeps_a_renewal_folder_holding_no_kept_file() {
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("checkout");
    checkout_with_empty_renewal(&root);
    let wanted = needed_paths(&listing(&root));
    assert!(
        wanted
            .iter()
            .any(|p| p.starts_with("Translation/Compatibility/2021-10-28/Renewal/")),
        "{wanted:?}"
    );
}

#[test]
fn download_of_needed_files_imports_like_the_full_checkout_with_an_empty_renewal() {
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("checkout");
    checkout_with_empty_renewal(&root);
    let partial = tmp.path().join("partial");
    for rel in needed_paths(&listing(&root)) {
        let dest = partial.join(&rel);
        std::fs::create_dir_all(dest.parent().unwrap()).unwrap();
        std::fs::copy(root.join(&rel), dest).unwrap();
    }
    let (full_out, partial_out) = (tmp.path().join("full/data"), tmp.path().join("part/data"));
    import(&root, &rathena(), &full_out).unwrap();
    import(&partial, &rathena(), &partial_out).unwrap();
    assert_eq!(
        read(&full_out.join("texture/ui/btn.bmp")),
        "compat 2020 renewal\n"
    );
    assert_same_tree(&partial_out, &full_out);
}

#[test]
fn needed_command_skips_plain_data_of_a_date_with_renewal() {
    let wanted = needed_paths(&listing(&checkout()));
    // 2020-09-02 has Renewal/: its plain data/ is never read
    assert!(
        !wanted
            .contains(&"Translation/Compatibility/2020-09-02/data/texture/ui/btn.bmp".to_string()),
        "{wanted:?}"
    );
    assert!(wanted.contains(
        &"Translation/Compatibility/2020-09-02/Renewal/data/texture/ui/btn.bmp".to_string()
    ));
    // 2018-06-20 has no Renewal/: its plain data/ is read
    assert!(
        wanted
            .contains(&"Translation/Compatibility/2018-06-20/data/texture/ui/btn.bmp".to_string())
    );
}

#[test]
fn needed_command_refuses_a_truncated_listing() {
    let tmp = tempfile::tempdir().unwrap();
    let tree = tmp.path().join("tree.json");
    std::fs::write(&tree, r#"{"sha":"x","truncated":true,"tree":[]}"#).unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_nostalre-english"))
        .arg("needed")
        .arg(&tree)
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert!(String::from_utf8_lossy(&output.stderr).contains("truncated"));
}
