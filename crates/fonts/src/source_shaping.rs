//! Shaping an explicitly selected source/document face, with OpenType features and UAX #9.
use harfrust::{BufferClusterLevel, Direction, ShapeOptions, ShaperData, UnicodeBuffer};
use skrifa::raw::TableProvider;
use skrifa::{FontRef, GlyphId};
use unicode_bidi::{Level, ParagraphBidiInfo};

#[derive(Clone, Debug)]
pub struct ShapedGlyph {
    pub id: GlyphId,
    pub position: [f64; 2],
    pub text: String,
}
#[derive(Clone, Debug)]
pub struct ShapedRun {
    pub glyphs: Vec<ShapedGlyph>,
    pub advance: f64,
}

/// Complex scripts/marks require shaping instead of independently encoding characters.
pub fn required(text: &str) -> bool {
    text.chars()
        .any(|c| matches!(u32::from(c), 0x0300..=0x036f | 0x0590..=0x0fff | 0x1780..=0x17ff | 0x200c..=0x200f | 0x202a..=0x202e | 0x2066..=0x2069))
}

/// Isolate ASCII numeric expressions (dates, decimal values and identifiers) so an
/// Arabic prefix cannot reverse their components. Returned ranges still refer to
/// the original logical text; directional controls are never stored in the PDF.
pub fn visual_runs(text: &str, rtl: Option<bool>) -> Vec<(std::ops::Range<usize>, bool)> {
    let rtl = rtl.unwrap_or_else(|| unicode_bidi::get_base_direction(text) == unicode_bidi::Direction::Rtl);
    let mut input = String::new();
    let mut offsets = Vec::new();
    let mut at = 0;
    while at < text.len() {
        let ch = text[at..].chars().next().unwrap();
        let mut end = at + ch.len_utf8();
        if ch.is_ascii_digit() {
            while end < text.len() && (text.as_bytes()[end].is_ascii_digit() || matches!(text.as_bytes()[end], b'-' | b'/' | b'.' | b':')) {
                end += 1;
            }
            while end > at + 1 && !text.as_bytes()[end - 1].is_ascii_digit() {
                end -= 1;
            }
        }
        let isolate = ch.is_ascii_digit() && text[at..end].bytes().any(|c| !c.is_ascii_digit());
        if isolate {
            input.push('\u{2066}');
            offsets.extend([None; 3]);
        }
        input.push_str(&text[at..end]);
        offsets.extend((at..end).map(Some));
        if isolate {
            input.push('\u{2069}');
            offsets.extend([None; 3]);
        }
        at = end;
    }
    let bidi = ParagraphBidiInfo::new(&input, Some(if rtl { Level::rtl() } else { Level::ltr() }));
    let (levels, runs) = bidi.visual_runs(0..input.len());
    runs.into_iter()
        .filter_map(|run| {
            let start = offsets[run.clone()].iter().flatten().next().copied()?;
            let end = offsets[run.clone()].iter().flatten().last().copied()? + 1;
            Some((start..end, levels[run.start].is_rtl()))
        })
        .collect()
}

pub fn shape(bytes: &[u8], text: &str) -> Result<ShapedRun, String> {
    shape_context(bytes, text, None)
}

/// Retain paragraph direction and joining context across font/style boundaries.
pub fn shape_context(bytes: &[u8], text: &str, context: Option<(bool, &str, &str)>) -> Result<ShapedRun, String> {
    if text.len() > 262_144 || text.contains(['\n', '\r']) {
        return Err("shape one bounded text row at a time".into());
    }
    if text.is_empty() {
        return Ok(ShapedRun { glyphs: Vec::new(), advance: 0.0 });
    }
    let face = FontRef::new(bytes).map_err(|e| e.to_string())?;
    if text.chars().any(|c| matches!(u32::from(c), 0x0600..=0x06ff | 0x0750..=0x077f | 0x08a0..=0x0fff | 0x1780..=0x17ff)) && face.gsub().is_err() {
        return Err("the source subset has no contextual substitution tables; select its full font".into());
    }
    let data = ShaperData::new(&face);
    let shaper = data.shaper(&face).build();
    let em = f64::from(shaper.units_per_em().max(1));
    let rtl = context.map_or_else(|| unicode_bidi::get_base_direction(text) == unicode_bidi::Direction::Rtl, |c| c.0);
    let runs = visual_runs(text, Some(rtl));
    let mut glyphs = Vec::new();
    let mut x = 0.0;
    for (run, rtl) in runs {
        let Some(input) = text.get(run.clone()) else { continue };
        let mut buffer = UnicodeBuffer::new();
        buffer.push_str(input);
        let before = format!("{}{}", context.map_or("", |c| c.1), &text[..run.start]);
        let after = format!("{}{}", &text[run.end..], context.map_or("", |c| c.2));
        buffer.set_pre_context(&before);
        buffer.set_post_context(&after);
        buffer.set_cluster_level(BufferClusterLevel::MonotoneGraphemes);
        buffer.set_direction(if rtl { Direction::RightToLeft } else { Direction::LeftToRight });
        buffer.guess_segment_properties();
        let output = shaper.shape(buffer, ShapeOptions::new());
        let mut starts: Vec<_> = output.glyph_infos().iter().map(|g| g.cluster as usize).collect();
        starts.sort_unstable();
        starts.dedup();
        let mut seen = std::collections::HashSet::new();
        for (g, p) in output.glyph_infos().iter().zip(output.glyph_positions()) {
            if g.glyph_id == 0 {
                return Err("the full font lacks a required contextual glyph; choose the complete source face".into());
            }
            let start = g.cluster as usize;
            let end = starts.get(starts.partition_point(|i| *i <= start)).copied().unwrap_or(input.len());
            let logical = if seen.insert(start) { input.get(start..end).unwrap_or_default().to_string() } else { String::new() };
            glyphs.push(ShapedGlyph {
                id: GlyphId::new(g.glyph_id),
                position: [x + f64::from(p.x_offset) / em, f64::from(p.y_offset) / em],
                text: logical,
            });
            x += f64::from(p.x_advance) / em;
        }
    }
    if !x.is_finite() || glyphs.len() > 65_534 {
        return Err("shaped row exceeds its geometry budget".into());
    }
    Ok(ShapedRun { glyphs, advance: x })
}

/// Shape-measured wrapping shared by generated PDF appearances. Break at whitespace;
/// keep combining marks with their base and never measure Arabic with Latin metrics.
pub fn wrap(bytes: &[u8], text: &str, width: f64) -> Result<Vec<String>, String> {
    if !width.is_finite() || width <= 0.0 || text.len() > 262_144 {
        return Err("invalid text layout budget".into());
    }
    let mut lines = Vec::new();
    for paragraph in text.replace("\r\n", "\n").replace('\r', "\n").split('\n') {
        let mut line = String::new();
        for word in paragraph.split_inclusive(char::is_whitespace) {
            let candidate = format!("{line}{word}");
            if !line.is_empty() && shape(bytes, candidate.trim_end())?.advance > width {
                lines.push(line.trim_end().to_string());
                line.clear();
            }
            if shape(bytes, word.trim_end())?.advance > width {
                use unicode_segmentation::UnicodeSegmentation;
                for cluster in word.graphemes(true) {
                    let candidate = format!("{line}{cluster}");
                    if !line.is_empty() && shape(bytes, &candidate)?.advance > width {
                        lines.push(std::mem::take(&mut line));
                    }
                    line.push_str(cluster);
                }
            } else {
                line.push_str(word);
            }
        }
        lines.push(line.trim_end().to_string());
        if lines.len() > 8192 {
            return Err("text box has too many lines".into());
        }
    }
    Ok(lines)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn arabic_static_faces_are_embeddable_and_keep_joining_context() {
        for bold in [false, true] {
            let Some(face) = crate::document_arabic_font_for_style(bold) else { return };
            crate::embedding::validate(face.bytes).unwrap();
            let whole = shape(face.bytes, "بب").unwrap();
            let first = shape_context(face.bytes, "ب", Some((true, "", "ب"))).unwrap();
            let second = shape_context(face.bytes, "ب", Some((true, "ب", ""))).unwrap();
            let combined: Vec<_> = second.glyphs.iter().chain(&first.glyphs).map(|g| g.id).collect();
            assert_eq!(whole.glyphs.iter().map(|g| g.id).collect::<Vec<_>>(), combined);
            assert!((whole.advance - first.advance - second.advance).abs() < 1e-9);
        }
    }

    #[test]
    fn arabic_dates_keep_their_latin_component_order() {
        let text = "هـ1448-05-09 اختبار";
        let runs = visual_runs(text, None);
        assert!(runs.iter().any(|(r, rtl)| &text[r.clone()] == "1448-05-09" && !rtl));
        let Some(face) = crate::document_arabic_font() else { return };
        let shaped = shape(face.bytes, text).unwrap();
        let visual: String = shaped.glyphs.iter().map(|g| g.text.as_str()).collect();
        assert!(visual.contains("1448-05-09"));
    }

    #[test]
    fn narrow_arabic_wrapping_preserves_combining_clusters_and_explicit_rows() {
        let Some(face) = crate::document_arabic_font() else { return };
        let text = "بَبَبَبَبَ";
        let rows = wrap(face.bytes, text, 0.9).unwrap();
        assert!(rows.len() > 1);
        assert_eq!(rows.concat(), text);
        assert!(rows.iter().all(|r| !r.starts_with('َ')));
        assert_eq!(wrap(face.bytes, "الأول\nالثاني", 100.0).unwrap().len(), 2);
    }
}
