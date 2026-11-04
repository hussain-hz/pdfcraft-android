package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Font imports are data only: TTF/OTF or a bounded ZIP. Archive paths are never destinations. */
suspend fun copyFontImport(input:InputStream,file:File) {
    file.outputStream().use {out ->
        val buffer=ByteArray(8192);var total=0L
        while(true) {coroutineContext.ensureActive();val n=input.read(buffer);if(n<0) break
            total+=n;require(total<=64L*1024*1024) {"Font import exceeds 64 MB"};out.write(buffer,0,n)
        }
    }
}

suspend fun stageFontImport(source:File,directory:File):List<File> {
    var total=0L
    suspend fun stage(input:InputStream):File {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) {coroutineContext.ensureActive();val n=input.read(buffer);if(n<0) break
            require(out.size()+n<=MAX_DOCUMENT_FONT_BYTES) {"Font exceeds 16 MB"}
            total+=n;require(total<=128L*1024*1024) {"Expanded fonts exceed 128 MB"};out.write(buffer,0,n)
        }
        val bytes=out.toByteArray();require(bytes.size>=12) {"Invalid font"}
        val signature=bytes.copyOfRange(0,4)
        val extension=when {
            signature.contentEquals(byteArrayOf(0,1,0,0)) || signature.contentEquals("true".toByteArray()) -> "ttf"
            signature.contentEquals("OTTO".toByteArray()) -> "otf"
            else -> error("Import a TTF or OTF font")
        }
        return File(directory,"import-${fontDigest(bytes)}.$extension").also {atomicFontWrite(it,bytes)}
    }
    val magic=ByteArray(4);val read=source.inputStream().use {it.read(magic)}
    if(read<2 || magic[0]!=80.toByte() || magic[1]!=75.toByte()) return listOf(source.inputStream().use {stage(it)})
    return ZipFile(source).use {zip ->
        val entries=zip.entries();val files=mutableListOf<File>();var count=0
        while(entries.hasMoreElements()) {
            coroutineContext.ensureActive();require(++count<=512) {"Font ZIP has too many entries"}
            val entry=entries.nextElement();val path=entry.name.replace('\\','/')
            require(!path.startsWith('/') && ':' !in path && path.split('/').none {it==".."}) {"Unsafe font ZIP path"}
            if(entry.isDirectory || !(path.endsWith(".ttf",true) || path.endsWith(".otf",true))) continue
            require(entry.size<=MAX_DOCUMENT_FONT_BYTES) {"Font exceeds 16 MB"}
            files.add(zip.getInputStream(entry).use {stage(it)})
        }
        require(files.isNotEmpty()) {"No TTF or OTF fonts in ZIP"};files.distinctBy {it.name}
    }
}
