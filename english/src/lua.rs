//! Reads one top-level Lua table literal (`name = { ... }`) without running Lua, the way `lua_table()` in the old
//! the old Python importer (scripts/import-english.py, removed) did: strings stay raw bytes (CP949), `[A.B]` keys and `A.B` values keep `B`, and a table
//! with any key is a map whose positional values are dropped.

use indexmap::IndexMap;
use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum Key {
    Int(i64),
    /// A `["quoted"]` key
    Str(Vec<u8>),
    /// A bare `name =` key, or the last part of a `[A.B]` one
    Name(String),
}

#[derive(Debug, Clone, PartialEq)]
pub enum Lua {
    Str(Vec<u8>),
    Int(i64),
    Bool(bool),
    /// A bare name or constant (`SKID.SM_BASH` reads as `SM_BASH`)
    Name(String),
    Map(IndexMap<Key, Lua>),
    List(Vec<Lua>),
}

impl Lua {
    pub fn get(&self, key: &str) -> Option<&Lua> {
        match self {
            Lua::Map(map) => map.get(&Key::Name(key.to_string())),
            _ => None,
        }
    }

    /// A string's bytes, or a bare name's: the script read `FOO` and `A.B` values as bytes like strings
    pub fn as_bytes(&self) -> Option<&[u8]> {
        match self {
            Lua::Str(bytes) => Some(bytes),
            Lua::Name(name) => Some(name.as_bytes()),
            _ => None,
        }
    }

    /// A list's text items (description lines), others skipped; nothing for any other value
    pub fn lines(&self) -> Vec<Vec<u8>> {
        self.items()
            .iter()
            .filter_map(|v| v.as_bytes().map(<[u8]>::to_vec))
            .collect()
    }

    /// The truthiness the script tested with `entry.get(...)`: empty strings, lists and maps, 0 and false are false
    pub fn truthy(&self) -> bool {
        match self {
            Lua::Str(s) => !s.is_empty(),
            Lua::Int(n) => *n != 0,
            Lua::Bool(b) => *b,
            Lua::Name(n) => !n.is_empty(),
            Lua::Map(m) => !m.is_empty(),
            Lua::List(l) => !l.is_empty(),
        }
    }

    /// A list's items; nothing for any other value (a map included)
    pub fn items(&self) -> &[Lua] {
        match self {
            Lua::List(items) => items,
            _ => &[],
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LuaError {
    NotFound(String),
    UnexpectedEnd,
    Unexpected { token: String, at: usize },
}

impl fmt::Display for LuaError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            LuaError::NotFound(name) => write!(f, "no `{name} = {{` table"),
            LuaError::UnexpectedEnd => write!(f, "table not closed"),
            LuaError::Unexpected { token, at } => write!(f, "unexpected {token:?} at byte {at}"),
        }
    }
}

impl std::error::Error for LuaError {}

#[derive(Debug, Clone, PartialEq)]
enum Tok {
    Str(Vec<u8>),
    Int(i64),
    Name(String),
    Punct(u8),
}

struct Token {
    tok: Tok,
    at: usize,
}

fn tokenize(data: &[u8]) -> Result<Vec<Token>, LuaError> {
    let mut tokens = Vec::new();
    let mut i = 0;
    while i < data.len() {
        let c = data[i];
        let start = i;
        // Python's \s: ASCII whitespace and the vertical tab
        if c.is_ascii_whitespace() || c == 0x0b {
            i += 1;
        } else if data[i..].starts_with(b"--") {
            while i < data.len() && data[i] != b'\n' {
                i += 1;
            }
        } else if c == b'"' {
            match string_end(data, i + 1) {
                Some(end) => {
                    tokens.push(Token {
                        tok: Tok::Str(unescape(&data[i + 1..end])),
                        at: start,
                    });
                    i = end + 1;
                }
                // an unclosed quote is a lone punctuation mark, as the script's catch-all reads it
                None => {
                    tokens.push(Token {
                        tok: Tok::Punct(c),
                        at: start,
                    });
                    i += 1;
                }
            }
        } else if c.is_ascii_digit()
            || (c == b'-' && data.get(i + 1).is_some_and(u8::is_ascii_digit))
        {
            i += 1;
            while i < data.len() && data[i].is_ascii_digit() {
                i += 1;
            }
            let text = std::str::from_utf8(&data[start..i]).expect("ASCII digits");
            let n = text.parse().map_err(|_| LuaError::Unexpected {
                token: text.to_string(),
                at: start,
            })?;
            tokens.push(Token {
                tok: Tok::Int(n),
                at: start,
            });
        } else if c.is_ascii_alphabetic() || c == b'_' {
            while i < data.len() && (data[i].is_ascii_alphanumeric() || data[i] == b'_') {
                i += 1;
            }
            let name = String::from_utf8_lossy(&data[start..i]).into_owned();
            tokens.push(Token {
                tok: Tok::Name(name),
                at: start,
            });
        } else {
            tokens.push(Token {
                tok: Tok::Punct(c),
                at: start,
            });
            i += 1;
        }
    }
    Ok(tokens)
}

/// Index of the closing quote of a string whose body starts at `from`
fn string_end(data: &[u8], from: usize) -> Option<usize> {
    let mut i = from;
    while i < data.len() {
        match data[i] {
            b'"' => return Some(i),
            b'\\' => i += 2,
            _ => i += 1,
        }
    }
    None
}

/// `\n` and `\t` become control characters, any other escaped character itself; a backslash before a newline stays
fn unescape(raw: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(raw.len());
    let mut i = 0;
    while i < raw.len() {
        if raw[i] == b'\\' && i + 1 < raw.len() && raw[i + 1] != b'\n' {
            out.push(match raw[i + 1] {
                b'n' => b'\n',
                b't' => b'\t',
                other => other,
            });
            i += 2;
        } else {
            out.push(raw[i]);
            i += 1;
        }
    }
    out
}

struct Parser {
    tokens: Vec<Token>,
    pos: usize,
}

impl Parser {
    fn tok(&self, at: usize) -> Result<&Tok, LuaError> {
        self.tokens
            .get(at)
            .map(|t| &t.tok)
            .ok_or(LuaError::UnexpectedEnd)
    }

    fn is_punct(&self, at: usize, c: u8) -> bool {
        matches!(self.tokens.get(at), Some(Token { tok: Tok::Punct(p), .. }) if *p == c)
    }

    fn value(&mut self) -> Result<Lua, LuaError> {
        let tok = self.tok(self.pos)?.clone();
        let at = self.tokens[self.pos].at;
        self.pos += 1;
        Ok(match tok {
            Tok::Str(s) => Lua::Str(s),
            Tok::Int(n) => Lua::Int(n),
            Tok::Name(name) if name == "true" => Lua::Bool(true),
            Tok::Name(name) if name == "false" => Lua::Bool(false),
            Tok::Name(mut name) => {
                while self.is_punct(self.pos, b'.')
                    && let Ok(Tok::Name(next)) = self.tok(self.pos + 1)
                {
                    name = next.clone();
                    self.pos += 2;
                }
                Lua::Name(name)
            }
            Tok::Punct(b'{') => self.table()?,
            Tok::Punct(c) => {
                return Err(LuaError::Unexpected {
                    token: (c as char).to_string(),
                    at,
                });
            }
        })
    }

    fn table(&mut self) -> Result<Lua, LuaError> {
        let mut map = IndexMap::new();
        let mut items = Vec::new();
        while !self.is_punct(self.pos, b'}') {
            self.tok(self.pos)?;
            if self.is_punct(self.pos, b'[') {
                self.pos += 1;
                let mut last = None;
                while !self.is_punct(self.pos, b']') {
                    last = Some(self.tok(self.pos)?.clone());
                    self.pos += 1;
                }
                self.pos += 2; // ] =
                let key = match last {
                    Some(Tok::Int(n)) => Key::Int(n),
                    Some(Tok::Str(s)) => Key::Str(s),
                    Some(Tok::Name(n)) => Key::Name(n),
                    _ => {
                        return Err(LuaError::Unexpected {
                            token: "[]".into(),
                            at: self.tokens[self.pos - 2].at,
                        });
                    }
                };
                let value = self.value()?;
                map.insert(key, value);
            } else if let Tok::Name(name) = self.tok(self.pos)?
                && self.is_punct(self.pos + 1, b'=')
            {
                let key = Key::Name(name.clone());
                self.pos += 2;
                let value = self.value()?;
                map.insert(key, value);
            } else {
                items.push(self.value()?);
            }
            if self.is_punct(self.pos, b',') || self.is_punct(self.pos, b';') {
                self.pos += 1;
            }
        }
        self.pos += 1;
        Ok(if map.is_empty() {
            Lua::List(items)
        } else {
            Lua::Map(map)
        })
    }
}

/// The table literal assigned to the top-level `name`
pub fn parse_table(src: &[u8], name: &str) -> Result<Lua, LuaError> {
    let tokens = tokenize(src)?;
    let start = (0..tokens.len().saturating_sub(1))
        .find(|&i| {
            matches!(&tokens[i].tok, Tok::Name(n) if n == name)
                && matches!(tokens[i + 1].tok, Tok::Punct(b'='))
        })
        .ok_or_else(|| LuaError::NotFound(name.to_string()))?;
    let mut parser = Parser {
        tokens,
        pos: start + 2,
    };
    if !parser.is_punct(parser.pos, b'{') {
        let at = parser.tokens.get(parser.pos).map_or(src.len(), |t| t.at);
        return Err(LuaError::Unexpected {
            token: "not a table".into(),
            at,
        });
    }
    parser.pos += 1;
    parser.table()
}
