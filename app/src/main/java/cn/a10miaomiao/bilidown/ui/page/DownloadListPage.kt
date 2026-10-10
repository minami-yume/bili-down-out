package cn.a10miaomiao.bilidown.ui.page

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import cn.a10miaomiao.bilidown.R
import cn.a10miaomiao.bilidown.common.BiliDownFile
import cn.a10miaomiao.bilidown.common.BiliDownOutFile
import cn.a10miaomiao.bilidown.common.FileNameUtils
import cn.a10miaomiao.bilidown.common.BiliDownUtils
import cn.a10miaomiao.bilidown.common.MiaoLog
import cn.a10miaomiao.bilidown.common.datastore.DataStoreKeys
import cn.a10miaomiao.bilidown.common.datastore.rememberDataStorePreferencesFlow
import cn.a10miaomiao.bilidown.common.lifecycle.LaunchedLifecycleObserver
import cn.a10miaomiao.bilidown.common.localStoragePermission
import cn.a10miaomiao.bilidown.common.molecule.collectAction
import cn.a10miaomiao.bilidown.common.molecule.rememberPresenter
import cn.a10miaomiao.bilidown.entity.DownloadInfo
import cn.a10miaomiao.bilidown.entity.DownloadItemInfo
import cn.a10miaomiao.bilidown.entity.BiliDownloadEntryAndPathInfo
import cn.a10miaomiao.bilidown.entity.DownloadType
import cn.a10miaomiao.bilidown.db.dao.OutRecord
import cn.a10miaomiao.bilidown.service.BiliDownService
import cn.a10miaomiao.bilidown.shizuku.localShizukuPermission
import cn.a10miaomiao.bilidown.ui.BiliDownScreen
import cn.a10miaomiao.bilidown.ui.components.DownloadListItem
import cn.a10miaomiao.bilidown.ui.components.PermissionDialog
import cn.a10miaomiao.bilidown.ui.components.SwipeToRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.ShizukuProvider


data class DownloadListPageState(
    val list: List<DownloadInfo>,
    val path: String,
    val canRead: Boolean,
    val loading: Boolean,
    val refreshing: Boolean,
    val failMessage: String,
)

sealed class DownloadListPageAction {
    class GetList(
        val packageName: String,
        val enabledShizuku: Boolean,
        val enabledZeroWidthPath: Boolean,
    ) : DownloadListPageAction()

    class RefreshList(
        val packageName: String,
        val enabledShizuku: Boolean,
        val enabledZeroWidthPath: Boolean,
    ) : DownloadListPageAction()
}

@Composable
fun DownloadListPagePresenter(
    context: Context,
    action: Flow<DownloadListPageAction>,
): DownloadListPageState {
    var list by remember {
        mutableStateOf(emptyList<DownloadInfo>())
    }
    var path by remember {
        mutableStateOf("")
    }
    var canRead by remember {
        mutableStateOf(true)
    }
    var failMessage by remember {
        mutableStateOf("")
    }
    var loading by remember {
        mutableStateOf(true)
    }
    var refreshing by remember {
        mutableStateOf(false)
    }

    suspend fun getList(
        packageName: String,
        enabledShizuku: Boolean,
        enabledZeroWidthPath: Boolean,
    ) {
        try {
            MiaoLog.debug { "getList(packageName:$packageName, enabledShizuku: $enabledShizuku, enabledZeroWidthPath: $enabledZeroWidthPath)" }
            val biliDownFile = BiliDownFile(context, packageName, enabledShizuku, enabledZeroWidthPath)
            canRead = biliDownFile.canRead()
            if (!canRead) {
                return
            }
            loading = true
            failMessage = ""
            val newList = mutableListOf<DownloadInfo>()
            biliDownFile.readDownloadList().forEach {
                val biliEntry = it.entry
                var indexTitle = ""
                var itemTitle = ""
                var id = biliEntry.avid ?: 0L
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
                    itemTitle = if (ep.index_title.isNotBlank()) {
                        ep.index_title
                    } else {
                        ep.index
                    }
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
                val last = newList.lastOrNull()
                if (last != null
                    && last.type == item.type
                    && last.id == item.id
                ) {
                    if (last.is_completed && !item.is_completed) {
                        last.is_completed = false
                    }
                    last.items.add(item)
                } else {
                    newList.add(
                        DownloadInfo(
                            dir_path = it.pageDirPath,
                            media_type = biliEntry.media_type,
                            has_dash_audio = biliEntry.has_dash_audio,
                            is_completed = biliEntry.is_completed,
                            total_bytes = biliEntry.total_bytes,
                            downloaded_bytes = biliEntry.downloaded_bytes,
                            title = biliEntry.title,
                            cover = biliEntry.cover,
                            cid = cid,
                            id = id,
                            type = type,
                            items = mutableListOf(item)
                        )
                    )
                }
            }
            list = newList.toList()
        } catch (e: TimeoutCancellationException) {
            e.printStackTrace()
            failMessage = if (enabledShizuku) {
                "连接Shizuku服务超时，建议您尝试停止并重新激活Shizuku！"
            } else {
                "读取缓存列表超时！"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            failMessage = "读取列表异常：" + (e.message ?: e.toString())
        } finally {
            loading = false
        }
    }

    action.collectAction {
        when (it) {
            is DownloadListPageAction.RefreshList -> {
                refreshing = true
                withContext(Dispatchers.IO) {
                    getList(it.packageName, it.enabledShizuku, it.enabledZeroWidthPath)
                }
                refreshing = false
            }

            is DownloadListPageAction.GetList -> {
                withContext(Dispatchers.IO) {
                    getList(it.packageName, it.enabledShizuku, it.enabledZeroWidthPath)
                }
            }
        }
    }
    return DownloadListPageState(
        list,
        path,
        canRead,
        loading,
        refreshing,
        failMessage,
    )
}

@Composable
fun DownloadListPage(
    navController: NavHostController,
    packageName: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showBatchExportDialog by remember { mutableStateOf(false) }
    var showBatchMigrateDialog by remember { mutableStateOf(false) }
    var targetApps by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var pendingBatchTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var batchTemplateVideos by remember { mutableStateOf(emptyList<BiliDownloadEntryAndPathInfo>()) }
    var showBatchTemplatePicker by remember { mutableStateOf(false) }
    var selectedBatchTemplate by remember { mutableStateOf<BiliDownloadEntryAndPathInfo?>(null) }
    var batchMp4 by remember { mutableStateOf(true) }
    var batchMp3 by remember { mutableStateOf(false) }
    val storagePermission = localStoragePermission()
    val permissionState = storagePermission.collectState()
    val shizukuPermission = localShizukuPermission()
    val shizukuPermissionState by shizukuPermission.collectState()
    val enabledZeroWidthPath by rememberDataStorePreferencesFlow(
        context = context,
        key = DataStoreKeys.enabledZeroWidthPath,
        initial = true,
    ).collectAsState(true)

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
                    true
                } catch (_: Exception) { false }
                if (installed) app.packageName to app.name else null
            }
    }

    LaunchedEffect(pendingBatchTarget, enabledZeroWidthPath, shizukuPermissionState.isEnabled) {
        val target = pendingBatchTarget ?: return@LaunchedEffect
        batchTemplateVideos = runCatching {
            BiliDownFile(context, target.first, shizukuPermissionState.isEnabled, enabledZeroWidthPath).readDownloadList()
        }.getOrDefault(emptyList())
        showBatchTemplatePicker = true
    }

    val (state, channel) = rememberPresenter(listOf(packageName, permissionState, enabledZeroWidthPath)) {
        DownloadListPagePresenter(context, it)
    }

    LaunchedEffect(
        packageName,
        permissionState.isGranted,
        permissionState.isExternalStorage,
        shizukuPermissionState.isRunning,
        shizukuPermissionState.isEnabled,
        enabledZeroWidthPath,
    ) {
        if (permissionState.isGranted
            && permissionState.isExternalStorage
        ) {
            channel.send(
                DownloadListPageAction.GetList(
                    packageName = packageName,
                    enabledShizuku = shizukuPermissionState.isEnabled,
                    enabledZeroWidthPath = enabledZeroWidthPath,
                )
            )
        }
    }

    LaunchedLifecycleObserver(
        onResume = {
            if (state.list.isEmpty()) {
                channel.trySend(
                    DownloadListPageAction.GetList(
                        packageName = packageName,
                        enabledShizuku = shizukuPermissionState.isEnabled,
                        enabledZeroWidthPath = enabledZeroWidthPath,
                    )
                )
            }
        }
    )

    fun resultCallBack() {
        if (!permissionState.isGranted || !permissionState.isExternalStorage) {
            showPermissionDialog = true
        }
    }

    fun openShizuku() {
        try {
            val packageManager = context.packageManager
            val intent = packageManager.getLaunchIntentForPackage(ShizukuProvider.MANAGER_APPLICATION_ID)
            if (intent == null) {
                Toast.makeText(context, "未找到Shizuku", Toast.LENGTH_LONG)
                    .show()
            } else {
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Shizuku启动失败", Toast.LENGTH_LONG)
                .show()
            e.printStackTrace()
        }
    }

    PermissionDialog(
        showPermissionDialog = showPermissionDialog,
        isGranted = permissionState.isGranted,
        onDismiss = { showPermissionDialog = false }
    )
    if (showBatchExportDialog) {
        AlertDialog(
            onDismissRequest = { showBatchExportDialog = false },
            title = { Text("一键全部导出") },
            text = {
                Column {
                    val count = state.list.flatMap { it.items }.count { it.is_completed }
                    Text("将队列导出当前客户端中已完成的 $count 个视频")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = batchMp4, onCheckedChange = { batchMp4 = it })
                        Text("导出 MP4")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = batchMp3, onCheckedChange = { batchMp3 = it })
                        Text("导出 MP3")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = batchMp4 || batchMp3,
                    onClick = {
                        val items = state.list.flatMap { it.items }
                            .filter { it.is_completed }
                            .distinctBy { it.dir_path }
                        val reservedNames = mutableSetOf<String>()
                        fun uniqueOutputName(baseTitle: String, extension: String): String {
                            val safeTitle = FileNameUtils.sanitize(baseTitle).ifBlank { "未命名" }
                            var candidate = "$safeTitle.$extension"
                            var suffix = 2
                            while (candidate in reservedNames || BiliDownOutFile(candidate).exists()) {
                                candidate = "${safeTitle}_$suffix.$extension"
                                suffix++
                            }
                            reservedNames.add(candidate)
                            return candidate
                        }
                        showBatchExportDialog = false
                        scope.launch {
                            val service = BiliDownService.getService(context)
                            items.forEach { item ->
                                if (batchMp4) {
                                    val file = BiliDownOutFile(uniqueOutputName(item.title, "mp4"))
                                    service.addTask(item.dir_path, file.path, file.name, item.cover, OutRecord.TYPE_MP4)
                                }
                                if (batchMp3) {
                                    val file = BiliDownOutFile(uniqueOutputName(item.title, "mp3"))
                                    service.addTask(item.dir_path, file.path, file.name, item.cover, OutRecord.TYPE_MP3)
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
                TextButton(onClick = { showBatchExportDialog = false }) { Text("取消") }
            },
        )
    }
    if (showBatchMigrateDialog) {
        AlertDialog(
            onDismissRequest = { showBatchMigrateDialog = false },
            title = { Text("一键迁移全部视频") },
            text = {
                Column {
                    val sourceDirectories = state.list.map { it.dir_path }.distinct()
                    Text("将当前客户端的 ${sourceDirectories.size} 个缓存目录复制到目标客户端，源文件会保留。")
                    targetApps.forEach { (targetPackage, targetName) ->
                        TextButton(onClick = {
                            pendingBatchTarget = targetPackage to targetName
                            showBatchMigrateDialog = false
                        }) { Text(targetName) }
                    }
                    if (targetApps.isEmpty()) Text("未检测到其他已安装的支持客户端")
                }
            },
            confirmButton = {
                TextButton(onClick = { showBatchMigrateDialog = false }) { Text("关闭") }
            },
        )
    }
    if (showBatchTemplatePicker && pendingBatchTarget != null) {
        val target = pendingBatchTarget!!
        AlertDialog(
            onDismissRequest = { showBatchTemplatePicker = false; pendingBatchTarget = null },
            title = { Text("选择${target.second}的原生视频") },
            text = {
                Column {
                    Text("请选择一个由目标客户端直接下载的视频作为 entry.json 模板；全部迁移的视频会使用这份模板转换。")
                    if (batchTemplateVideos.isEmpty()) Text("无法读取目标客户端的视频缓存，请确认已下载视频并授予访问权限。")
                    batchTemplateVideos.forEach { video ->
                        TextButton(onClick = {
                            selectedBatchTemplate = video
                            showBatchTemplatePicker = false
                        }) { Text(video.entry.title.ifBlank { video.entry.avid?.toString() ?: video.entryDirPath.substringAfterLast('/') }) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBatchTemplatePicker = false; pendingBatchTarget = null }) { Text("取消") } },
        )
    }
    selectedBatchTemplate?.let { template ->
        val target = pendingBatchTarget
        if (target != null) AlertDialog(
            onDismissRequest = { selectedBatchTemplate = null; pendingBatchTarget = null },
            title = { Text("确认全部迁移") },
            text = { Text("将当前客户端的 ${state.list.map { it.dir_path }.distinct().size} 个缓存目录复制到${target.second}，并按所选视频的 entry.json 字段转换。源文件会保留，迁移后仍可能无法识别。") },
            confirmButton = {
                TextButton(onClick = {
                    val sourceDirectories = state.list.map { it.dir_path }.distinct()
                    scope.launch {
                        val service = BiliDownService.getService(context)
                        if (service.enqueueCacheMigrationBatch(
                                sourceDirectories,
                                packageName,
                                target.first,
                                enabledZeroWidthPath,
                                template.rawEntryJson,
                            )) {
                            navController.navigate(BiliDownScreen.Progress.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        } else {
                            Toast.makeText(context, "没有可迁移的缓存目录", Toast.LENGTH_LONG).show()
                        }
                    }
                    selectedBatchTemplate = null
                    pendingBatchTarget = null
                }) { Text("开始迁移") }
            },
            dismissButton = { TextButton(onClick = { selectedBatchTemplate = null; pendingBatchTarget = null }) { Text("取消") } },
        )
    }
    SwipeToRefresh(
        refreshing = state.refreshing,
        onRefresh = {
            channel.trySend(
                DownloadListPageAction.RefreshList(
                    packageName = packageName,
                    enabledShizuku = shizukuPermissionState.isEnabled,
                    enabledZeroWidthPath = enabledZeroWidthPath,
                )
            )
        },
    ) {
       if (state.list.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 4.dp,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        "正在读取列表",
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else if (!permissionState.isGranted || !permissionState.isExternalStorage) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (permissionState.isGranted) {
                            Text(text = "请授予所有文件的存储权限")
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = {
                                    storagePermission.requestPermissions(::resultCallBack)
                                }
                            ) {
                                Text(text = "授予所有文件的权限")
                            }
                        } else {
                            Text(text = "请授予存储权限")
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = {
                                    storagePermission.requestPermissions(::resultCallBack)
                                }
                            ) {
                                Text(text = "授予权限")
                            }
                        }
                    }
                } else if (!state.canRead) {
                    if (enabledZeroWidthPath) {
                        Text(text = "零宽路径访问失败，请在设置中关闭该选项后使用文件夹授权")
                    } else {
                        Text(text = "请授予文件夹权限")
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = {
                                val biliDownFile = BiliDownFile(context, packageName, shizukuPermissionState.isEnabled, enabledZeroWidthPath)
                                biliDownFile.startFor(2)
                            }
                        ) {
                            Text(text = "授予权限")
                        }
                    }
                    TextButton(
                        onClick = {
                            navController.navigate(BiliDownScreen.More.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    ) {
                        Text(text = "或使用Shizuku")
                    }
                } else if (state.failMessage.isNotBlank()) {
                    Text(
                        text = state.failMessage,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(20.dp)
                    )
                    if ("Shizuku" in state.failMessage) {
                        TextButton(
                            onClick = ::openShizuku,
                        ) {
                            Text(text = "跳转Shizuku")
                        }
                    }
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.ic_movie_pay_area_limit),
                        contentDescription = "空空如也",
                        modifier = Modifier.size(200.dp, 200.dp)
                    )
                    Text(
                        modifier = Modifier.padding(vertical = 8.dp),
                        text = "空空如也",
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = { showBatchExportDialog = true },
                        ) { Text("一键全部导出", maxLines = 1) }
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = { showBatchMigrateDialog = true },
                        ) { Text("一键迁移全部视频", maxLines = 1) }
                    }
                }
                items(state.list, { it.dir_path }) {
                    DownloadListItem(
                        item = it,
                        onClick = {
                            val dirPath = Uri.encode(it.dir_path)
                            navController.navigate(
                                BiliDownScreen.Detail.route + "?packageName=${packageName}&dirPath=${dirPath}"
                            )
                        }
                    )
                }
            }
        }
    }

}
