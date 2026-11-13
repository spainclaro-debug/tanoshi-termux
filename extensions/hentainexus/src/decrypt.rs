//! Reader-payload decryption for HentaiNexus.
//!
//! Ported from the Keiyoushi extension's `Utils.decryptData`. The site base64
//! encodes a blob whose first 14 bytes are XOR-masked with the hostname; the
//! next 64 bytes seed an RC4-like key schedule, and the remainder is the
//! ciphertext. The prime-index step selects one of eight stride values.

use anyhow::{Result, anyhow, bail};
use base64::Engine as _;

/// Hostname whose bytes mask the first 14 bytes of the payload.
const HOSTNAME: &[u8] = b"hentainexus.com";
/// XOR polynomial used by the CRC-like prime index derivation.
const PRIME_INDEX_XOR_MASK: u8 = 12;
/// Prime strides; the derived index selects one.
const PRIME_NUMBERS: [u8; 8] = [2, 3, 5, 7, 11, 13, 17, 19];
const KEY_STREAM_LEN: usize = 64;

/// Decrypt a `initReader("...")` payload into the reader JSON string.
pub fn decrypt_payload(encoded: &str) -> Result<String> {
    let normalized: String = encoded
        .chars()
        .filter(|c| !c.is_whitespace())
        .collect();

    let raw = base64::engine::general_purpose::STANDARD
        .decode(normalized.as_bytes())
        .map_err(|error| anyhow!("invalid base64 reader payload: {error}"))?;

    if raw.len() <= KEY_STREAM_LEN {
        bail!("reader payload too short ({} bytes)", raw.len());
    }
    if raw.len() < HOSTNAME.len() {
        bail!("reader payload shorter than the hostname mask");
    }

    let mut data = raw;
    // Unmask the head with the hostname.
    for (slot, mask) in data.iter_mut().zip(HOSTNAME.iter()) {
        *slot ^= *mask;
    }

    let key_stream = &data[..KEY_STREAM_LEN];
    let ciphertext = &data[KEY_STREAM_LEN..];

    let mut digest: [u8; 256] = [0; 256];
    for (index, slot) in digest.iter_mut().enumerate() {
        *slot = index as u8;
    }

    // Derive which prime stride to use.
    let mut prime_index: u8 = 0;
    for value in key_stream {
        prime_index ^= *value;
        for _ in 0..8 {
            prime_index = if prime_index & 1 != 0 {
                (prime_index >> 1) ^ PRIME_INDEX_XOR_MASK
            } else {
                prime_index >> 1
            };
        }
    }
    prime_index &= 7;

    // Key-scheduling (RC4-like).
    let mut key: u8 = 0;
    for i in 0..256 {
        key = key
            .wrapping_add(digest[i])
            .wrapping_add(key_stream[i % KEY_STREAM_LEN]);
        digest.swap(i, key as usize);
    }

    let stride = PRIME_NUMBERS[prime_index as usize];
    let mut k: u8 = 0;
    let mut n: u8 = 0;
    let mut p: u8 = 0;
    let mut xor_key: u8 = 0;
    let mut out = String::with_capacity(ciphertext.len());

    for byte in ciphertext {
        k = k.wrapping_add(stride);
        n = p.wrapping_add(digest[n.wrapping_add(digest[k as usize]) as usize]);
        p = p.wrapping_add(k).wrapping_add(digest[k as usize]);
        digest.swap(k as usize, n as usize);

        let index = n.wrapping_add(digest[k
            .wrapping_add(digest[xor_key.wrapping_add(p) as usize])
            as usize]) as usize;
        xor_key = digest[index];

        out.push((*byte ^ xor_key) as char);
    }

    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_short_payload() {
        assert!(decrypt_payload("AAAA").is_err());
    }
}
