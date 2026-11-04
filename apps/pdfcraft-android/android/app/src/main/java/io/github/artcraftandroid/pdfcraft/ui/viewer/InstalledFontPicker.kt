package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class InstalledFontGroup(val family:String,val system:Boolean,val fonts:List<StoredDocumentFont>)

internal fun groupInstalledFonts(phone:List<StoredDocumentFont>,extra:List<StoredDocumentFont>,query:String,filter:Int):List<InstalledFontGroup> =
    installedDocumentFonts(phone,extra).filter {choice ->
        (filter==0 || choice.system==(filter==1)) && (query.isBlank() ||
            choice.font.family.contains(query.trim(),true) || choice.font.face.contains(query.trim(),true))
    }.groupBy {it.system to it.font.family}.map {(key,choices) -> InstalledFontGroup(key.second,key.first,choices.map {it.font})}

@Composable
internal fun ColumnScope.InstalledFontPicker(vm:AppViewModel,doc:OpenDocument,manager:DocumentFonts) {
    val c=pdfColors;val target=manager.target ?: return
    var query by remember(target) {mutableStateOf("")}
    var filter by remember(target) {mutableIntStateOf(0)}
    var expanded by remember(target) {mutableStateOf<String?>(null)}
    val groups=remember(manager.phone,manager.stored,query,filter) {groupInstalledFonts(manager.phone,manager.stored,query,filter)}
    val current=editorFaceKey(target.para.font)
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Icon(LucideIcons.Check,null,tint=c.toolAccent,modifier=Modifier.size(18.dp))
        Text(stringResource(R.string.font_current),color=c.toolAccent,fontSize=12.sp)
        Text(target.para.font.substringAfterLast('+'),color=c.text,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
    }
    OutlinedTextField(value=query,onValueChange={query=it},singleLine=true,modifier=Modifier.fillMaxWidth(),
        placeholder={Text(stringResource(R.string.font_search),fontSize=13.sp)},
        leadingIcon={Icon(LucideIcons.Search,null,tint=c.icon,modifier=Modifier.size(18.dp))})
    LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        items(listOf(R.string.font_filter_all,R.string.font_filter_system,R.string.font_filter_added).withIndex().toList()) {(i,label) ->
            FilterChip(selected=filter==i,onClick={filter=i},label={Text(stringResource(label),fontSize=12.sp)})
        }
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max=280.dp).weight(1f,fill=false),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(manager.installingDefaults) item {Text(stringResource(R.string.font_auto_installing),color=c.toolAccent,fontSize=12.sp)}
        if(manager.defaultsFailed) item {Text(stringResource(R.string.font_auto_retry_hint),color=c.textSecondary,fontSize=12.sp)}
        if(groups.isEmpty()) item {Text(stringResource(if(query.isBlank()) R.string.font_empty else R.string.font_no_matches),color=c.textSecondary,fontSize=13.sp)}
        groups.forEachIndexed {index,group ->
            if(index==0 || groups[index-1].system!=group.system) item("heading-${group.system}") {
                Text(stringResource(if(group.system) R.string.font_phone else R.string.font_installed_extra),color=c.toolAccent,fontSize=12.sp)
            }
            val key="${group.system}/${group.family}"
            item(key) {
                val selected=group.fonts.any {editorFaceKey(it.face)==current}
                val previewFace=group.fonts.firstOrNull {editorFaceKey(it.face)==current} ?: group.fonts.firstOrNull {it.face.endsWith("Regular",true) || editorFaceKey(it.face)==editorFaceKey(it.family) || editorFaceKey(it.face)==editorFaceKey(it.family)+"mt"} ?: group.fonts.first()
                val preview by produceState<FontFamily?>(null,previewFace.file.path) {
                    value=withContext(Dispatchers.IO) {runCatching {FontFamily(android.graphics.Typeface.createFromFile(previewFace.file))}.getOrNull()}
                }
                Surface(shape=RoundedCornerShape(14.dp),color=if(selected) c.accent.copy(alpha=.1f) else c.surface) {
                    Column {
                        Row(Modifier.fillMaxWidth().clickable {
                            if(group.fonts.size==1) manager.choose(group.fonts.first()) else expanded=if(expanded==key)null else key
                        }.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(group.family,color=c.text,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal,fontSize=15.sp)
                                if(group.fonts.size==1) Text(fontStyleLabel(group.fonts.first()),color=c.textSecondary,fontSize=11.sp)
                                Text("أبجد Aa",fontFamily=preview ?: FontFamily.Default,color=c.textSecondary,fontSize=17.sp,maxLines=1)
                            }
                            if(selected) Icon(LucideIcons.Check,null,tint=c.toolAccent,modifier=Modifier.size(18.dp))
                            if(group.fonts.size>1) Icon(if(expanded==key) LucideIcons.ChevronUp else LucideIcons.ChevronDown,
                                stringResource(R.string.font_expand_family,group.family),tint=c.icon,modifier=Modifier.size(18.dp))
                        }
                        if(expanded==key) group.fonts.forEach {font ->
                            val isCurrent=editorFaceKey(font.face)==current
                            TextButton(onClick={manager.choose(font)},modifier=Modifier.fillMaxWidth().padding(horizontal=8.dp)) {
                                Text(fontStyleLabel(font),color=if(isCurrent)c.toolAccent else c.text,fontSize=13.sp,modifier=Modifier.weight(1f))
                                if(isCurrent) Icon(LucideIcons.Check,null,tint=c.toolAccent,modifier=Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
        if(!inlineRtl(target.draft)) item {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                listOf("helvetica" to R.string.edit_font_sans,"times" to R.string.edit_font_serif,"courier" to R.string.edit_font_mono).forEach {(font,label) ->
                    TextButton(onClick={manager.standard(doc,target.para,font)}) {Text(stringResource(label),color=c.text,fontSize=12.sp)}
                }
            }
        }
        item {HorizontalDivider(color=c.divider)
            TextButton(onClick=manager::pick,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.font_import),color=c.toolAccent)}
            TextButton(onClick=manager::downloads,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.font_downloads),color=c.toolAccent)}
        }
    }
}

@Composable
private fun fontStyleLabel(font:StoredDocumentFont):String {
    val key=editorFaceKey(font.face)
    val family=editorFaceKey(font.family)
    val label=when {
        listOf("semibold","demibold","light","medium","thin","black").any {it in key} -> null
        "bold" in key && ("italic" in key || "oblique" in key) -> R.string.font_style_bold_italic
        "bold" in key -> R.string.font_bold
        "italic" in key || "oblique" in key -> R.string.font_style_italic
        "regular" in key || key==family || key==family+"mt" || key==family+"psmt" -> R.string.font_regular
        else -> null
    }
    return label?.let {stringResource(it)} ?: font.face
}
