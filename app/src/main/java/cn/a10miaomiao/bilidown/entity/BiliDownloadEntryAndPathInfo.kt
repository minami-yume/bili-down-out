package cn.a10miaomiao.bilidown.entity

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class BiliDownloadEntryAndPathInfo(
    val pageDirPath: String,
    val entryDirPath: String,
    val entry: BiliDownloadEntryInfo,
    val rawEntryJson: String = "",
): Parcelable
