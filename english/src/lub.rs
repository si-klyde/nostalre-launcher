//! `data/luafiles514/lua files/datainfo/iteminfo_en.lub`: the Renewal itemInfo.lua as Lua 5.1 bytecode, what the
//! client reads, byte for byte what `luac5.1 -s` writes. Lua 5.1's `lua_dump` cannot strip, so the dump is
//! stripped here: every function's source name, line info, local and upvalue names dropped.

use crate::ImportError;
use std::path::Path;

pub fn compile(source: &[u8], path: &Path) -> Result<Vec<u8>, ImportError> {
    let compile_error = |message: String| ImportError::Compile {
        path: path.to_path_buf(),
        message,
    };
    let lua = mlua::Lua::new();
    let function = lua
        .load(skip_shebang(source))
        .set_name("=itemInfo")
        .into_function()
        .map_err(|e| compile_error(e.to_string()))?;
    strip(&function.dump(false)).map_err(|e| compile_error(format!("bytecode: {e}")))
}

/// The source as `luac` reads it: a first line starting with `#` is skipped, its line end kept
fn skip_shebang(source: &[u8]) -> &[u8] {
    if !source.starts_with(b"#") {
        return source;
    }
    source
        .iter()
        .position(|&b| b == b'\n')
        .map_or(&[], |i| &source[i..])
}

/// A Lua 5.1 chunk without its debug information
pub fn strip(chunk: &[u8]) -> Result<Vec<u8>, &'static str> {
    const HEADER: usize = 12;
    if chunk.len() < HEADER || &chunk[..5] != b"\x1bLuaQ" {
        return Err("not a Lua 5.1 chunk");
    }
    let sizes = Sizes {
        int: chunk[7] as usize,
        size_t: chunk[8] as usize,
        instruction: chunk[9] as usize,
        number: chunk[10] as usize,
    };
    let little = chunk[6] == 1;
    let mut reader = Reader {
        data: chunk,
        pos: HEADER,
        sizes,
        little,
    };
    let mut out = chunk[..HEADER].to_vec();
    function(&mut reader, &mut out)?;
    Ok(out)
}

#[derive(Clone, Copy)]
struct Sizes {
    int: usize,
    size_t: usize,
    instruction: usize,
    number: usize,
}

struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
    sizes: Sizes,
    little: bool,
}

impl Reader<'_> {
    fn take(&mut self, n: usize) -> Result<&[u8], &'static str> {
        let end = self
            .pos
            .checked_add(n)
            .filter(|&e| e <= self.data.len())
            .ok_or("truncated chunk")?;
        let bytes = &self.data[self.pos..end];
        self.pos = end;
        Ok(bytes)
    }

    fn uint(&mut self, size: usize) -> Result<u64, &'static str> {
        let little = self.little;
        let bytes = self.take(size)?;
        let fold = |acc: u64, b: &u8| (acc << 8) | u64::from(*b);
        Ok(if little {
            bytes.iter().rev().fold(0, fold)
        } else {
            bytes.iter().fold(0, fold)
        })
    }

    fn int(&mut self) -> Result<usize, &'static str> {
        usize::try_from(self.uint(self.sizes.int)?).map_err(|_| "count too large")
    }

    /// Copies `n` raw bytes to `out`
    fn copy(&mut self, n: usize, out: &mut Vec<u8>) -> Result<(), &'static str> {
        let bytes = self.take(n)?.to_vec();
        out.extend_from_slice(&bytes);
        Ok(())
    }

    /// Skips a string (size_t length, then that many bytes)
    fn skip_string(&mut self) -> Result<(), &'static str> {
        let len = usize::try_from(self.uint(self.sizes.size_t)?).map_err(|_| "string too large")?;
        self.take(len).map(|_| ())
    }

    fn copy_string(&mut self, out: &mut Vec<u8>) -> Result<(), &'static str> {
        let start = self.pos;
        self.skip_string()?;
        out.extend_from_slice(&self.data[start..self.pos]);
        Ok(())
    }
}

fn put_zero(out: &mut Vec<u8>, size: usize) {
    out.extend(std::iter::repeat_n(0, size));
}

fn function(r: &mut Reader, out: &mut Vec<u8>) -> Result<(), &'static str> {
    let s = r.sizes;
    r.skip_string()?; // source name: a null string when stripped
    put_zero(out, s.size_t);
    r.copy(2 * s.int + 4, out)?; // linedefined, lastlinedefined, nups, numparams, is_vararg, maxstacksize

    let start = r.pos;
    let code = r.int()?;
    r.pos = start;
    r.copy(s.int + code * s.instruction, out)?;

    let constants = r.int()?;
    out.extend_from_slice(&r.data[r.pos - s.int..r.pos]);
    for _ in 0..constants {
        let kind = r.take(1)?[0];
        out.push(kind);
        match kind {
            0 => {}
            1 => r.copy(1, out)?,
            3 => r.copy(s.number, out)?,
            4 => r.copy_string(out)?,
            _ => return Err("unknown constant type"),
        }
    }

    let protos = r.int()?;
    out.extend_from_slice(&r.data[r.pos - s.int..r.pos]);
    for _ in 0..protos {
        function(r, out)?;
    }

    // debug: line info, locals, upvalue names, each written as an empty list
    let lines = r.int()?;
    r.take(lines * s.int)?;
    let locals = r.int()?;
    for _ in 0..locals {
        r.skip_string()?;
        r.take(2 * s.int)?;
    }
    let upvalues = r.int()?;
    for _ in 0..upvalues {
        r.skip_string()?;
    }
    for _ in 0..3 {
        put_zero(out, s.int);
    }
    Ok(())
}
