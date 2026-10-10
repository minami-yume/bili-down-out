package cn.a10miaomiao.bilidown.shizuku.service

import android.content.Context
import android.os.RemoteException
import android.system.Os
import android.util.Log
import androidx.annotation.Keep
import cn.a10miaomiao.bilidown.callback.ProgressCallback
import cn.a10miaomiao.bilidown.common.BiliEntryJsonParser
import cn.a10miaomiao.bilidown.common.BiliEntryJsonCompatibility
import cn.a10miaomiao.bilidown.common.CommandUtil
import cn.a10miaomiao.bilidown.common.MiaoLog
import cn.a10miaomiao.bilidown.entity.BiliDownloadEntryAndPathInfo
import cn.a10miaomiao.bilidown.entity.VideoOutInfo
import cn.a10miaomiao.bilidown.shizuku.IUserService
import io.microshow.rxffmpeg.RxFFmpegCommandList
import io.microshow.rxffmpeg.RxFFmpegInvoke
import io.microshow.rxffmpeg.RxFFmpegSubscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.system.exitProcess


class UserService : IUserService.Stub, CoroutineScope {

    private val job: Job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    /**
     * Constructor is required.
     */
    constructor() {
        Log.i("UserService", "constructor")
    }

    /**
     * Constructor with Context. This is only available from Shizuku API v13.
     *
     * This method needs to be annotated with [Keep] to prevent ProGuard from removing it.
     *
     * @param context Context created with createPackageContextAsUser
     * @see [code used to create the instance of this class](https://github.com/RikkaApps/Shizuku-API/blob/672f5efd4b33c2441dbf609772627e63417587ac/server-shared/src/main/java/rikka/shizuku/server/UserService.java.L66)
     */
    @Keep
    constructor(context: Context) {
        Log.i("UserService", "constructor with Context: context=$context")
    }

    /**
     * Reserved destroy method
     */
    override fun destroy() {
        Log.i("UserService", "destroy")
        job.cancel()
        exitProcess(0)
    }

    override fun exit() {
        destroy()
    }

    @Throws(RemoteException::class)
    override fun doSomething(): String {
        Log.i("UserService", "doSomething, pid=${Os.getpid()}, uid=${Os.getuid()}")
        return "pid=${Os.getpid()}, uid=${Os.getuid()}"
    }

    @Throws(RemoteException::class)
    override fun readDownloadList(path: String?): MutableList<BiliDownloadEntryAndPathInfo> {
        if (path.isNullOrBlank()) {
            return mutableListOf()
        }
        MiaoLog.debug { "readDownloadList(${path})" }
        val dir = File(path)
        val list = mutableListOf<BiliDownloadEntryAndPathInfo>()
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles { file -> file.isDirectory }
                ?.forEach {
                    MiaoLog.debug { it.path }
                    list.addAll(readDownloadDirectory(it.path))
                }
        }
        return list
    }

    @Throws(RemoteException::class)
    override fun readDownloadDirectory(path: String?): MutableList<BiliDownloadEntryAndPathInfo> {
        if (path.isNullOrBlank()) {
            return mutableListOf()
        }
        val dir = File(path)
        val list = mutableListOf<BiliDownloadEntryAndPathInfo>()
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles { file -> file.isDirectory }
                ?.forEach { entryDir ->
                    val entryFile = File(entryDir, "entry.json")
                    if (entryFile.exists()) {
                        val entryJson = entryFile.readText()
                        val entry = BiliEntryJsonParser.parseOrNull(entryJson)
                            ?: return@forEach
                        list.add(
                            BiliDownloadEntryAndPathInfo(
                                entry = entry,
                                entryDirPath = entryDir.path,
                                pageDirPath = dir.path,
                                rawEntryJson = entryJson,
                            )
                        )
                    }
                }
        }
        return list
    }

    override fun exportBiliVideo(
        entryDirPath: String?,
        outFilePath: String?,
        callback: ProgressCallback?
    ): String? {
        if (entryDirPath.isNullOrBlank() || outFilePath.isNullOrBlank()) {
            return "文件路径不能为空"
        }
        val entryDirFile = File(entryDirPath)
        val entryJsonFile = File(entryDirPath, "entry.json")
        if (!entryJsonFile.exists()) {
            return "entry.json 文件不存在"
        }
        val outFile = File(outFilePath)
        val entry = BiliEntryJsonParser.parseOrNull(entryJsonFile.readText())
            ?: return "entry.json 文件为空或已损坏"
        val videoDirPath = "$entryDirPath/${entry.videoDirName}"
        val videoDir = File(videoDirPath)
        val videoOutInfo = VideoOutInfo(
            entryDirPath = entryDirPath,
            outFilePath = outFilePath,
            name = outFile.name,
            cover = entry.cover,
        )
        val myRxFFmpegSubscriber = ShizukuRxFFmpegSubscriber(
            videoOutInfo, callback
        )
        if (!videoDir.exists() || !videoDir.isDirectory) {
            val videoFile = entryDirFile.listFiles()?.find {
                it.isFile && it.name.startsWith(entry.videoDirName)
                        && it.name.endsWith(".mp4")
            }
            if (videoFile == null) {
                return "找不到视频文件夹：${entry.videoDirName}"
            }
            // 直接复制mp4文件
            callback?.onStart(videoOutInfo)
            launch {
                copyFile(videoFile, outFile)
                callback?.onFinish(videoOutInfo)
            }
            return null
        }
        if (entry.media_type == 1) {
            // 多段blv(flv)视频
            val blvFiles = mutableListOf<File>()
            var blvIndex = 0
            while (true) {
                val file = File(videoDir, "${blvIndex}.blv")
                if (file.exists()) {
                    blvFiles.add(file)
                    blvIndex++
                } else {
                    break
                }
            }
            if (blvFiles.isEmpty()) {
                return "找不到视频文件：0.blv"
            }
            callback?.onStart(videoOutInfo)
            mergerVideos(
                blvFiles,
                outFile,
                myRxFFmpegSubscriber,
            )
            return null
        } else {
            val videoFile = File(videoDir, "video.m4s")
            val audioFile = File(videoDir, "audio.m4s")
            if (!videoFile.exists()) {
                return "找不到视频文件"
            }
            if (!audioFile.exists()) {
                callback?.onStart(videoOutInfo)
                launch {
                    copyFile(videoFile, outFile)
                    callback?.onFinish(videoOutInfo)
                }
                return null
            }
            callback?.onStart(videoOutInfo)
            mergerVideoAndAudio(videoFile, audioFile, outFile, myRxFFmpegSubscriber)
            return null
        }
    }

    override fun exportBiliAudio(
        entryDirPath: String?,
        outFilePath: String?,
        callback: ProgressCallback?
    ): String? {
        if (entryDirPath.isNullOrBlank() || outFilePath.isNullOrBlank()) return "文件路径不能为空"
        val entryDir = File(entryDirPath)
        val entry = BiliEntryJsonParser.parseOrNull(File(entryDir, "entry.json").takeIf { it.exists() }?.readText() ?: "")
            ?: return "entry.json 文件为空或已损坏"
        val outFile = File(outFilePath)
        val videoDir = File(entryDir, entry.videoDirName)
        val audioFile = File(videoDir, "audio.m4s")
        if (!audioFile.isFile) return "找不到 audio.m4s，当前缓存格式无法直接导出音频"

        val info = VideoOutInfo(entryDirPath, outFilePath, outFile.name, entry.cover, 2)
        val coverFile = File(entryDir, "cover.jpg").takeIf { it.isFile && it.length() > 0 }
        outFile.parentFile?.mkdirs()
        if (coverFile == null) {
            callback?.onStart(info)
            launch {
                try {
                    copyFile(audioFile, outFile)
                    callback?.onFinish(info)
                } catch (e: Exception) {
                    outFile.delete()
                    callback?.onError(info, e.message ?: "复制音频缓存失败")
                }
            }
            return null
        }

        val commands = RxFFmpegCommandList().apply {
            append("-y")
            append("-i"); append(audioFile.absolutePath)
            append("-i"); append(coverFile.absolutePath)
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
        callback?.onStart(info)
        RxFFmpegInvoke.getInstance().runCommandRxJava(commands)
            .subscribe(ShizukuRxFFmpegSubscriber(info, callback))
        return null
    }

    override fun migrateCache(sourceDirectoryPath: String?, targetDirectoryPath: String?, targetEntryTemplate: String?): String? {
        if (sourceDirectoryPath.isNullOrBlank() || targetDirectoryPath.isNullOrBlank() || targetEntryTemplate.isNullOrBlank()) return "目录路径或目标 entry 模板不能为空"
        val source = File(sourceDirectoryPath)
        val target = File(targetDirectoryPath)
        if (!source.isDirectory) return "Shizuku 无法访问源缓存目录：$sourceDirectoryPath"
        if (target.exists()) return "目标客户端已有同名缓存目录，为避免覆盖已取消迁移"
        return try {
            target.parentFile?.mkdirs()
            source.copyRecursively(target, overwrite = false)
            target.walkTopDown().filter { it.isFile && it.name == "entry.json" }.forEach { entryFile ->
                entryFile.writeText(BiliEntryJsonCompatibility.convertForMigration(entryFile.readText(), targetEntryTemplate))
            }
            target.walkTopDown().forEach {
                it.setReadable(true, false)
                it.setWritable(true, false)
                if (it.isDirectory) it.setExecutable(true, false)
            }
            null
        } catch (e: Exception) {
            target.deleteRecursively()
            e.message ?: "复制缓存目录失败"
        }
    }

    override fun deleteCacheEntry(sourceDirectoryPath: String?, sourcePackageName: String?): String? {
        if (sourceDirectoryPath.isNullOrBlank() || sourcePackageName.isNullOrBlank()) return "缓存路径不能为空"
        val path = sourceDirectoryPath.replace("/storage/emulated/0\u200B/Android/data/", "/storage/emulated/0/Android/data/")
        val relativeDownloadPath = if (sourcePackageName == "com.example.piliplus") "files/download" else "download"
        val allowedRoot = "/storage/emulated/0/Android/data/$sourcePackageName/$relativeDownloadPath/"
        if (!path.startsWith(allowedRoot) || !path.removePrefix(allowedRoot).matches(Regex("\\d+"))) {
            return "删除路径必须是下载目录下按视频 ID 命名的文件夹"
        }
        val directory = File(path)
        if (!directory.isDirectory) return "找不到要删除的视频缓存目录"
        return try {
            if (directory.deleteRecursively()) null else "无法完整删除缓存目录"
        } catch (e: Exception) {
            e.message ?: "删除缓存目录失败"
        }
    }

    private suspend fun copyFile(
        inputFile: File,
        outFile: File
    ) = withContext(Dispatchers.IO) {
        outFile.parentFile?.mkdirs()
        inputFile.copyTo(outFile, overwrite = true)
    }

    private fun mergerVideoAndAudio(
        videoFile: File,
        audioFile: File,
        outFile: File,
        subscriber: RxFFmpegSubscriber,
    ) {
        outFile.parentFile?.mkdirs()
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
        // 开始执行FFmpeg命令
        RxFFmpegInvoke.getInstance()
            .runCommandRxJava(commands)
            .subscribe(subscriber)
    }

    private fun mergerVideos(
        videoFiles: List<File>,
        outFile: File,
        subscriber: RxFFmpegSubscriber,
    ) {
        outFile.parentFile?.mkdirs()
        val ffTxtFile = File(outFile.parent, ".ff.txt")
        if (ffTxtFile.exists()) {
            ffTxtFile.deleteRecursively()
        }
        val ffTxtContent = videoFiles.joinToString("\n") { file ->
            "file ${CommandUtil.filePath(file)}"
        }
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
        // 开始执行FFmpeg命令
        RxFFmpegInvoke.getInstance()
            .runCommandRxJava(commands)
            .subscribe(subscriber)
    }

}
