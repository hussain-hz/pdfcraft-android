package io.github.artcraftandroid.pdfcraft.engine

/** Unknown/structural tools conservatively invalidate every metadata collection. */
enum class MetadataRefresh { All, Comments, Content }
fun metadataFor(tool: String): MetadataRefresh = when (tool) {
    "page_add_text", "page_add_image", "content_update", "content_delete", "text_edit", "image_edit" -> MetadataRefresh.Content
    "comment_edit", "comment_add", "comment_delete", "fill_sign_add" -> MetadataRefresh.Comments
    else -> MetadataRefresh.All
}
