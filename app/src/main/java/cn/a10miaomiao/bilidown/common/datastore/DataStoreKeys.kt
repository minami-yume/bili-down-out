package cn.a10miaomiao.bilidown.common.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

object DataStoreKeys {

    val appPackageNameSet = stringSetPreferencesKey("app_package_name_set")

    val enabledShizuku = booleanPreferencesKey("enabled_shizuku")

    val enabledZeroWidthPath = booleanPreferencesKey("enabled_zero_width_path")
}
