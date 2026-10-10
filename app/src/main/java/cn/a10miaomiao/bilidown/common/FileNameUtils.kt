package cn.a10miaomiao.bilidown.common

object FileNameUtils {
    private val unsupportedCharacters = Regex("[\\u0000-\\u001F\\u007F<>:\"/\\\\|?*]")
    private val windowsReservedName = Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$")

    fun sanitize(name: String): String {
        val cleaned = name.replace(unsupportedCharacters, "").trimEnd(' ', '.')
        return if (windowsReservedName.matches(cleaned)) "_$cleaned" else cleaned
    }

    fun isValid(name: String): Boolean {
        return name.isNotBlank() &&
            !unsupportedCharacters.containsMatchIn(name) &&
            name != "." && name != ".." &&
            !name.endsWith(' ') && !name.endsWith('.') &&
            !windowsReservedName.matches(name)
    }
}
