//! Text encoding as Python's `str.encode("cp949", errors="replace")` did it.

use encoding_rs::{EUC_KR, EncoderResult};

/// `text` in CP949; a character CP949 lacks becomes `?`
pub fn cp949(text: &str) -> Vec<u8> {
    let mut encoder = EUC_KR.new_encoder();
    let mut out = Vec::with_capacity(text.len() * 2);
    let mut buf = [0u8; 64];
    let mut rest = text;
    loop {
        let (result, read, written) =
            encoder.encode_from_utf8_without_replacement(rest, &mut buf, true);
        out.extend_from_slice(&buf[..written]);
        rest = &rest[read..];
        match result {
            EncoderResult::InputEmpty => return out,
            EncoderResult::OutputFull => {}
            EncoderResult::Unmappable(_) => out.push(b'?'),
        }
    }
}

/// `bytes` with every `from` byte replaced by `to`
pub fn replace(bytes: &[u8], from: u8, to: u8) -> Vec<u8> {
    bytes
        .iter()
        .map(|&b| if b == from { to } else { b })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ascii_and_korean_encode_and_unmappable_is_question_mark() {
        assert_eq!(cp949("Hat"), b"Hat");
        assert_eq!(cp949("가"), vec![0xb0, 0xa1]);
        assert_eq!(cp949("a😀b"), b"a?b");
    }
}
