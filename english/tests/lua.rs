//! AC2: the Lua table reader reads what `lua_table()` in the old Python importer (scripts/import-english.py, removed) read, the same way.

use nostalre_english::lua::{Key, Lua, parse_table};

fn table(src: &str, name: &str) -> Lua {
    parse_table(src.as_bytes(), name).unwrap_or_else(|e| panic!("{src}: {e}"))
}

fn s(text: &str) -> Lua {
    Lua::Str(text.as_bytes().to_vec())
}

#[test]
fn list_with_both_separators() {
    assert_eq!(
        table("T = { 1, 2; 3 }", "T"),
        Lua::List(vec![Lua::Int(1), Lua::Int(2), Lua::Int(3)])
    );
}

#[test]
fn trailing_separator_and_negative_numbers() {
    assert_eq!(
        table("T = { -1, 0, }", "T"),
        Lua::List(vec![Lua::Int(-1), Lua::Int(0)])
    );
}

#[test]
fn finds_the_named_table_after_other_code() {
    let src = "local x = 5\nOther = { 9 }\nT = { 7 }\nfunction main() return T end";
    assert_eq!(table(src, "T"), Lua::List(vec![Lua::Int(7)]));
}

#[test]
fn key_kinds() {
    let Lua::Map(map) = table(
        r#"T = { [501] = 1, ["k"] = 2, name = 3, [SKID.NV_BASIC] = 4 }"#,
        "T",
    ) else {
        panic!("not a map");
    };
    assert_eq!(map.get(&Key::Int(501)), Some(&Lua::Int(1)));
    assert_eq!(map.get(&Key::Str(b"k".to_vec())), Some(&Lua::Int(2)));
    assert_eq!(map.get(&Key::Name("name".into())), Some(&Lua::Int(3)));
    // a constant key keeps its last part
    assert_eq!(map.get(&Key::Name("NV_BASIC".into())), Some(&Lua::Int(4)));
}

#[test]
fn keys_keep_source_order() {
    let Lua::Map(map) = table("T = { [3] = 0, [1] = 0, [2] = 0 }", "T") else {
        panic!("not a map")
    };
    let keys: Vec<&Key> = map.keys().collect();
    assert_eq!(keys, vec![&Key::Int(3), &Key::Int(1), &Key::Int(2)]);
}

#[test]
fn constant_and_bool_values() {
    let Lua::Map(map) = table(
        "T = { a = SKID.SM_BASH, b = true, c = false, d = JT_NOVICE }",
        "T",
    ) else {
        panic!("not a map")
    };
    assert_eq!(
        map.get(&Key::Name("a".into())),
        Some(&Lua::Name("SM_BASH".into()))
    );
    assert_eq!(map.get(&Key::Name("b".into())), Some(&Lua::Bool(true)));
    assert_eq!(map.get(&Key::Name("c".into())), Some(&Lua::Bool(false)));
    assert_eq!(
        map.get(&Key::Name("d".into())),
        Some(&Lua::Name("JT_NOVICE".into()))
    );
}

#[test]
fn nested_tables() {
    let Lua::Map(map) = table(
        r#"T = { [1] = { name = "x", list = { { 1, 2 }, {} } } }"#,
        "T",
    ) else {
        panic!("not a map")
    };
    let Some(Lua::Map(inner)) = map.get(&Key::Int(1)) else {
        panic!("no inner map")
    };
    assert_eq!(inner.get(&Key::Name("name".into())), Some(&s("x")));
    assert_eq!(
        inner.get(&Key::Name("list".into())),
        Some(&Lua::List(vec![
            Lua::List(vec![Lua::Int(1), Lua::Int(2)]),
            Lua::List(vec![])
        ]))
    );
}

#[test]
fn keyed_and_positional_mixed_keeps_only_the_keys() {
    // `lua_table()` returns the dict when a table has any keys: the positional "SM_BASH" is dropped
    let Lua::Map(map) = table(r#"T = { "SM_BASH"; MaxLv = 2 }"#, "T") else {
        panic!("not a map")
    };
    assert_eq!(map.len(), 1);
    assert_eq!(map.get(&Key::Name("MaxLv".into())), Some(&Lua::Int(2)));
}

#[test]
fn escapes_as_the_script_reads_them() {
    // \n and \t are control characters; any other escaped character is itself (\r reads as "r")
    let src = r#"T = { "a\nb", "c\td", "q\"q", "back\\slash", "r\r" }"#;
    assert_eq!(
        table(src, "T"),
        Lua::List(vec![
            s("a\nb"),
            s("c\td"),
            s("q\"q"),
            s("back\\slash"),
            s("rr")
        ])
    );
}

#[test]
fn comments_are_skipped() {
    let src = "T = {\n-- first\n1, -- one\n2 -- two\n}";
    assert_eq!(table(src, "T"), Lua::List(vec![Lua::Int(1), Lua::Int(2)]));
}

#[test]
fn strings_are_raw_bytes() {
    // CP949 bytes pass through untouched
    let src = b"T = { \"\xbb\xa1\xb0\xa3\" }";
    assert_eq!(
        parse_table(src, "T").unwrap(),
        Lua::List(vec![Lua::Str(vec![0xbb, 0xa1, 0xb0, 0xa3])])
    );
}

#[test]
fn vertical_tab_is_whitespace() {
    // Python's \s takes \x0b
    assert_eq!(
        parse_table(b"T = {\x0b1,\x0b2 }", "T").unwrap(),
        Lua::List(vec![Lua::Int(1), Lua::Int(2)])
    );
}

#[test]
fn number_too_big_is_an_error_not_zero() {
    assert!(parse_table(b"T = { [99999999999999999999] = 1 }", "T").is_err());
}

#[test]
fn missing_table_is_an_error() {
    assert!(parse_table(b"Other = { 1 }", "T").is_err());
}

#[test]
fn unterminated_table_is_an_error() {
    assert!(parse_table(b"T = { [501] = {", "T").is_err());
}

#[test]
fn bare_name_values_read_as_their_bytes() {
    // the script's `A.B` and bare-name values are bytes like strings: `SkillName = FOO` names the skill FOO
    let entry = table("T = { a = FOO, b = SKID.SM_BASH, c = 5 }", "T");
    assert_eq!(entry.get("a").and_then(Lua::as_bytes), Some(&b"FOO"[..]));
    assert_eq!(
        entry.get("b").and_then(Lua::as_bytes),
        Some(&b"SM_BASH"[..])
    );
    assert_eq!(entry.get("c").and_then(Lua::as_bytes), None);
}

#[test]
fn lines_are_a_lists_text_items() {
    let list = table(r#"T = { "one", TWO, 3, { "x" } }"#, "T");
    assert_eq!(list.lines(), vec![b"one".to_vec(), b"TWO".to_vec()]);
    assert!(table(r#"T = { k = "v" }"#, "T").lines().is_empty());
}
