package io.github.artcraftandroid.pdfcraft.ui

/** Compact activity saved state; bitmaps and native document ids are never persisted. */
data class ScanSession(val shot:String?,val retake:Int?,val forImage:Boolean,val name:String,val pages:List<Page>,
    val searchable:Boolean=true,val forInsert:Boolean=false) {
    data class Page(val path:String,val corners:FloatArray,val found:FloatArray?,val filter:ScanFilter=ScanFilter.AutoColor,
        val strength:Int=filter.level,val rotation:Int=0)
    fun encode():String=buildString {
        append("pdfcraft-scan 2\nshot\t").append(shot.orEmpty()).append("\nretake\t").append(retake?.toString().orEmpty())
        append("\nimage\t").append(if(forImage)"1" else "0").append("\nname\t").append(name.replace('\n',' ').replace('\r',' ').replace('\t',' ').take(200))
        append("\nsearchable\t").append(if(searchable)"1" else "0").append("\ninsert\t").append(if(forInsert)"1" else "0").append('\n')
        pages.take(50).forEach {p ->
            append("page\t").append(p.path).append('\t').append(p.corners.joinToString(",")).append('\t').append(p.found?.joinToString(",").orEmpty())
            append('\t').append(p.filter.name).append('\t').append(p.strength).append('\t').append(p.rotation).append('\n')
        }
    }
    companion object {
        fun decode(text:String?):ScanSession? {
            if(text==null || text.length>128*1024) return null
            val lines=text.lines();val version=lines.firstOrNull()
            if(version !in listOf("pdfcraft-scan 1","pdfcraft-scan 2")) return null
            var shot:String?=null;var retake:Int?=null;var image=false;var name="";var searchable=true;var insert=false
            val pages=mutableListOf<Page>()
            for(line in lines.drop(1)) {
                val fields=line.split('\t');val value=fields.getOrNull(1).orEmpty()
                when(fields.first()) {
                    "shot" -> shot=value.ifEmpty {null}
                    "retake" -> retake=value.toIntOrNull()?.takeIf {it in 0..49}
                    "image" -> image=value=="1"
                    "name" -> name=value.take(200)
                    "searchable" -> searchable=value=="1"
                    "insert" -> insert=value=="1"
                    "page" -> {
                        if(value.isEmpty() || pages.size>=50) continue
                        val corners=corners(fields.getOrNull(2)) ?: continue
                        val filter=if(version=="pdfcraft-scan 1") ScanFilter.AutoColor else ScanFilter.entries.firstOrNull {it.name==fields.getOrNull(4)} ?: continue
                        val strength=if(version=="pdfcraft-scan 1") filter.level else fields.getOrNull(5)?.toIntOrNull()?.takeIf {it in 0..100} ?: continue
                        val rotation=if(version=="pdfcraft-scan 1") 0 else fields.getOrNull(6)?.toIntOrNull()?.takeIf {it in listOf(0,90,180,270)} ?: continue
                        pages.add(Page(value,corners,corners(fields.getOrNull(3)),filter,strength,rotation))
                    }
                }
            }
            return ScanSession(shot,retake,image,name,pages,searchable,insert)
        }
        private fun corners(value:String?):FloatArray? {
            val values=value?.split(',')?.map {it.toFloatOrNull() ?: return null}?.toFloatArray() ?: return null
            return values.takeIf(::validScanCorners)
        }
    }
}
