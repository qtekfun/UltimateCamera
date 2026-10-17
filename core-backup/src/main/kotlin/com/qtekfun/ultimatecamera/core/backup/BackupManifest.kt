package com.qtekfun.ultimatecamera.core.backup

import com.qtekfun.ultimatecamera.core.settings.SettingValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** The phone a backup was made on. [fingerprint] decides whether the capability results apply (D65). */
@Serializable
data class BackupDevice(val manufacturer: String, val model: String, val fingerprint: String)

/** One zip entry as the manifest promises it: the reader checks every byte against [sha256]. */
@Serializable
data class BackupEntry(val name: String, val bytes: Long, val sha256: String)

/** The layouts inside the backup; any newer than this app knows makes the import refuse the backup. */
@Serializable
data class BackupSchemas(val settings: Int, val lutIndex: Int, val capabilities: Int? = null)

/** `manifest.json`, the first entry of every backup. */
@Serializable
data class BackupManifest(
    val format: String = FORMAT,
    val formatVersion: Int = FORMAT_VERSION,
    val appVersion: String,
    val appVersionCode: Long,
    /** ISO-8601 instant. */
    val createdAt: String,
    val device: BackupDevice,
    val schemas: BackupSchemas,
    val entries: List<BackupEntry>
) {
    companion object {
        const val FORMAT = "ultimatecamera-backup"
        const val FORMAT_VERSION = 1
    }
}

/** The typed settings file (`settings.json`): every DataStore key as `{ "type", "value" }`, never the raw protobuf. */
@Serializable
data class SettingsFile(val schemaVersion: Int, val values: Map<String, TypedValue>)

@Serializable
data class TypedValue(val type: String, val value: JsonElement)

internal object BackupJson {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(value: SettingValue): TypedValue = when (value) {
        is SettingValue.Bool -> TypedValue("boolean", JsonPrimitive(value.value))
        is SettingValue.IntValue -> TypedValue("int", JsonPrimitive(value.value))
        is SettingValue.LongValue -> TypedValue("long", JsonPrimitive(value.value))
        is SettingValue.FloatValue -> TypedValue("float", JsonPrimitive(value.value))
        is SettingValue.DoubleValue -> TypedValue("double", JsonPrimitive(value.value))
        is SettingValue.Text -> TypedValue("string", JsonPrimitive(value.value))
        is SettingValue.TextSet -> TypedValue("stringSet", JsonArray(value.value.sorted().map(::JsonPrimitive)))
    }

    /** Null for a type this version does not know (left out of the import rather than failing it). */
    fun decode(typed: TypedValue): SettingValue? {
        val v = typed.value
        return when (typed.type) {
            "boolean" -> SettingValue.Bool(v.jsonPrimitive.boolean)
            "int" -> SettingValue.IntValue(v.jsonPrimitive.int)
            "long" -> SettingValue.LongValue(v.jsonPrimitive.long)
            "float" -> SettingValue.FloatValue(v.jsonPrimitive.float)
            "double" -> SettingValue.DoubleValue(v.jsonPrimitive.double)
            "string" -> SettingValue.Text(v.jsonPrimitive.content)
            "stringSet" -> SettingValue.TextSet(v.jsonArray.map { it.jsonPrimitive.content }.toSet())
            else -> null
        }
    }
}
