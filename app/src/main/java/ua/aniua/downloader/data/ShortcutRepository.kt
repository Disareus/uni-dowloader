package ua.aniua.downloader.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ua.aniua.downloader.extractor.ExtractorUtils
import ua.aniua.downloader.model.SiteShortcut
import java.net.URI
import java.util.Locale
import java.util.UUID

class ShortcutRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getShortcuts(): MutableList<SiteShortcut> {
        val raw = prefs.getString(KEY_SHORTCUTS, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<SiteShortcut>>() {}.type
            gson.fromJson<MutableList<SiteShortcut>>(raw, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun addShortcut(rawUrl: String, customName: String? = null): SiteShortcut? {
        val normalizedUrl = normalizeAndValidateUrl(rawUrl) ?: return null
        val name = customName?.trim()?.takeIf { it.isNotEmpty() } ?: deriveSiteName(normalizedUrl)
        val list = getShortcuts()
        val existingIdx = list.indexOfFirst { it.url.equals(normalizedUrl, ignoreCase = true) }
        val shortcut = if (existingIdx != -1) {
            val updated = list[existingIdx].copy(name = name, url = normalizedUrl)
            list[existingIdx] = updated
            updated
        } else {
            val created = SiteShortcut(
                id = UUID.randomUUID().toString(),
                name = name,
                url = normalizedUrl
            )
            list.add(created)
            created
        }
        saveShortcuts(list)
        return shortcut
    }

    fun updateShortcut(id: String, rawUrl: String, customName: String? = null): SiteShortcut? {
        val normalizedUrl = normalizeAndValidateUrl(rawUrl) ?: return null
        val name = customName?.trim()?.takeIf { it.isNotEmpty() } ?: deriveSiteName(normalizedUrl)
        val list = getShortcuts()
        val idx = list.indexOfFirst { it.id == id }
        if (idx == -1) return null
        val updated = list[idx].copy(name = name, url = normalizedUrl)
        list[idx] = updated
        saveShortcuts(list)
        return updated
    }

    fun removeShortcut(id: String) {
        val list = getShortcuts()
        if (list.removeAll { it.id == id }) {
            saveShortcuts(list)
        }
    }

    private fun saveShortcuts(list: List<SiteShortcut>) {
        prefs.edit().putString(KEY_SHORTCUTS, gson.toJson(list)).apply()
    }

    companion object {
        private const val PREFS_NAME = "aniua_shortcuts_prefs"
        private const val KEY_SHORTCUTS = "saved_site_shortcuts"

        fun normalizeAndValidateUrl(rawUrl: String): String? {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = ExtractorUtils.ensureHttpsScheme(trimmed)
            return try {
                val uri = URI(withScheme)
                val host = uri.host
                if (host.isNullOrBlank() || !host.contains(".")) null else withScheme
            } catch (_: Exception) {
                null
            }
        }

        fun deriveSiteName(url: String): String {
            return try {
                val host = URI(ExtractorUtils.ensureHttpsScheme(url)).host
                    ?.lowercase(Locale.ROOT)
                    ?.removePrefix("www.")
                    ?: return "Сайт"
                val parts = host.split(".")
                val core = if (parts.size >= 2) parts[parts.size - 2] else host
                core.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
                }.ifEmpty { "Сайт" }
            } catch (_: Exception) {
                "Сайт"
            }
        }

        fun buildFaviconUrl(url: String): String? {
            return try {
                val host = URI(ExtractorUtils.ensureHttpsScheme(url)).host ?: return null
                "https://www.google.com/s2/favicons?sz=128&domain=$host"
            } catch (_: Exception) {
                null
            }
        }
    }
}
