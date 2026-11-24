package com.spotifyme.core.engine

import com.spotifyme.core.api.HttpClient
import com.spotifyme.core.model.AudioFeatures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Persistent cache of audio features so each track is looked up only once.
 * Also remembers misses for a while so unknown tracks are not re-queried on every refresh.
 *
 * @param file where to persist; null keeps the cache in memory only (tests).
 */
class FeatureCache(
    private val file: File?,
    private val missTtlMillis: Long = 14L * 24 * 3600 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class Stored(
        val features: MutableMap<String, AudioFeatures> = mutableMapOf(),
        /** Spotify ID -> time of the failed lookup (epoch millis). */
        val misses: MutableMap<String, Long> = mutableMapOf(),
    )

    private val mutex = Mutex()
    private var stored: Stored = file?.takeIf { it.exists() }
        ?.let { runCatching { HttpClient.json.decodeFromString<Stored>(it.readText()) }.getOrNull() }
        ?: Stored()

    suspend fun features(id: String): AudioFeatures? = mutex.withLock { stored.features[id] }

    /** IDs that have no cached features and were not recently looked up without success. */
    suspend fun idsNeedingLookup(ids: List<String>): List<String> = mutex.withLock {
        val now = clock()
        ids.filter { id ->
            if (stored.features[id] != null) return@filter false
            val missed = stored.misses[id]
            missed == null || now - missed >= missTtlMillis
        }
    }

    suspend fun store(features: AudioFeatures, id: String) = mutex.withLock {
        // Never replace full mood data with BPM-only data.
        val existing = stored.features[id]
        if (existing != null && existing.hasMoodData && !features.hasMoodData) return@withLock
        stored.features[id] = features
        stored.misses.remove(id)
    }

    suspend fun recordMiss(id: String) = mutex.withLock {
        stored.misses[id] = clock()
    }

    suspend fun save() {
        val target = file ?: return
        val text = mutex.withLock { HttpClient.json.encodeToString(stored) }
        withContext(Dispatchers.IO) {
            // A failed cache write only costs extra lookups next time.
            runCatching {
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(target)) {
                    target.writeText(text)
                    tmp.delete()
                }
            }
        }
    }

    suspend fun clear() {
        mutex.withLock { stored = Stored() }
        save()
    }
}
