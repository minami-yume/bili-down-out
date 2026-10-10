package cn.a10miaomiao.bilidown.common

import android.os.Build

/** One deterministic backend is used for every Android/data operation in a flow. */
enum class AndroidDataAccessMode {
    SHIZUKU,
    ZERO_WIDTH,
    SAF,
    DIRECT_FILE;

    companion object {
        fun resolve(enabledShizuku: Boolean, enabledZeroWidthPath: Boolean): AndroidDataAccessMode {
            return when {
                enabledShizuku -> SHIZUKU
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> DIRECT_FILE
                enabledZeroWidthPath -> ZERO_WIDTH
                else -> SAF
            }
        }
    }
}
