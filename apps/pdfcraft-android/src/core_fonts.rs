//! Decode original Core Fonts installers as data, never as executable programs.
use std::io::{Cursor, Read};

use crate::MobileError;

#[derive(Debug, uniffi::Record)]
pub struct FontArchiveFile {
    pub name: String,
    pub bytes: Vec<u8>,
}

/// Android verifies the pinned installer SHA-256 before calling this and verifies each
/// returned face afterwards. Only exact allowlisted basenames are returned; no paths are written.
#[uniffi::export]
pub fn extract_core_font_archive(bytes: Vec<u8>, names: Vec<String>) -> Result<Vec<FontArchiveFile>, MobileError> {
    extract(&bytes, &names).map_err(|e| MobileError::Command(format!("font archive: {e}")))
}

fn extract(bytes: &[u8], names: &[String]) -> std::io::Result<Vec<FontArchiveFile>> {
    let bad = || std::io::Error::new(std::io::ErrorKind::InvalidData, "invalid or oversized cabinet");
    if bytes.len() > 2 * 1024 * 1024
        || names.is_empty()
        || names.len() > 30
        || names.iter().any(|n| !n.to_ascii_lowercase().ends_with(".ttf") || n.contains(['/', '\\', ':']) || n.contains("..") || n.len() > 80)
    {
        return Err(bad());
    }
    // The PE stub also contains the MSCF string: require a bounded 1.3 cabinet header.
    let offset = bytes
        .windows(36)
        .position(|h| {
            h.starts_with(b"MSCF") && h[24..26] == [3, 1] && h[30..32] == [0, 0] && {
                let size = u32::from_le_bytes([h[8], h[9], h[10], h[11]]) as usize;
                size >= 36 && size <= bytes.len()
            }
        })
        .ok_or_else(bad)?;
    let size = u32::from_le_bytes(bytes[offset + 8..offset + 12].try_into().map_err(|_| bad())?) as usize;
    let end = offset.checked_add(size).filter(|end| *end <= bytes.len()).ok_or_else(bad)?;
    let mut cab = cab::Cabinet::new(Cursor::new(&bytes[offset..end]))?;
    let mut total = 0usize;
    let mut result = Vec::new();
    for name in names {
        let entry = cab.get_file_entry(name).ok_or_else(bad)?;
        let size = entry.uncompressed_size() as usize;
        if size == 0 || size > 16 * 1024 * 1024 {
            return Err(bad());
        }
        total = total.checked_add(size).filter(|v| *v <= 32 * 1024 * 1024).ok_or_else(bad)?;
        let mut font = Vec::with_capacity(size);
        cab.read_file(name)?.take(size as u64 + 1).read_to_end(&mut font)?;
        if font.len() != size {
            return Err(bad());
        }
        result.push(FontArchiveFile { name: name.clone(), bytes: font });
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn embedded_cabinet_returns_only_requested_files() -> Result<(), Box<dyn std::error::Error>> {
        let mut builder = cab::CabinetBuilder::new();
        let folder = builder.add_folder(cab::CompressionType::MsZip);
        folder.add_file("face.ttf");
        folder.add_file("setup.exe");
        let mut writer = builder.build(Cursor::new(Vec::new()))?;
        while let Some(mut file) = writer.next_file()? {
            file.write_all(b"sample")?;
        }
        let mut data = b"MZ installer stub MSCF fake".to_vec();
        data.extend(writer.finish()?.into_inner());
        let out = extract(&data, &["face.ttf".into()])?;
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].bytes, b"sample");
        assert!(extract(&data, &["../face.ttf".into()]).is_err());
        assert!(extract(&data, &["missing.ttf".into()]).is_err());
        data.truncate(data.len() - 8);
        assert!(extract(&data, &["face.ttf".into()]).is_err());
        Ok(())
    }
}
