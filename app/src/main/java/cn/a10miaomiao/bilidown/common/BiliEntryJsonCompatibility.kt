package cn.a10miaomiao.bilidown.common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Shapes migrated metadata after a native entry.json from the target client. */
object BiliEntryJsonCompatibility {
    private val json = Json { ignoreUnknownKeys = true }
    private val bvidAlphabet = "fZodR9XQDSUm21yCkr6zBqiveYah8bt4xsWpHnJE7jL5VG3guMTKNPAwcF"
    private val bvidPositions = intArrayOf(11, 10, 3, 8, 4, 6)
    private val bvidAdd = 8728348608L
    private val bvidXor = 177451812L

    /** Keeps exactly the target template's field structure and copies matching source values. */
    fun convertForMigration(rawJson: String, targetTemplateJson: String): String {
        val source = json.parseToJsonElement(rawJson) as? JsonObject
            ?: throw IllegalArgumentException("源 entry.json 不是 JSON 对象")
        val template = json.parseToJsonElement(targetTemplateJson) as? JsonObject
            ?: throw IllegalArgumentException("目标客户端 entry 模板不是 JSON 对象")
        return mergeTemplate(template, source).toString()
    }

    private fun mergeTemplate(template: JsonElement, source: JsonElement?): JsonElement {
        if (template is JsonObject) {
            val sourceObject = source as? JsonObject
            return JsonObject(template.mapValues { (key, templateValue) ->
                val sourceValue = sourceObject?.get(key)
                when {
                    sourceValue == null && key == "bvid" && sourceObject != null -> {
                        val avid = (sourceObject["avid"] as? JsonPrimitive)?.longOrNull
                        if (avid != null && avid > 0L) JsonPrimitive(avToBv(avid)) else templateValue
                    }
                    sourceValue == null -> templateValue
                    else -> mergeTemplate(templateValue, sourceValue)
                }
            })
        }
        if (template is JsonArray) {
            val sourceArray = source as? JsonArray ?: return template
            return JsonArray(template.mapIndexed { index, element ->
                mergeTemplate(element, sourceArray.getOrNull(index))
            })
        }
        return source?.let { adaptValueToTemplate(template, it) } ?: template
    }

    private fun adaptValueToTemplate(template: JsonElement, source: JsonElement): JsonElement {
        val templateNumber = (template as? JsonPrimitive)?.longOrNull ?: return source
        val sourceNumber = (source as? JsonPrimitive)?.longOrNull ?: return source
        // Bilibili timestamps use seconds in some clients and milliseconds in others.
        if (kotlin.math.abs(templateNumber) < 100_000_000_000L && kotlin.math.abs(sourceNumber) >= 100_000_000_000L) {
            return JsonPrimitive(sourceNumber / 1000L)
        }
        if (kotlin.math.abs(templateNumber) >= 100_000_000_000L && kotlin.math.abs(sourceNumber) < 100_000_000_000L) {
            return JsonPrimitive(sourceNumber * 1000L)
        }
        return source
    }

    private fun avToBv(avid: Long): String {
        var value = (avid xor bvidXor) + bvidAdd
        val result = "BV1  4 1 7  ".toCharArray()
        for (position in bvidPositions) {
            result[position] = bvidAlphabet[(value % 58L).toInt()]
            value /= 58L
        }
        return String(result)
    }
}
