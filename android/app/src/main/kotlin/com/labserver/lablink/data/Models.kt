package com.labserver.lablink.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** What the server offers: models by provider (DeepSeek, ChatGPT...) and the thinking levels each one accepts. */
@Serializable
data class ModelInfo(val id: String, val name: String, val efforts: List<String> = emptyList())

@Serializable
data class ProviderInfo(val id: String, val name: String, val models: List<ModelInfo> = emptyList())

@Serializable
data class Catalog(val providers: List<ProviderInfo> = emptyList()) {
    fun find(provider: String, model: String): ModelInfo? =
        providers.firstOrNull { it.id == provider }?.models?.firstOrNull { it.id == model }

    fun providerName(provider: String): String = providers.firstOrNull { it.id == provider }?.name ?: provider
}

/** A choice of model and thinking level. Empty = "whatever the bot or the server defaults to". */
@Serializable
data class ModelPick(val provider: String = "", val model: String = "", val reasoning: String = "") {
    val isDefault: Boolean get() = provider.isEmpty() || model.isEmpty()
}

/** The thinking levels in the order they are shown, from the server's own words to ours. */
val EFFORT_ORDER = listOf("none", "low", "medium", "high", "xhigh", "max")

class CatalogCache(dir: File) {
    private val file = File(dir, "models.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Catalog = try {
        if (file.exists()) json.decodeFromString(file.readText()) else Catalog()
    } catch (_: Exception) {
        Catalog()
    }

    fun save(c: Catalog) {
        try {
            file.writeText(json.encodeToString(c))
        } catch (_: Exception) {
        }
    }
}
