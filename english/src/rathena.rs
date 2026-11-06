//! What the import takes from rAthena's Renewal db (the one the APK's servers run): English item names for the items
//! the translation lacks, shield/headgear views, cards, and skill names and SP.

use crate::ImportError;
use crate::text::cp949;
use regex::Regex;
use serde::Deserialize;
use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

/// The item_db_*.yml files, in path order (later ones win an id both have)
fn item_dbs(db: &Path) -> Result<Vec<PathBuf>, ImportError> {
    let mut paths: Vec<PathBuf> = fs::read_dir(db)
        .map_err(|e| ImportError::io(db, e))?
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| {
            p.file_name()
                .and_then(|n| n.to_str())
                .is_some_and(|n| n.starts_with("item_db_") && n.ends_with(".yml"))
        })
        .collect();
    paths.sort();
    Ok(paths)
}

/// UTF-8 text with bad bytes replaced and line ends made `\n`, as Python's `read_text(errors="replace")`
fn read_text(path: &Path) -> Result<String, ImportError> {
    let bytes = crate::read_input(path)?;
    Ok(String::from_utf8_lossy(&bytes)
        .replace("\r\n", "\n")
        .replace('\r', "\n"))
}

/// Id -> English name (CP949) of every item in the db
pub fn item_names(db: &Path) -> Result<BTreeMap<i64, Vec<u8>>, ImportError> {
    let entry = Regex::new(r"(?m)^  - Id: (\d+)[^\n]*\n(?:    .*\n)*?    Name: (.+)$")
        .expect("valid regex");
    let mut names = BTreeMap::new();
    for path in item_dbs(db)? {
        let text = read_text(&path)?;
        for cap in entry.captures_iter(&text) {
            let Ok(id) = cap[1].parse::<i64>() else {
                continue;
            };
            let name = cap[2]
                .split('#')
                .next()
                .unwrap_or("")
                .trim()
                .trim_matches('"');
            names.insert(id, cp949(name));
        }
    }
    Ok(names)
}

/// The db's shields (`id#view#`), headgear (`id#view#`) and cards (`id#`), as the client's look-up tables
pub struct ViewTables {
    pub shields: BTreeMap<i64, i64>,
    pub headgear: BTreeMap<i64, i64>,
    pub cards: Vec<i64>,
}

pub fn view_tables(db: &Path) -> Result<ViewTables, ImportError> {
    let re = |pattern: &str| Regex::new(&format!("(?m){pattern}")).expect("valid regex");
    let (card, view, armor) = (
        re(r"^    Type: Card$"),
        re(r"^    View: (\d+)$"),
        re(r"^    Type: Armor$"),
    );
    let (left_hand, head) = (
        re(r"^      Left_Hand: true$"),
        re(r"^      (Costume_)?Head_(Top|Mid|Low): true$"),
    );
    let mut tables = ViewTables {
        shields: BTreeMap::new(),
        headgear: BTreeMap::new(),
        cards: Vec::new(),
    };
    for path in item_dbs(db)? {
        let text = read_text(&path)?;
        for entry in text.split("\n  - Id: ").skip(1) {
            let first = entry.split('\n').next().unwrap_or("");
            let Some(Ok(id)) = first.split_whitespace().next().map(str::parse::<i64>) else {
                continue;
            };
            if card.is_match(entry) {
                tables.cards.push(id);
            }
            let Some(view) = view.captures(entry).and_then(|c| c[1].parse::<i64>().ok()) else {
                continue;
            };
            if !armor.is_match(entry) {
                continue;
            }
            if left_hand.is_match(entry) {
                tables.shields.insert(id, view);
            } else if head.is_match(entry) {
                tables.headgear.insert(id, view);
            }
        }
    }
    tables.cards.sort();
    Ok(tables)
}

#[derive(Deserialize)]
struct SkillDb {
    #[serde(rename = "Body")]
    body: Vec<SkillEntry>,
}

#[derive(Deserialize)]
struct SkillEntry {
    #[serde(rename = "Name")]
    name: String,
    #[serde(rename = "Description")]
    description: Option<String>,
    #[serde(rename = "MaxLevel")]
    max_level: Option<u32>,
    #[serde(rename = "Requires")]
    requires: Option<Requires>,
}

#[derive(Deserialize)]
struct Requires {
    #[serde(rename = "SpCost")]
    sp_cost: Option<SpCost>,
}

#[derive(Deserialize)]
#[serde(untagged)]
enum SpCost {
    Flat(i64),
    Levels(Vec<LevelAmount>),
}

#[derive(Deserialize)]
struct LevelAmount {
    #[serde(rename = "Level")]
    level: u32,
    #[serde(rename = "Amount")]
    amount: i64,
}

/// A skill as the db has it: its English name (CP949) and SP at each level
pub struct Skill {
    pub name: Vec<u8>,
    pub sp: Vec<i64>,
}

/// Aegis name -> skill, from skill_db.yml
pub fn skills(db: &Path) -> Result<BTreeMap<String, Skill>, ImportError> {
    let path = db.join("skill_db.yml");
    let text = read_text(&path)?;
    let parsed: SkillDb = serde_norway::from_str(&text).map_err(|e| ImportError::Yaml {
        path: path.clone(),
        message: e.to_string(),
    })?;
    let mut skills = BTreeMap::new();
    for entry in parsed.body {
        let levels = 1..=entry.max_level.unwrap_or(1);
        let sp = match entry.requires.and_then(|r| r.sp_cost) {
            Some(SpCost::Levels(costs)) if !costs.is_empty() => {
                let by_level: BTreeMap<u32, i64> =
                    costs.iter().map(|c| (c.level, c.amount)).collect();
                levels
                    .map(|l| by_level.get(&l).copied().unwrap_or(0))
                    .collect()
            }
            Some(SpCost::Flat(cost)) => levels.map(|_| cost).collect(),
            _ => levels.map(|_| 0).collect(),
        };
        let name = cp949(entry.description.as_deref().unwrap_or(&entry.name));
        skills.insert(entry.name, Skill { name, sp });
    }
    Ok(skills)
}
