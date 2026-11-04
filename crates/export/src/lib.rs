//! pdfcraft-export — Export a PDF ▸ Word, HTML, RTF, Markdown (L4).
//!
//! The engine reduces each page to [`Page`]: paragraphs (text, box, size, bold/italic) and
//! images (encoded bytes and box), in reading order. The writers turn that into a flowing
//! document: headings are the paragraphs set larger than the body text, and a block that
//! continues another on its line (an italic word, a link) is a run of that block's paragraph.
//! Word keeps pages with
//! rotated text, or text overlapped by artwork or watermark-sized text, as positioned, editable
//! shapes to avoid reflowing overlays; other pages flow with images inline.

#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

mod pptx;
mod xlsx;
mod zip;

pub use pptx::pptx;
pub use xlsx::xlsx;
pub use zip::Zip;

mod as_docx;
mod as_html;
mod as_markdown;
mod as_rtf;
mod layout;

pub use as_docx::docx;
pub(crate) use as_docx::hex;
pub(crate) use as_docx::rgb8;
#[cfg(test)]
use as_docx::{MAX_MEDIA, docx_table};
pub(crate) use as_html::esc;
#[cfg(test)]
use as_html::{allowed, base64};
pub use as_html::{html, html_simple};
pub use as_markdown::markdown;
#[cfg(test)]
use as_markdown::{emphasis, paragraph};
pub use as_rtf::rtf;
pub use layout::{Block, Cell, Image, Page, Table};
pub(crate) use layout::{Item, items};
#[cfg(test)]
use layout::{LOOKUP_STEPS, MAX_COLS, body_size};
pub(crate) use layout::{Run, paragraphs, tables};

fn core_xml(title: &str) -> String {
    format!(
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>{}</dc:title></cp:coreProperties>",
        esc(title)
    )
}

#[cfg(test)]
mod tests;
