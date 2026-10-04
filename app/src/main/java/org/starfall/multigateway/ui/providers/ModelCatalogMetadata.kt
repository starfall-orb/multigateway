package org.starfall.multigateway.ui.providers

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val catalogMetadataJson = Json { prettyPrint = true }
private val camelCaseBoundary = Regex("([a-z])([A-Z])")

internal fun formatCatalogMetadata(key: String, value: JsonElement, zone: ZoneId = ZoneId.systemDefault()): String {
    fun formatted(field: String, element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (name, child) -> formatted(name, child) })
        is JsonArray -> JsonArray(element.map { formatted(field, it) })
        is JsonPrimitive -> {
            val normalized = field.replace(camelCaseBoundary, "$1_$2").lowercase(Locale.ROOT)
            val timestampField = normalized in setOf("created", "updated", "modified", "published", "released", "expires", "timestamp", "date") ||
                normalized.endsWith("_at") || normalized.endsWith("_time") || normalized.endsWith("_timestamp") || normalized.endsWith("_date")
            val epoch = element.content.toLongOrNull()
            if (!timestampField || epoch == null) element else runCatching {
                val instant = if (epoch >= 100_000_000_000L || epoch <= -100_000_000_000L) Instant.ofEpochMilli(epoch) else Instant.ofEpochSecond(epoch)
                JsonPrimitive(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).withZone(zone).format(instant))
            }.getOrDefault(element)
        }
    }
    val rendered = formatted(key, value)
    return if (rendered is JsonPrimitive && rendered.isString) rendered.content
        else catalogMetadataJson.encodeToString(JsonElement.serializer(), rendered)
}
