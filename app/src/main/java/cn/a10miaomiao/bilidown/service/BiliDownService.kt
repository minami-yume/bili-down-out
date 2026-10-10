package cn.a10miaomiao.bilidown.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import cn.a10miaomiao.bilidown.BiliDownApp
import cn.a10miaomiao.bilidown.common.BiliEntryJsonParser
import cn.a10miaomiao.bilidown.common.BiliEntryJsonCompatibility
import cn.a10miaomiao.bilidown.common.CommandUtil
import cn.a10miaomiao.bilidown.common.MiaoLog
import cn.a10miaomiao.bilidown.common.file.MiaoDocumentFile
import cn.a10miaomiao.bilidown.db.AppDatabase
import cn.a10miaomiao.bilidown.db.dao.OutRecord
import cn.a10miaomiao.bilidown.entity.BiliDownloadEntryInfo
import cn.a10miaomiao.bilidown.shizuku.util.RemoteServiceUtil
import cn.a10miaomiao.bilidown.state.AppState
import cn.a10miaomiao.bilidown.state.TaskStatus
import io.microshow.rxffmpeg.RxFFmpegCommandList
import io.microshow.rxffmpeg.RxFFmpegInvoke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.CoroutineContext

class BiliDownService :
    Service(),
    CoroutineScope {

    companion object {
        private const val TAG = "DownloadService"
        private val channel = Channel<BiliDownService>()
        private var _instance: BiliDownService? = null

//        val instance get() = _instance

        suspend fun getService(context: Context): BiliDownService {
            _instance?.let { return it }
            startService(context)
            return channel.receive().also {
                _instance = it
            }
        }

        fun startService(context: Context) {
            val intent = Intent(context, BiliDownService::class.java)
            context.startService(intent)
        }
    }

    private lateinit var appDatabase: AppDatabase
    private lateinit var appState: AppState

    private var job: Job = Job()
    private val taskStartMutex = Mutex()
    private val migrationQueue = Channel<CacheMigrationBatch>(Channel.UNLIMITED)

    private data class CacheMigrationBatch(
        val sourcePaths: List<String>,
        val sourcePackageName: String,
        val targetPackageName: String,
        val useZeroWidthPath: Boolean,
        val targetEntryTemplate: String,
    )
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    private val myProgressCallback by lazy {
        MyProgressCallback(this@BiliDownService, appState)
    }

    override fun onCreate() {
        super.onCreate()
        val biliDownApp = application as BiliDownApp
        appDatabase = biliDownApp.database
        appState = biliDownApp.state
        job = Job()
        launch {
            channel.send(this@BiliDownService)
            appState.taskStatus.collect {
                if (it is TaskStatus.InIdle) {
                    startNextWaitingTask()
                }
            }
        }
        launch {
            for (batch in migrationQueue) {
                while (appState.taskStatus.value !is TaskStatus.InIdle && appState.taskStatus.value !is TaskStatus.Error) {
                    kotlinx.coroutines.delay(300)
                }
                val paths = batch.sourcePaths.distinct()
                var succeeded = 0
                paths.forEachIndexed { index, sourcePath ->
                    val name = sourcePath.trimEnd('/').substringAfterLast('/').ifBlank { "缓存目录" }
                    appState.putTaskStatus(TaskStatus.Migrating(
                        entryDirPath = sourcePath,
                        name = name,
                        cover = "",
                        // Byte-level totals vary across access backends; show an indeterminate
                        // current-item phase and advance the batch bar after each directory.
                        progress = -1f,
                        completedDirectories = index,
                        totalDirectories = paths.size,
                    ))
                    if (migrateCacheDirectory(
                            sourcePath,
                            batch.sourcePackageName,
                            batch.targetPackageName,
                            batch.useZeroWidthPath,
                            batch.targetEntryTemplate,
                            showSuccessToast = false,
                        )) succeeded++
                    appState.putTaskStatus(TaskStatus.Migrating(
                        entryDirPath = sourcePath,
                        name = name,
                        cover = "",
                        progress = (index + 1f) / paths.size.coerceAtLeast(1),
                        completedDirectories = index + 1,
                        totalDirectories = paths.size,
                    ))
                }
                toast("迁移队列完成：成功 $succeeded/${paths.size} 个缓存目录")
                appState.putTaskStatus(TaskStatus.InIdle)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        _instance = null
    }

    suspend fun exportBiliVideo(
        entryDirPath: String,
        outFile: File,
        outputType: Int = OutRecord.TYPE_MP4,
    ): Boolean {
        val taskStatus = appState.taskStatus.value
        val shizukuState = appState.shizukuState.value
        if (taskStatus != TaskStatus.InIdle && taskStatus !is TaskStatus.Error) {
            toast("有视频正在导出中，请稍后再试")
            return false
        }

        val t = appDatabase.outRecordDao().findByPath(entryDirPath, outputType)
        if (t != null && t.status == OutRecord.STATUS_SUCCESS) {
            toast("此视频已导出")
            return false
        }
        if (outputType == OutRecord.TYPE_MP3) {
            return exportBiliAudio(entryDirPath, outFile)
        }
        // 使用Shizuku
        if (shizukuState.isEnabled) {
            val shizukuUserService = try {
                RemoteServiceUtil.getUserService()
            } catch (e: TimeoutCancellationException) {
                e.printStackTrace()
                toast("Shizuku连接超时！")
                return false
            }
            val errorMessage = shizukuUserService.exportBiliVideo(
                entryDirPath,
                outFile.path,
                myProgressCallback,
            )
            if (errorMessage != null) {
                toast(errorMessage)
                return false
            }
            return true
        }

        // 使用DocumentFile
        if (entryDirPath.startsWith("content:")) {
            return copyAndExportBiliVideo(entryDirPath, outFile)
        }

        // 使用Java File API正常导出
        val entryDirFile = File(entryDirPath)
        val entryJsonFile = File(entryDirPath, "entry.json")
        val entry = BiliEntryJsonParser.parseOrNull(entryJsonFile.readText())
            ?: run {
                toast("entry.json 文件为空或已损坏")
                return false
            }

        val videoDirPath = entryDirPath + "/" + entry.videoDirName
        val videoDir = File(videoDirPath)
        if (!videoDir.exists() || !videoDir.isDirectory) {
            val videoFile = entryDirFile.listFiles().find {
                it.isFile && it.name.startsWith(entry.videoDirName)
                        && it.name.endsWith(".mp4")
            }
            if (videoFile == null) {
                toast("找不到视频文件夹：${entry.videoDirName}")
                return false
            }
            // 直接复制mp4文件
            appState.putTaskStatus(
                TaskStatus.Copying(
                    name = entry.name,
                    entryDirPath = entryDirPath,
                    cover = entry.cover,
                    progress = 0f,
                )
            )
            launch {
                copyFile(videoFile, outFile)
            }
            return true
        }
//        val videoIndexJsonFile = File(videoDirPath, "index.json")
//        if (!videoIndexJsonFile.exists()) {
//            toast("缓存文件丢失，请重新缓存")
//            return false
//        }
//        val videoIndexJson = videoIndexJsonFile.readText()
        if (entry.media_type == 1) {
            // 多段blv(flv)视频
            val blvFiles = mutableListOf<File>()
            var blvIndex = 0
            while (true) {
                val file = File(videoDir, "/${blvIndex}.blv")
                if (file.exists()) {
                    blvFiles.add(file)
                    blvIndex++
                } else {
                    break
                }
            }
            if (blvFiles.isEmpty()) {
                toast("找不到视频文件：0.blv")
                return false
            }
            appState.putTaskStatus(
                TaskStatus.InProgress(
                    name = entry.name,
                    entryDirPath = entryDirPath,
                    cover = entry.cover,
                    progress = 0f
                )
            )
            mergerVideos(
                blvFiles,
                outFile
            )
            return true
        } else {
            val videoFile = File(videoDir, "video.m4s")
            val audioFile = File(videoDir, "audio.m4s")
            if (!videoFile.exists()) {
                toast("找不到视频文件")
                return false
            }
            if (!audioFile.exists()) {
                appState.putTaskStatus(
                    TaskStatus.Copying(
                        name = entry.name,
                        entryDirPath = entryDirPath,
                        cover = entry.cover,
                        progress = 0f,
                    )
                )
                launch {
                    copyFile(videoFile, outFile)
                }
                return true
            }
            appState.putTaskStatus(
                TaskStatus.InProgress(
                    name = entry.name,
                    entryDirPath = entryDirPath,
                    cover = entry.cover,
                    progress = 0f,
                )
            )
            mergerVideoAndAudio(videoFile, audioFile, outFile)
            return true
        }
    }

    private suspend fun exportBiliAudio(
        entryDirPath: String,
        outFile: File,
    ): Boolean {
        if (appState.shizukuState.value.isEnabled) {
            val userService = try {
                RemoteServiceUtil.getUserService()
            } catch (e: TimeoutCancellationException) {
                toast("Shizuku连接超时！")
                return false
            }
            val error = userService.exportBiliAudio(entryDirPath, outFile.path, myProgressCallback)
            if (error != null) {
                toast(error)
                return false
            }
            return true
        }

        val isDocument = entryDirPath.startsWith("content:")
        val entryDir = if (isDocument) {
            DocumentFile.fromTreeUri(this, Uri.parse(entryDirPath))
                ?: run { toast("无法访问视频目录"); return false }
        } else null
        val entry = if (entryDir != null) {
            BiliEntryJsonParser.parseOrNull(MiaoDocumentFile(this, entryDir, "/entry.json").readText())
        } else {
            BiliEntryJsonParser.parseOrNull(File(File(entryDirPath), "entry.json").readText())
        } ?: run { toast("entry.json 文件为空或已损坏"); return false }

        val documentAudio = entryDir?.let { dir ->
            MiaoDocumentFile(this, dir, "/${entry.videoDirName}").documentFile.findFile("audio.m4s")
        }
        val documentCover = entryDir?.findFile("cover.jpg")?.takeIf { it.isFile && it.length() > 0 }
        val sourceFile = if (entryDir != null) null else File(File(entryDirPath), "${entry.videoDirName}/audio.m4s")
        if ((documentAudio == null || !documentAudio.exists()) && (sourceFile == null || !sourceFile.isFile)) {
            toast("找不到 audio.m4s，当前缓存格式无法直接导出音频")
            return false
        }

        val tempAudio = if (documentAudio != null) File(getTempPath(), "audio_export_source.m4s") else null
        val tempCover = if (documentCover != null) File(getTempPath(), "audio_export_cover.jpg") else null
        val audioInput: File
        val coverInput: File?
        try {
            if (documentAudio != null && tempAudio != null) {
                MiaoDocumentFile(this, documentAudio).copyToTemp(tempAudio)
                audioInput = tempAudio
            } else {
                audioInput = sourceFile!!
            }
            if (documentCover != null && tempCover != null) {
                MiaoDocumentFile(this, documentCover).copyToTemp(tempCover)
                coverInput = tempCover
            } else {
                coverInput = if (entryDir == null) File(File(entryDirPath), "cover.jpg").takeIf { it.isFile && it.length() > 0 } else null
            }
        } catch (e: Exception) {
            tempAudio?.delete()
            tempCover?.delete()
            toast(e.message ?: "读取音频或封面失败")
            return false
        }

        appState.putTaskStatus(if (coverInput == null) {
            TaskStatus.Copying(
                name = entry.name,
                entryDirPath = entryDirPath,
                cover = entry.cover,
                progress = 0f,
                outputType = OutRecord.TYPE_MP3,
            )
        } else {
            TaskStatus.InProgress(
                name = entry.name,
                entryDirPath = entryDirPath,
                cover = entry.cover,
                progress = 0f,
                outputType = OutRecord.TYPE_MP3,
            )
        })
        outFile.parentFile?.mkdirs()
        if (coverInput == null) {
            return try {
                audioInput.copyTo(outFile, overwrite = true)
                scanExportedAudio(outFile.path)
                putOutRecord(entryDirPath, outFile.path, outFile.name, entry.cover,
                    status = OutRecord.STATUS_SUCCESS, type = OutRecord.TYPE_MP3)
                tempAudio?.delete()
                appState.putTaskStatus(TaskStatus.InIdle)
                true
            } catch (e: Exception) {
                tempAudio?.delete()
                outFile.delete()
                appState.putTaskStatus(TaskStatus.Error(
                    entryDirPath = entryDirPath,
                    name = entry.name,
                    cover = entry.cover,
                    progress = 0f,
                    message = e.message ?: "复制音频缓存失败",
                    outputType = OutRecord.TYPE_MP3,
                ))
                toast(e.message ?: "复制音频缓存失败")
                false
            }
        }

        val commands = RxFFmpegCommandList().apply {
            append("-y")
            append("-i"); append(audioInput.absolutePath)
            append("-i"); append(coverInput.absolutePath)
            append("-map"); append("0:a:0")
            append("-map"); append("1:v:0")
            append("-c:a"); append("libmp3lame")
            append("-q:a"); append("2")
            append("-c:v"); append("copy")
            append("-id3v2_version"); append("3")
            append("-metadata:s:v:0"); append("title=Album cover")
            append("-metadata:s:v:0"); append("comment=Cover (front)")
            append("-disposition:v:0"); append("attached_pic")
            entry.owner_name?.takeIf { it.isNotBlank() }?.let { ownerName ->
                append("-metadata"); append("artist=$ownerName")
            }
            append(outFile.absolutePath)
        }.build()
        val subscriber = object : MyRxFFmpegSubscriber(appState) {
            override fun onFinish() {
                scanExportedAudio(outFile.path)
                launch {
                    putOutRecord(entryDirPath, outFile.path, outFile.name, entry.cover,
                        status = OutRecord.STATUS_SUCCESS, type = OutRecord.TYPE_MP3)
                    tempAudio?.delete()
                    tempCover?.delete()
                    appState.putTaskStatus(TaskStatus.InIdle)
                }
            }

            override fun onError(message: String) {
                tempAudio?.delete()
                tempCover?.delete()
                outFile.delete()
                super.onError(message)
            }

            override fun onCancel() {
                tempAudio?.delete()
                tempCover?.delete()
                outFile.delete()
                super.onCancel()
            }
        }
        RxFFmpegInvoke.getInstance().runCommandRxJava(commands).subscribe(subscriber)
        return true
    }

    fun enqueueCacheMigrationBatch(
        sourceDirectoryPaths: List<String>,
        sourcePackageName: String,
        targetPackageName: String,
        useZeroWidthPath: Boolean,
        targetEntryTemplate: String,
    ): Boolean {
        if (sourceDirectoryPaths.isEmpty()) return false
        return migrationQueue.trySend(CacheMigrationBatch(
            sourcePaths = sourceDirectoryPaths,
            sourcePackageName = sourcePackageName,
            targetPackageName = targetPackageName,
            useZeroWidthPath = useZeroWidthPath,
            targetEntryTemplate = targetEntryTemplate,
        )).isSuccess
    }

    suspend fun deleteCacheEntry(
        sourceDirectoryPath: String,
        sourcePackageName: String,
        useZeroWidthPath: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (sourceDirectoryPath.startsWith("content:")) {
                val directory = DocumentFile.fromTreeUri(this@BiliDownService, Uri.parse(sourceDirectoryPath))
                    ?: throw IllegalStateException("无法打开视频缓存目录")
                if (!directory.isDirectory || directory.name?.matches(Regex("\\d+")) != true) {
                    throw IllegalStateException("删除路径不是按视频 ID 命名的缓存目录")
                }
                if (!directory.delete()) throw IllegalStateException("SAF 未能删除缓存目录")
            } else if (appState.shizukuState.value.isEnabled) {
                val userService = RemoteServiceUtil.getUserService()
                val error = userService.deleteCacheEntry(sourceDirectoryPath, sourcePackageName)
                if (error != null) throw IllegalStateException(error)
            } else {
                val path = sourceDirectoryPath
                val relativeDownloadPath = if (sourcePackageName == "com.example.piliplus") "files/download" else "download"
                val allowedRoots = buildList {
                    add("/storage/emulated/0/Android/data/$sourcePackageName/$relativeDownloadPath/")
                    if (useZeroWidthPath) add("/storage/emulated/0\u200B/Android/data/$sourcePackageName/$relativeDownloadPath/")
                }
                val allowedRoot = allowedRoots.firstOrNull { path.startsWith(it) }
                    ?: throw IllegalStateException("拒绝删除下载目录以外的路径")
                if (!path.removePrefix(allowedRoot).matches(Regex("\\d+"))) {
                    throw IllegalStateException("删除路径必须是下载目录下按视频 ID 命名的文件夹")
                }
                val directory = File(path)
                if (!directory.isDirectory) throw IllegalStateException("找不到要删除的视频缓存目录")
                if (!directory.deleteRecursively()) throw IllegalStateException("无法完整删除缓存目录")
            }
            true
        } catch (e: Exception) {
            toast("删除失败：${e.message ?: e}")
            false
        }
    }

    private suspend fun migrateCacheDirectory(
        sourceDirectoryPath: String,
        sourcePackageName: String,
        targetPackageName: String,
        useZeroWidthPath: Boolean,
        targetEntryTemplate: String,
        showSuccessToast: Boolean = true,
    ): Boolean = withContext(Dispatchers.IO) {
        val shizukuEnabled = appState.shizukuState.value.isEnabled
        // A path returned by a list read through U+200B must be normalized for Shizuku.
        // Also pass this normalized value to the remote service (not the original path).
        val sourcePath = if (shizukuEnabled) {
            sourceDirectoryPath.replace("/storage/emulated/0\u200B/Android/data/", "/storage/emulated/0/Android/data/")
        } else sourceDirectoryPath
        val sourceIsDocument = sourcePath.startsWith("content:")
        val sourceName = if (sourceIsDocument) {
            Uri.parse(sourcePath).pathSegments.lastOrNull()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }.orEmpty()
        } else File(sourcePath.trimEnd('/')).name

        val downloadRelativePath = if (targetPackageName == "com.example.piliplus") {
            "files/download"
        } else {
            "download"
        }
        val useDirectFileAccess = useZeroWidthPath || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        val destinationName = when {
            shizukuEnabled -> "/storage/emulated/0/Android/data/$targetPackageName/$downloadRelativePath/$sourceName"
            useZeroWidthPath -> "/storage/emulated/0\u200B/Android/data/$targetPackageName/$downloadRelativePath/$sourceName"
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> "/storage/emulated/0/Android/data/$targetPackageName/$downloadRelativePath/$sourceName"
            else -> ""
        }

        try {
            if (sourceName.isBlank()) {
                toast("无法从源路径识别缓存目录名称：$sourceDirectoryPath")
                return@withContext false
            }
            if (shizukuEnabled) {
                val userService = RemoteServiceUtil.getUserService()
                val error = userService.migrateCache(
                    sourcePath,
                    destinationName,
                    targetEntryTemplate,
                )
                if (error != null) {
                    toast(error)
                    return@withContext false
                }
            } else if (useDirectFileAccess) {
                val sourceFile = if (sourceIsDocument) null else File(sourcePath)
                val sourceDocument = if (sourceIsDocument) resolveSafSourceDirectory(sourcePackageName, sourceName) else null
                if (sourceFile?.isDirectory != true && sourceDocument?.isDirectory != true) {
                    toast("源缓存目录不可访问：$sourcePath")
                    return@withContext false
                }
                val destination = File(destinationName)
                if (destination.exists()) {
                    toast("目标客户端已有同名缓存目录，为避免覆盖已取消迁移")
                    return@withContext false
                }
                destination.parentFile?.mkdirs()
                if (sourceFile != null) {
                    sourceFile.copyRecursively(destination, overwrite = false)
                } else {
                    copyDocumentDirectoryToFile(sourceDocument!!, destination)
                }
                destination.walkTopDown().forEach {
                    it.setReadable(true, false)
                    it.setWritable(true, false)
                    if (it.isDirectory) it.setExecutable(true, false)
                }
                normalizeMigratedEntryJson(destination, targetEntryTemplate)
            } else {
                val sourceDocument = if (sourceIsDocument) {
                    resolveSafSourceDirectory(sourcePackageName, sourceName)
                } else null
                val sourceFile = if (sourceIsDocument) null else File(sourcePath)
                if (sourceFile?.isDirectory != true && sourceDocument?.isDirectory != true) {
                    toast("源缓存目录不可访问：$sourcePath")
                    return@withContext false
                }
                val treeId = "primary:Android/data/$targetPackageName"
                if (!MiaoDocumentFile.checkFolderPermission(this@BiliDownService, treeId)) {
                    toast("尚未授权目标客户端目录，请先在应用列表中添加并授权该客户端")
                    return@withContext false
                }
                val targetTree = DocumentFile.fromTreeUri(
                    this@BiliDownService,
                    MiaoDocumentFile.getFolderUri(treeId, true),
                ) ?: run { toast("无法打开目标客户端目录"); return@withContext false }
                var parent = targetTree
                downloadRelativePath.split('/').forEach { segment ->
                    parent = parent.findFile(segment) ?: parent.createDirectory(segment)
                        ?: throw IllegalStateException("无法创建目标目录：$segment")
                }
                if (parent.findFile(sourceName) != null) {
                    toast("目标客户端已有同名缓存目录，为避免覆盖已取消迁移")
                    return@withContext false
                }
                val destination = parent.createDirectory(sourceName)
                    ?: throw IllegalStateException("无法创建目标缓存目录")
                if (sourceDocument != null) {
                    copyDocumentDirectory(sourceDocument, destination)
                } else {
                    copyFileDirectoryToDocument(sourceFile!!, destination)
                }
                normalizeMigratedEntryJson(destination, targetEntryTemplate)
            }
            if (showSuccessToast) toast("缓存已复制到目标客户端，源文件保留")
            true
        } catch (e: Exception) {
            toast("缓存迁移失败：${e.message ?: e}")
            false
        }
    }

    private fun resolveSafSourceDirectory(packageName: String, directoryName: String): DocumentFile? {
        val treeId = "primary:Android/data/$packageName"
        if (!MiaoDocumentFile.checkFolderPermission(this, treeId)) return null
        var directory = DocumentFile.fromTreeUri(this, MiaoDocumentFile.getFolderUri(treeId, true)) ?: return null
        val relativePath = if (packageName == "com.example.piliplus") "files/download" else "download"
        (relativePath.split('/') + directoryName).forEach { segment ->
            directory = directory.findFile(segment) ?: return null
        }
        return directory.takeIf { it.isDirectory }
    }

    private fun normalizeMigratedEntryJson(directory: File, targetEntryTemplate: String) {
        directory.walkTopDown().filter { it.isFile && it.name == "entry.json" }.forEach { entryFile ->
            val converted = BiliEntryJsonCompatibility.convertForMigration(entryFile.readText(), targetEntryTemplate)
            entryFile.writeText(converted)
        }
    }

    private fun normalizeMigratedEntryJson(directory: DocumentFile, targetEntryTemplate: String) {
        directory.listFiles().forEach { child ->
            if (child.isDirectory) {
                normalizeMigratedEntryJson(child, targetEntryTemplate)
            } else if (child.name == "entry.json") {
                val raw = contentResolver.openInputStream(child.uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("无法读取 entry.json")
                val converted = BiliEntryJsonCompatibility.convertForMigration(raw, targetEntryTemplate)
                contentResolver.openOutputStream(child.uri, "wt")?.bufferedWriter()?.use { it.write(converted) }
                    ?: throw IllegalStateException("无法写入 entry.json")
            }
        }
    }

    private fun copyDocumentDirectoryToFile(source: DocumentFile, destination: File) {
        if (!destination.mkdirs() && !destination.isDirectory) {
            throw IllegalStateException("无法创建目录：${destination.path}")
        }
        source.listFiles().forEach { child ->
            val target = File(destination, child.name ?: "")
            if (child.isDirectory) {
                copyDocumentDirectoryToFile(child, target)
            } else {
                contentResolver.openInputStream(child.uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法读取文件：${child.name}")
            }
        }
    }

    private fun copyDocumentDirectory(source: DocumentFile, destination: DocumentFile) {
        source.listFiles().forEach { child ->
            val name = child.name ?: throw IllegalStateException("源文件名为空")
            if (child.isDirectory) {
                val targetDirectory = destination.createDirectory(name)
                    ?: throw IllegalStateException("无法创建目录：$name")
                copyDocumentDirectory(child, targetDirectory)
            } else {
                val targetFile = destination.createFile("application/octet-stream", name)
                    ?: throw IllegalStateException("无法创建文件：$name")
                contentResolver.openInputStream(child.uri)?.use { input ->
                    contentResolver.openOutputStream(targetFile.uri)?.use { output -> input.copyTo(output) }
                        ?: throw IllegalStateException("无法写入文件：$name")
                } ?: throw IllegalStateException("无法读取文件：$name")
            }
        }
    }

    private fun copyFileDirectoryToDocument(source: File, destination: DocumentFile) {
        source.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val targetDirectory = destination.createDirectory(child.name)
                    ?: throw IllegalStateException("无法创建目录：${child.name}")
                copyFileDirectoryToDocument(child, targetDirectory)
            } else {
                val targetFile = destination.createFile("application/octet-stream", child.name)
                    ?: throw IllegalStateException("无法创建文件：${child.name}")
                child.inputStream().use { input ->
                    contentResolver.openOutputStream(targetFile.uri)?.use { output -> input.copyTo(output) }
                        ?: throw IllegalStateException("无法写入文件：${child.name}")
                }
            }
        } ?: throw IllegalStateException("无法读取源目录：${source.path}")
    }

    /**
     * 复制并导出
     */
    suspend fun copyAndExportBiliVideo(
        entryDirPath: String,
        outFile: File,
    ): Boolean {
        val entryDirFile = DocumentFile.fromTreeUri(this, Uri.parse(entryDirPath))!!
        val entryJsonFile = MiaoDocumentFile(this, entryDirFile, "/entry.json")
        val entry = BiliEntryJsonParser.parseOrNull(entryJsonFile.readText())
            ?: run {
                toast("entry.json 文件为空或已损坏")
                return false
            }
        val videoDir = MiaoDocumentFile(this, entryDirFile, "/${entry.videoDirName}")

        if (!videoDir.exists() || !videoDir.isDirectory) {
            val videoFile = entryDirFile.listFiles().find {
                it.isFile && it.name?.startsWith(entry.videoDirName) == true
                        && it.name?.endsWith(".mp4") == true
            }
            if (videoFile == null) {
                toast("找不到视频文件夹：${entry.videoDirName}")
                return false
            }
            // 直接复制mp4文件
            appState.putTaskStatus(
                TaskStatus.Copying(
                    name = entry.name,
                    entryDirPath = entryDirPath,
                    cover = entry.cover,
                    progress = 0f,
                )
            )
            launch {
                copyFile(MiaoDocumentFile(this@BiliDownService, videoFile), outFile)
            }
            return true
        }
//        val videoIndexJsonFile = File(videoDirPath, "index.json")
//        if (!videoIndexJsonFile.exists()) {
//            toast("缓存文件丢失，请重新缓存")
//            return false
//        }
//        val videoIndexJson = videoIndexJsonFile.readText()
        if (entry.media_type == 1) {
            // 多段blv(flv)视频
            val blvFiles = mutableListOf<MiaoDocumentFile>()
            var blvIndex = 0
            while (true) {
                val file = MiaoDocumentFile(this, videoDir.documentFile, "/${blvIndex}.blv")
                if (file.exists()) {
                    blvFiles.add(file)
                    blvIndex++
                } else {
                    break
                }
            }
            if (blvFiles.isEmpty()) {
                toast("找不到视频文件：0.blv")
                return false
            }
            appState.putTaskStatus(
                TaskStatus.CopyingToTemp(
                    name = entry.name,
                    entryDirPath = entryDirPath,
                    cover = entry.cover,
                    progress = 0f,
                )
            )
            launch {
                try {
                    val tempFiles = blvFiles.map {
                        val tempF = File(getTempPath(), it.name)
                        it.copyToTemp(tempF)
                        tempF
                    }
                    appState.putTaskStatus(
                        TaskStatus.InProgress(
                            name = entry.name,
                            entryDirPath = entryDirPath,
                            cover = entry.cover,
                            progress = 0f
                        )
                    )
                    mergerVideos(
                        tempFiles,
                        outFile
                    )
                } catch (e: Exception) {
                    appState.putTaskStatus(
                        TaskStatus.Error(
                            appState.taskStatus.value,
                            e.message ?: e.toString(),
                        )
                    )
                    e.printStackTrace()
                }
            }
            return true
        } else {
            // 音视频分离视频
            val videoFile = MiaoDocumentFile(this, videoDir.documentFile, "/video.m4s")
            val audioFile = MiaoDocumentFile(this, videoDir.documentFile, "/audio.m4s")
            if (!videoFile.exists()) {
                toast("找不到视频文件：video.m4s")
                return false
            }
            if (!audioFile.exists()) {
                // 直接复制
                appState.putTaskStatus(
                    TaskStatus.Copying(
                        name = entry.name,
                        entryDirPath = entryDirPath,
                        cover = entry.cover,
                        progress = 0f,
                    )
                )
                launch {
                    copyFile(videoFile, outFile)
                }
                return false
            }
            appState.putTaskStatus(
                TaskStatus.CopyingToTemp(
                    name = entry.name,
                    entryDirPath = entryDirPath,
                    cover = entry.cover,
                    progress = 0f,
    //                audioFile = audioFile,
    //                videoFile = videoFile,
                )
            )
            launch {
                try {
                    val tempVideoFile = File(getTempPath(), "video.m4s")
                    val tempAudioFile = File(getTempPath(), "audio.m4s")
                    videoFile.copyToTemp(tempVideoFile)
                    audioFile.copyToTemp(tempAudioFile)
                    appState.putTaskStatus(
                        TaskStatus.InProgress(
                            name = entry.name,
                            entryDirPath = entryDirPath,
                            cover = entry.cover,
                            progress = 0f
                        )
                    )
                    mergerVideoAndAudio(
                        tempVideoFile,
                        tempAudioFile,
                        outFile
                    )
                } catch (e: Exception) {
                    appState.putTaskStatus(
                        TaskStatus.Error(
                            appState.taskStatus.value,
                            e.message ?: e.toString(),
                        )
                    )
                    e.printStackTrace()
                }
            }
            return true
        }
    }

    private suspend fun copyFile(
        inputFile: File,
        outFile: File
    ) {

        val fileInputStream = FileInputStream(inputFile)
        val fileOutputStream = FileOutputStream(outFile)
        val buffer = ByteArray(1024)
        var byteRead: Int
        while (-1 != fileInputStream.read(buffer).also { byteRead = it }) {
            fileOutputStream.write(buffer, 0, byteRead)
        }
        fileInputStream.close()
        fileOutputStream.flush()
        fileOutputStream.close()

        val currentStatus = appState.taskStatus.value
        putOutRecord(
            currentStatus.entryDirPath,
            outFile.path,
            outFile.name,
            currentStatus.cover,
            status = OutRecord.STATUS_SUCCESS,
            type = currentStatus.outputType,
        )
        appState.putTaskStatus(TaskStatus.InIdle)
    }

    private suspend fun copyFile(
        inputFile: MiaoDocumentFile,
        outFile: File
    ) {
        inputFile.copyToTemp(outFile)
        val currentStatus = appState.taskStatus.value
        putOutRecord(
            currentStatus.entryDirPath,
            outFile.path,
            outFile.name,
            currentStatus.cover,
            status = OutRecord.STATUS_SUCCESS,
            type = currentStatus.outputType,
        )
        appState.putTaskStatus(TaskStatus.InIdle)
    }

    private fun mergerVideoAndAudio(
        videoFile: File,
        audioFile: File,
        outFile: File,
    ) {
        if (!outFile.parentFile!!.exists()) {
            outFile.parentFile!!.mkdir()
        }
        val commands = RxFFmpegCommandList().apply {
            append("-i")
            append(videoFile.absolutePath)
            append("-i")
            append(audioFile.absolutePath)
            append("-c:v")
            append("copy")
            append("-strict")
            append("experimental")
            append(outFile.absolutePath)
        }.build()
        //开始执行FFmpeg命令
        val myRxFFmpegSubscriber = object : MyRxFFmpegSubscriber(
            appState,
//            getTempPath()
        ) {
            override fun onFinish() {
                val currentStatus = appState.taskStatus.value
                launch {
                    putOutRecord(
                        currentStatus.entryDirPath,
                        outFile.path,
                        outFile.name,
                        currentStatus.cover,
                        status = OutRecord.STATUS_SUCCESS,
                        type = currentStatus.outputType,
                    )
                }
                val tempPath = getTempPath()
                File(tempPath).deleteRecursively()
                super.onFinish()
            }
        }
        RxFFmpegInvoke.getInstance()
            .runCommandRxJava(commands)
            .subscribe(myRxFFmpegSubscriber)
    }

    private fun mergerVideos(
        videoFiles: List<File>,
        outFile: File,
    ) {
        if (!outFile.parentFile!!.exists()) {
            outFile.parentFile!!.mkdir()
        }
        val ffTxtFile = File(getTempPath(), ".ff.txt")
        if (ffTxtFile.exists() && ffTxtFile.isDirectory) {
            ffTxtFile.deleteRecursively()
            ffTxtFile.delete()
        }
        val ffTxtContent = videoFiles.map {
                file -> "file ${CommandUtil.filePath(file)}"
        }.joinToString("\n")
        ffTxtFile.writeText(ffTxtContent)
        val commands = RxFFmpegCommandList().apply {
            append("-f")
            append("concat")
            append("-safe")
            append("0")
            append("-i")
            append(ffTxtFile.absolutePath)
            append("-c")
            append("copy")
            append(outFile.absolutePath)
        }.build()
        //开始执行FFmpeg命令
        val myRxFFmpegSubscriber = object : MyRxFFmpegSubscriber(appState) {
            override fun onFinish() {
                val currentStatus = appState.taskStatus.value
                launch {
                    putOutRecord(
                        currentStatus.entryDirPath,
                        outFile.path,
                        outFile.name,
                        currentStatus.cover,
                        status = OutRecord.STATUS_SUCCESS,
                        type = currentStatus.outputType,
                    )
                }
                val tempPath = getTempPath()
                File(tempPath).deleteRecursively()
                super.onFinish()
            }
        }
        RxFFmpegInvoke.getInstance()
            .runCommandRxJava(commands)
            .subscribe(myRxFFmpegSubscriber)
    }

    private suspend fun putOutRecord(
        entryDirPath: String,
        outFilePath: String,
        title: String,
        cover: String,
        status: Int,
        message: String? = null,
        type: Int = OutRecord.TYPE_MP4,
    ) {
        val outRecordDao = appDatabase.outRecordDao()
        val record = outRecordDao.findByPath(entryDirPath, type)
        val currentTime = System.currentTimeMillis()
        if (record == null) {
            val newRecord = OutRecord(
                entryDirPath = entryDirPath,
                outFilePath = outFilePath,
                title = title,
                cover = cover,
                status = status,
                type = type,
                createTime = currentTime,
                updateTime = currentTime,
                message = message,
            )
            outRecordDao.insertAll(newRecord)
        } else {
            val newRecord = record.copy(
                entryDirPath = entryDirPath,
                outFilePath = outFilePath,
                title = title,
                cover = cover,
                status = status,
                message = message,
                type = type,
                updateTime = currentTime,
            )
            outRecordDao.update(newRecord)
        }
    }

    fun scanExportedAudio(outFilePath: String) {
        MediaScannerConnection.scanFile(
            this,
            arrayOf(outFilePath),
            arrayOf("audio/mpeg"),
        ) { path, uri ->
            MiaoLog.info { "Scanned exported audio: $path -> $uri" }
        }
    }

    fun tryAddOutRecord(
        entryDirPath: String,
        outFilePath: String,
        title: String,
        cover: String,
        type: Int = OutRecord.TYPE_MP4,
    ) {
        launch {
            putOutRecord(
                entryDirPath, outFilePath, title, cover,
                status = OutRecord.STATUS_SUCCESS,
                type = type,
            )
        }
    }

    suspend fun getRecordList(): List<OutRecord> {
        return appDatabase.outRecordDao().getAll()
    }

    suspend fun getRecordList(status: Int): List<OutRecord> {
        return appDatabase.outRecordDao().getAllByStatus(status)
    }

    suspend fun getRecordList(paths: Array<String>): List<OutRecord> {
        return appDatabase.outRecordDao().getAllByEntryDirPaths(paths)
    }

    suspend fun startTask(
        task: OutRecord,
    ) {
        val isSuccess = exportBiliVideo(
            task.entryDirPath,
            File(task.outFilePath),
            task.type,
        )
        if (isSuccess) {
            putOutRecord(
                task.entryDirPath,
                task.outFilePath,
                task.title,
                task.cover,
                status = OutRecord.STATUS_IN_PROGRESS,
                type = task.type,
            )
        }
    }

    private suspend fun startNextWaitingTask() = taskStartMutex.withLock {
        if (appState.taskStatus.value !is TaskStatus.InIdle) return@withLock
        val nextTask = appDatabase.outRecordDao()
            .getAllByStatus(OutRecord.STATUS_WAIT)
            .firstOrNull() ?: return@withLock
        startTask(nextTask)
    }

    suspend fun addTask(
        entryDirPath: String,
        outFilePath: String,
        title: String,
        cover: String,
        type: Int = OutRecord.TYPE_MP4,
    ) {
        val outRecordDao = appDatabase.outRecordDao()
        val record = outRecordDao.findByPath(entryDirPath, type)
        val currentTime = System.currentTimeMillis()
        if (record == null) {
            val newRecord = OutRecord(
                entryDirPath = entryDirPath,
                outFilePath = outFilePath,
                title = title,
                cover = cover,
                status = OutRecord.STATUS_WAIT,
                type = type,
                createTime = currentTime,
                updateTime = currentTime,
            )
            outRecordDao.insertAll(newRecord)
            toast("成功创建任务：${title}")
            startNextWaitingTask()
        } else {
            if (record.status == OutRecord.STATUS_SUCCESS) {
                toast("该视频已导出：${record.title}")
            } else {
                toast("该视频已在队列中：${record.title}")
            }
        }
    }

    suspend fun delTask(
        task: OutRecord,
        isDeleteFile: Boolean,
    ) {
        if (isDeleteFile) {
            val outFile = File(task.outFilePath)
            if (outFile.exists()) {
                outFile.delete()
            }
            appDatabase.outRecordDao().delete(task)
            withContext(Dispatchers.Main) {
                toast("已删除记录及文件${task.title}")
            }
        } else {
            appDatabase.outRecordDao().delete(task)
            withContext(Dispatchers.Main) {
                toast("已删除记录${task.title}")
            }
        }
    }

    private suspend fun toast(message: String) {
        val duration = if (message.length > 10) {
            Toast.LENGTH_LONG
        } else {
            Toast.LENGTH_SHORT
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(this@BiliDownService, message, duration)
                .show()
        }
    }

    private fun getTempPath(): String {
        var file = File(getExternalFilesDir(null), "../temp")
        if (!file.exists()) {
            file.mkdir()
        }
        return file.canonicalPath
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

}
