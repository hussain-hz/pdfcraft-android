package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.engine.*
import io.github.artcraftandroid.pdfcraft.ui.viewer.*
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FontLibraryTest {
    @Test fun windowsPackIsVerifiedResumableAndKeepsOriginalInstaller()=runBlocking {
        val dir=Files.createTempDirectory("core-font-test").toFile()
        try {
            val archive="original installer".toByteArray();val font="font bytes".toByteArray()
            val face=CoreFontFace("Arial.TTF",font.size,fontDigest(font),"ArialMT")
            val pack=CoreFontPackage("arial32.exe","Arial",archive.size,fontDigest(archive),listOf(face))
            var transfers=0
            val transfer:FontTransfer={_,_,_,_,_->transfers++;archive}
            val extract:CoreFontExtractor={_,_->listOf(face.name to font)}
            cacheCoreFontPackage(pack,dir,transfer=transfer,extractor=extract)
            assertTrue(corePackageInstalled(pack,dir));assertArrayEquals(archive,File(dir,"core-font-packages/arial32.exe").readBytes())
            cacheCoreFontPackage(pack,dir,transfer={_,_,_,_,_->error("offline")},extractor={_,_->error("already installed")})
            pack.installedFile(dir,face).writeText("corrupt")
            cacheCoreFontPackage(pack,dir,transfer={_,_,_,_,_->error("cached original")},extractor=extract)
            assertEquals(1,transfers);assertTrue(corePackageInstalled(pack,dir))
            pack.installedFile(dir,face).delete()
            try {cacheCoreFontPackage(pack,dir,transfer=transfer,extractor={_,_->listOf(face.name to "bad".toByteArray())});fail("reject bad extracted font")}
            catch(_:IllegalArgumentException) {}
            assertFalse(pack.installedFile(dir,face).exists())
        } finally {dir.deleteRecursively()}
    }
    @Test fun mirrorRedirectsStayOnHttpsSourceForgeAndCoreFonts() {
        assertTrue(trustedCoreFontUrl(URI("https://downloads.sourceforge.net/project/corefonts/the%20fonts/final/arial32.exe")))
        assertTrue(trustedCoreFontUrl(URI("https://netix.dl.sourceforge.net/project/corefonts/the%20fonts/final/arial32.exe")))
        listOf("http://downloads.sourceforge.net/project/corefonts/x","https://evil.dl.sourceforge.net.example/project/corefonts/x",
            "https://downloads.sourceforge.net/project/other/x","https://name@downloads.sourceforge.net/project/corefonts/x").forEach {assertFalse(trustedCoreFontUrl(URI(it)))}
    }
    @Test fun fontZipStagesOnlyFontsAndRejectsTraversalBeforePublishing()=runBlocking {
        val dir=Files.createTempDirectory("font-zip").toFile()
        try {
            val source=File(dir,"fonts.zip");val stage=File(dir,"stage").apply {mkdirs()}
            fun zip(name:String) {ZipOutputStream(source.outputStream()).use {z ->
                z.putNextEntry(ZipEntry(name));z.write(byteArrayOf(0,1,0,0)+ByteArray(20));z.closeEntry()
                z.putNextEntry(ZipEntry("LICENSE.txt"));z.write("terms".toByteArray());z.closeEntry()
            }}
            zip("family/Font.ttf");val files=stageFontImport(source,stage)
            assertEquals(1,files.size);assertTrue(files.single().name.startsWith("import-"))
            zip("../escape.ttf")
            try {stageFontImport(source,stage);fail("reject traversal")} catch(_:IllegalArgumentException) {}
            assertFalse(File(dir,"escape.ttf").exists())
        } finally {dir.deleteRecursively()}
    }
    @Test fun fontFamiliesAreSearchableAndSystemFacesComeFirst() {
        fun face(family:String,name:String)=StoredDocumentFont(File("/$name.ttf"),family,name)
        val phone=listOf(face("Noto Sans","NotoSans-Bold"),face("Noto Sans","NotoSans-Regular"))
        val added=listOf(face("Arial","ArialMT"),face("Noto Sans","NotoSans-Regular"))
        val groups=groupInstalledFonts(phone,added,"",0)
        assertTrue(groups.first().system);assertEquals(2,groups.first().fonts.size);assertFalse(groups.last().system)
        assertEquals("Arial",groupInstalledFonts(phone,added,"arial",0).single().family)
        assertTrue(groupInstalledFonts(phone,added,"Arial",1).isEmpty())
        assertEquals(1,groupInstalledFonts(phone,added,"",2).size)
    }
    @Test fun newTextUsesNearestStyleAndCommercialNamesAreNotStandardAliases() {
        val a=Paragraph(0,1,"A",floatArrayOf(10f,10f,100f,30f),"ArialMT",18f,true,false,"#123456")
        val b=a.copy(number=2,text="B",box=floatArrayOf(10f,200f,100f,230f),font="TimesNewRomanPSMT")
        assertSame(b,nearestTextStyle(listOf(a,b),PagePoint(0,30f,250f)))
        assertNull(nearestTextStyle(listOf(a,b),PagePoint(1,30f,250f)))
        assertNull(exactStandardFont("ArialMT"));assertNull(exactStandardFont("TimesNewRomanPSMT"))
        assertEquals("times",exactStandardFont("Times-Roman"));assertEquals("helvetica",exactStandardFont("Helvetica-Bold"))
    }
}
