package com.andre.scanner.storage

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HistoryEntry(
    val name: String,
    val fileName: String,
    val pages: Int,
    val sizeBytes: Long,
    val createdAt: Long,
)

/** Histórico dos PDFs gerados, num JSON simples. Sem banco de dados. */
class HistoryStore(context: Context, private val pdfDir: File) {
    private val file = File(context.filesDir, "history.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    @Synchronized
    fun load(): List<HistoryEntry> {
        val list = runCatching { json.decodeFromString<List<HistoryEntry>>(file.readText()) }.getOrDefault(emptyList())
        // Some da lista o que não existe mais no disco.
        return list.filter { fileOf(it).exists() }
    }

    @Synchronized
    fun add(entry: HistoryEntry): List<HistoryEntry> =
        (listOf(entry) + load().filter { it.fileName != entry.fileName }).also(::save)

    @Synchronized
    fun remove(entry: HistoryEntry): List<HistoryEntry> {
        fileOf(entry).delete()
        return load().filter { it.fileName != entry.fileName }.also(::save)
    }

    fun fileOf(entry: HistoryEntry) = File(pdfDir, entry.fileName)

    private fun save(list: List<HistoryEntry>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(list))
        tmp.renameTo(file)
    }
}
