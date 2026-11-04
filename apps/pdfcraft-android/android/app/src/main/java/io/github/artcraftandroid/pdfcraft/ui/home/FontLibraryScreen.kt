package io.github.artcraftandroid.pdfcraft.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.*

@Composable
fun FontLibraryScreen(vm:AppViewModel) {
    val manager=vm.fontLibrary;val c=pdfColors
    var license by remember {mutableStateOf(false)}
    LaunchedEffect(manager) {manager.refresh()}
    Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.font_library_core_title),color=c.toolAccent,fontSize=18.sp)
        Text(stringResource(R.string.font_library_core_body),color=c.textSecondary,fontSize=14.sp)
        Text(manager.catalog.joinToString(" · ") {it.family},color=c.text,fontSize=14.sp)
        Text(stringResource(R.string.font_library_count,manager.installed,manager.catalog.sumOf {it.fonts.size}),color=c.toolAccent)
        if(manager.working) {
            Text(manager.current.ifBlank {stringResource(R.string.font_working)},color=c.text)
            val progress=manager.progress
            if(progress==null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth())
                Text(stringResource(R.string.font_download_progress,(progress*100).toInt()),color=c.textSecondary)
            }
            TextButton(onClick=manager::cancel) {Text(stringResource(R.string.common_cancel),color=c.toolAccent)}
        }
        manager.problem?.let {Text(it,color=c.toolAccent)}
        manager.message?.let {Text(it,color=c.text)}
        Button(onClick={if(manager.licenseAccepted) manager.installCore() else license=true},enabled=!manager.working && vm.engine!=null,modifier=Modifier.fillMaxWidth(),
            colors=ButtonDefaults.buttonColors(containerColor=c.accent,contentColor=c.onAccent)) {
            Text(stringResource(if(manager.installed==30) R.string.font_library_verify else R.string.font_library_download))
        }
        TextButton(onClick={if(manager.licenseAccepted) manager.installCore() else license=true},enabled=!manager.working) {Text(stringResource(R.string.font_library_license),color=c.toolAccent)}
        HorizontalDivider(color=c.divider)
        Text(stringResource(R.string.font_library_import_title),color=c.toolAccent,fontSize=18.sp)
        Text(stringResource(R.string.font_library_import_body),color=c.textSecondary,fontSize=14.sp)
        OutlinedButton(onClick=manager::pick,enabled=!manager.working && vm.engine!=null,modifier=Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.font_library_import),color=c.toolAccent)
        }
        Text(stringResource(R.string.font_library_scope),color=c.textSecondary,fontSize=13.sp)
    }
    if(license) AlertDialog(onDismissRequest={license=false},containerColor=c.menu,
        title={Text(stringResource(R.string.font_library_license),color=c.text)},
        text={Column(Modifier.heightIn(max=350.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.font_library_terms),color=c.textSecondary)
            Text(manager.license,color=c.text,fontSize=12.sp)
        }},
        confirmButton={TextButton(onClick={license=false;if(!manager.licenseAccepted) manager.acceptAndInstall()}) {Text(stringResource(if(manager.licenseAccepted) R.string.common_ok else R.string.font_library_accept),color=c.toolAccent)}},
        dismissButton={TextButton(onClick={license=false}) {Text(stringResource(R.string.common_cancel),color=c.textSecondary)}})
}
