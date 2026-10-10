package cn.a10miaomiao.bilidown.ui.page

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import cn.a10miaomiao.bilidown.BiliDownApp
import cn.a10miaomiao.bilidown.common.BiliDownFile
import cn.a10miaomiao.bilidown.common.BiliDownOutFile
import cn.a10miaomiao.bilidown.common.FileNameUtils
import cn.a10miaomiao.bilidown.common.BiliDownUtils
import cn.a10miaomiao.bilidown.common.datastore.DataStoreKeys
import cn.a10miaomiao.bilidown.common.datastore.rememberDataStorePreferencesFlow
import cn.a10miaomiao.bilidown.common.file.MiaoDocumentFile
import cn.a10miaomiao.bilidown.common.file.MiaoJavaFile
import cn.a10miaomiao.bilidown.common.molecule.collectAction
import cn.a10miaomiao.bilidown.common.molecule.rememberPresenter
import cn.a10miaomiao.bilidown.db.dao.OutRecord
import cn.a10miaomiao.bilidown.entity.DownloadInfo
import cn.a10miaomiao.bilidown.entity.BiliDownloadEntryAndPathInfo
import cn.a10miaomiao.bilidown.entity.BiliAppInfo
import cn.a10miaomiao.bilidown.entity.DownloadItemInfo
import cn.a10miaomiao.bilidown.entity.DownloadType
import cn.a10miaomiao.bilidown.service.BiliDownService
import cn.a10miaomiao.bilidown.shizuku.localShizukuPermission
import cn.a10miaomiao.bilidown.state.TaskStatus
import cn.a10miaomiao.bilidown.ui.BiliDownScreen
import cn.a10miaomiao.bilidown.ui.components.DownloadDetailItem
import cn.a10miaomiao.bilidown.ui.components.DownloadListItem
import cn.a10miaomiao.bilidown.ui.components.FileNameInputDialog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.ui.unit.dp


data class DownloadDetailPageState(
    val detailInfo: DownloadInfo?,
    val outRecordMap: Map<String, OutRecord>,
)

sealed class DownloadDetailPageAction {
    class Export(
        val entryDirPath: String,
        val outFile: BiliDownOutFile,
        val outputType: Int,
    ): DownloadDetailPageAction()

    class AddTask(
        val entryDirPath: String,
        val outFilePath: String,
        val title: String,
        val cover: String,
        val outputType: Int,
    ): DownloadDetailPageAction()

    class MigrateCache(
        val sourcePackageName: String,
        val targetPackageName: String,
        val useZeroWidthPath: Boolean,
        val targetEntryTemplate: String,
    ): DownloadDetailPageAction()

    class DeleteCache(
        val entryDirPath: String,
        val sourcePackageName: String,
        val useZeroWidthPath: Boolean,
    ): DownloadDetailPageAction()
}

@Composable
fun DownloadDetailPagePresenter(
    context: Context,
    packageName: String,
    dirPath: String,
    navController: NavHostController,
    action: Flow<DownloadDetailPageAction>,
): DownloadDetailPageState {
    var detailInfo by remember {
        mutableStateOf<DownloadInfo?>(null)
    }
    var path by remember {
        mutableStateOf("")
    }
    val outRecordMap = remember {
        mutableStateMapOf<String, OutRecord>()
    }
    LaunchedEffect(packageName, dirPath) {
        val biliDownService = BiliDownService.getService(context)
        val appState = (context.applicationContext as BiliDownApp).state
        val shizukuState = appState.shizukuState.value
        val biliDownFile = BiliDownFile(context, packageName, shizukuState.isEnabled)
//        val list = mutableListOf<DownloadInfo>()
        val dirFile = if (dirPath.startsWith("content://")) {
            MiaoDocumentFile(
                context,
                DocumentFile.fromTreeUri(
                    context,
                    Uri.parse(dirPath)
                )!!
            )
        } else {
            MiaoJavaFile(File(dirPath))
        }
        val list = biliDownFile.readDownloadDirectory(dirFile)
        val items = mutableListOf<DownloadItemInfo>()
        var isCompleted = true
        list.forEach {
            val biliEntry = it.entry
            var indexTitle = ""
            var itemTitle = ""
            var id = it.entry.avid ?: 0L
            var cid = 0L
            var epid = 0L
            var type = DownloadType.VIDEO
            val page = biliEntry.page_data
            if (page != null) {
                id = biliEntry.avid ?: page.cid
                indexTitle = page.download_title ?: page.part ?: "${page.page}P"
                cid = page.cid
                type = DownloadType.VIDEO
                itemTitle = biliEntry.title
            }
            val ep = biliEntry.ep
            val source = biliEntry.source
            if (ep != null && source != null) {
                id = biliEntry.season_id?.toLongOrNull() ?: biliEntry.ep?.episode_id ?: 0L
                indexTitle = ep.index_title
                epid = ep.episode_id
                cid = source.cid
                type = DownloadType.BANGUMI
                itemTitle = ep.index + ep.index_title
            }
            val item = DownloadItemInfo(
                dir_path = it.entryDirPath,
                media_type = biliEntry.media_type,
                has_dash_audio = biliEntry.has_dash_audio,
                is_completed = biliEntry.is_completed,
                total_bytes = biliEntry.total_bytes,
                downloaded_bytes = biliEntry.downloaded_bytes,
                title = itemTitle,
                cover = biliEntry.cover,
                id = id,
                type = type,
                cid = cid,
                epid = epid,
                index_title = indexTitle,
            )
            items.add(item)
            if (!item.is_completed) {
                isCompleted = false
            }
        }
        if (items.isNotEmpty()) {
            val paths = items.map {
                it.dir_path
            }.toTypedArray()
            val records = biliDownService.getRecordList(paths)
            outRecordMap.clear()
            records.filter { it.type == OutRecord.TYPE_MP4 }.forEach {
                outRecordMap[it.entryDirPath] = it
            }
        }
        if (list.isEmpty()) {
            detailInfo = null
        } else {
            val biliEntry = list[0].entry
            val item = items[0]
            detailInfo = DownloadInfo(
                dir_path = list[0].pageDirPath,
                media_type = biliEntry.media_type,
                has_dash_audio = biliEntry.has_dash_audio,
                is_completed = isCompleted,
                total_bytes = biliEntry.total_bytes,
                downloaded_bytes = biliEntry.downloaded_bytes,
                title = biliEntry.title,
                cover = biliEntry.cover,
                cid = item.cid,
                id = item.id,
                type = item.type,
                items = items
            )
        }
    }
    action.collectAction {
        when (it) {
            is DownloadDetailPageAction.Export -> {
                val biliDownService = BiliDownService.getService(context)
                val isSuccess = biliDownService.exportBiliVideo(
                    it.entryDirPath,
                    it.outFile.file,
                    it.outputType,
                )
                if (isSuccess) {
                    navController.navigate(BiliDownScreen.Progress.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }

            is DownloadDetailPageAction.AddTask -> {
                val biliDownService = BiliDownService.getService(context)
                biliDownService.addTask(
                    it.entryDirPath,
                    it.outFilePath,
                    it.title,
                    it.cover,
                    it.outputType,
                )
            }

            is DownloadDetailPageAction.MigrateCache -> {
                val biliDownService = BiliDownService.getService(context)
                val queued = biliDownService.enqueueCacheMigrationBatch(
                    listOf(dirPath),
                    it.sourcePackageName,
                    it.targetPackageName,
                    it.useZeroWidthPath,
                    it.targetEntryTemplate,
                )
                if (queued) {
                    navController.navigate(BiliDownScreen.Progress.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }

            is DownloadDetailPageAction.DeleteCache -> {
                val biliDownService = BiliDownService.getService(context)
                if (biliDownService.deleteCacheEntry(
                        it.entryDirPath,
                        it.sourcePackageName,
                        it.useZeroWidthPath,
                    )) {
                    outRecordMap.clear()
                    detailInfo = null
                    android.widget.Toast.makeText(context, "视频缓存已删除", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    return DownloadDetailPageState(
        detailInfo,
        outRecordMap,
    )
}

@Composable
fun DownloadDetailPage(
    navController: NavHostController,
    packageName: String,
    dirPath: String,
) {
    val context = LocalContext.current
    val appState = (context.applicationContext as BiliDownApp).state
    val taskStatus by appState.taskStatus.collectAsState()
    val scope = rememberCoroutineScope()
    val enabledZeroWidthPath by rememberDataStorePreferencesFlow(
        context,
        DataStoreKeys.enabledZeroWidthPath,
        true,
    ).collectAsState(true)
    val (state, channel) = rememberPresenter(listOf(packageName, dirPath)) {
        DownloadDetailPagePresenter(context, packageName, dirPath, navController, it)
    }
    var selectedItem by remember { mutableStateOf<DownloadItemInfo?>(null) }
    var selectedOutputType by remember { mutableIntStateOf(OutRecord.TYPE_MP4) }
    var showBatchDialog by remember { mutableStateOf(false) }
    var showMigrateDialog by remember { mutableStateOf(false) }
    var pendingMigrationTarget by remember { mutableStateOf<BiliAppInfo?>(null) }
    var migrationTemplateVideos by remember { mutableStateOf(emptyList<BiliDownloadEntryAndPathInfo>()) }
    var showMigrationTemplatePicker by remember { mutableStateOf(false) }
    var selectedMigrationTemplate by remember { mutableStateOf<BiliDownloadEntryAndPathInfo?>(null) }
    var deletingItem by remember { mutableStateOf<DownloadItemInfo?>(null) }
    var targetApps by remember { mutableStateOf(emptyList<BiliAppInfo>()) }
    var batchMp4 by remember { mutableStateOf(true) }
    var batchMp3 by remember { mutableStateOf(false) }

    LaunchedEffect(packageName) {
        val pm = context.packageManager
        targetApps = BiliDownUtils.biliAppList
            .filter { it.packageName != packageName }
            .mapNotNull { app ->
                val installed = try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(app.packageName, 0)
                    }
                } catch (_: Exception) {
                    null
                }
                app.takeIf { installed != null }
            }
    }

    LaunchedEffect(pendingMigrationTarget, enabledZeroWidthPath) {
        val target = pendingMigrationTarget ?: return@LaunchedEffect
        migrationTemplateVideos = runCatching {
            val shizukuEnabled = (context.applicationContext as BiliDownApp).state.shizukuState.value.isEnabled
            BiliDownFile(context, target.packageName, shizukuEnabled, enabledZeroWidthPath).readDownloadList()
        }.getOrDefault(emptyList())
        showMigrationTemplatePicker = true
    }

    FileNameInputDialog(
        showInputDialog = selectedItem != null,
        fileName = selectedItem?.title ?: "",
        extension = if (selectedOutputType == OutRecord.TYPE_MP3) "mp3" else "mp4",
        onDismiss = {
            selectedItem = null
        },
        confirmText = if (taskStatus is TaskStatus.InIdle) {
            "确定导出"
        } else { "添加到队列" },
        onConfirm = { outFile ->
            selectedItem?.let { item ->
                if (taskStatus is TaskStatus.InIdle) {
                    channel.trySend(DownloadDetailPageAction.Export(
                        entryDirPath = item.dir_path,
                        outFile = outFile,
                        outputType = selectedOutputType,
                    ))
                } else {
                    channel.trySend(DownloadDetailPageAction.AddTask(
                        entryDirPath = item.dir_path,
                        outFilePath = outFile.path,
                        title = outFile.name,
                        cover = item.cover,
                        outputType = selectedOutputType,
                    ))
                }
            }
            selectedItem = null
        }
    )

    if (showBatchDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDialog = false },
            title = { Text("批量导出当前视频") },
            text = {
                Column {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = batchMp4, onCheckedChange = { batchMp4 = it })
                        Text("导出 MP4")
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = batchMp3, onCheckedChange = { batchMp3 = it })
                        Text("导出 MP3")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = batchMp4 || batchMp3,
                    onClick = {
                        val items = state.detailInfo?.items.orEmpty()
                        showBatchDialog = false
                        scope.launch {
                            val service = BiliDownService.getService(context)
                            items.forEach { item ->
                                val safeTitle = FileNameUtils.sanitize(item.title).ifBlank { "未命名" }
                                if (batchMp4) {
                                    val outFile = BiliDownOutFile("$safeTitle.mp4")
                                    service.addTask(item.dir_path, outFile.path, outFile.name, item.cover, OutRecord.TYPE_MP4)
                                }
                                if (batchMp3) {
                                    val outFile = BiliDownOutFile("$safeTitle.mp3")
                                    service.addTask(item.dir_path, outFile.path, outFile.name, item.cover, OutRecord.TYPE_MP3)
                                }
                            }
                            navController.navigate(BiliDownScreen.Progress.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                ) { Text("加入导出队列") }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDialog = false }) { Text("取消") }
            },
        )
    }

    if (showMigrateDialog) {
        AlertDialog(
            onDismissRequest = { showMigrateDialog = false },
            title = { Text("选择目标客户端") },
            text = {
                Column {
                    Text("迁移会复制缓存并转换 entry.json，源文件会保留。由于客户端版本和缓存格式可能不同，迁移后仍可能无法被目标客户端识别。")
                    targetApps.forEach { app ->
                        TextButton(
                            onClick = {
                                pendingMigrationTarget = app
                                showMigrateDialog = false
                            }
                        ) { Text(app.name) }
                    }
                    if (targetApps.isEmpty()) Text("未检测到其他已安装的支持客户端")
                }
            },
            confirmButton = {
                TextButton(onClick = { showMigrateDialog = false }) { Text("关闭") }
            },
        )
    }

    if (showMigrationTemplatePicker && pendingMigrationTarget != null) {
        val target = pendingMigrationTarget!!
        AlertDialog(
            onDismissRequest = { showMigrationTemplatePicker = false; pendingMigrationTarget = null },
            title = { Text("选择${target.name}的原生视频") },
            text = {
                Column {
                    Text("请选择一个直接由目标客户端下载的视频，使用它的 entry.json 字段结构转换迁移内容。")
                    if (migrationTemplateVideos.isEmpty()) Text("无法读取目标客户端的视频缓存，请确认已下载视频并授予访问权限。")
                    migrationTemplateVideos.forEach { video ->
                        TextButton(onClick = {
                            selectedMigrationTemplate = video
                            showMigrationTemplatePicker = false
                        }) { Text(video.entry.title.ifBlank { video.entry.avid?.toString() ?: video.entryDirPath.substringAfterLast('/') }) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMigrationTemplatePicker = false; pendingMigrationTarget = null }) { Text("取消") } },
        )
    }

    selectedMigrationTemplate?.let { template ->
        val target = pendingMigrationTarget
        if (target != null) AlertDialog(
            onDismissRequest = { selectedMigrationTemplate = null; pendingMigrationTarget = null },
            title = { Text("确认迁移") },
            text = { Text("将以所选目标视频的 entry.json 字段结构转换迁移内容。迁移后视频仍可能无法被目标客户端识别，源文件会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    channel.trySend(DownloadDetailPageAction.MigrateCache(
                        packageName,
                        target.packageName,
                        enabledZeroWidthPath,
                        template.rawEntryJson,
                    ))
                    selectedMigrationTemplate = null
                    pendingMigrationTarget = null
                }) { Text("继续迁移") }
            },
            dismissButton = { TextButton(onClick = { selectedMigrationTemplate = null; pendingMigrationTarget = null }) { Text("取消") } },
        )
    }

    deletingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deletingItem = null },
            title = { Text("删除视频缓存？") },
            text = { Text("将从${BiliDownUtils.biliAppList.firstOrNull { it.packageName == packageName }?.name ?: "当前客户端"}中永久删除“${item.title}”对应的整个视频缓存目录（包括所有分P）。此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    channel.trySend(DownloadDetailPageAction.DeleteCache(
                        state.detailInfo?.dir_path ?: item.dir_path,
                        packageName,
                        enabledZeroWidthPath,
                    ))
                    deletingItem = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deletingItem = null }) { Text("取消") }
            },
        )
    }

    LazyColumn {
        val detailInfo = state.detailInfo
        if (detailInfo == null) {

        } else {
            item {
                Button(
                    modifier = Modifier.padding(8.dp),
                    onClick = { showBatchDialog = true },
                ) { Text("批量导出当前视频") }
                Button(
                    modifier = Modifier.padding(8.dp),
                    onClick = { showMigrateDialog = true },
                ) { Text("迁移到其他客户端") }
                DownloadListItem(
                    item = detailInfo,
                    onClick = { },
                )
            }
            items(detailInfo.items, { it.dir_path }) {
                DownloadDetailItem(
                    item = it,
                    isOut = state.outRecordMap.containsKey(it.dir_path),
                    onClick = {
                    },
                    onStartClick = {
                    },
                    onPauseClick = {
                    },
                    onExportClick = { outputType ->
                        selectedOutputType = outputType
                        selectedItem = it
                    },
                    onDeleteClick = { deletingItem = it },
                )
            }
        }
    }
}
