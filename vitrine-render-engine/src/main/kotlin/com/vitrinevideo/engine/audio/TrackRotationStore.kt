package com.vitrinevideo.engine.audio

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.vitrinevideo.engine.interpreter.ApprovedTrack
import com.vitrinevideo.engine.interpreter.AudioProfileSpec
import com.vitrinevideo.engine.timeline.PreFlightValidator
import java.io.File

/**
 * Pure cyclic track selection algorithm (1 -> 2 -> 3 -> 4 -> 1) decoupled from Android SQLite
 * so it can be deterministically verified on both JVM and Android SQLite.
 */
object CyclicRotationAlgorithm {
    fun nextTrackNumber(lastDeliveredTrack: Int?, approvedNumbers: List<Int>): Int {
        require(approvedNumbers.isNotEmpty()) { "Aucune piste approuvée dans le profil audio" }
        if (lastDeliveredTrack == null) return approvedNumbers.first()
        val idx = approvedNumbers.indexOf(lastDeliveredTrack)
        return if (idx < 0) approvedNumbers.first() else approvedNumbers[(idx + 1) % approvedNumbers.size]
    }
}

/**
 * Generic strict A→B→C→D→A policy for owner-approved pools. It deliberately
 * lives outside any template: the caller supplies the approved numbers from the
 * package catalogue. State advances only after a validated success is committed.
 */
object StrictMusicRotation {
    fun next(lastDeliveredTrack: Int?, approvedNumbers: List<Int>): Int =
        CyclicRotationAlgorithm.nextTrackNumber(lastDeliveredTrack, approvedNumbers)

    fun assertSuccess(lastDeliveredTrack: Int?, approvedNumbers: List<Int>, candidate: Int) {
        val expected = next(lastDeliveredTrack, approvedNumbers)
        require(candidate == expected) {
            "Rotation stricte violée: attendu=$expected, reçu=$candidate"
        }
    }
}

interface RotationPersistence {
    fun findTrackForJob(profileId: String, runId: String): Int?
    fun getLastTrack(profileId: String): Int?
    fun recordSuccess(profileId: String, runId: String, trackNumber: Int)
    fun getHistory(profileId: String): List<Int> = emptyList()
}

/** Balanced random choice across playable tracks; consecutive deliveries never repeat. */
object BalancedAvailableTrackRotationAlgorithm {
    fun choose(availableNumbers: List<Int>, history: List<Int>, randomIndex: (Int) -> Int = { (0 until it).random() }): Int {
        val available = availableNumbers.distinct().sorted()
        require(available.isNotEmpty()) { "Aucune piste musicale disponible" }
        val previous = history.lastOrNull()
        val candidates = available.filter { it != previous }.ifEmpty { available }
        val counts = history.groupingBy { it }.eachCount()
        val minCount = candidates.minOf { counts[it] ?: 0 }
        val leastUsed = candidates.filter { (counts[it] ?: 0) == minCount }
        return leastUsed[randomIndex(leastUsed.size)]
    }
}

/**
 * Android SQLite transactional rotation store (`BEGIN IMMEDIATE` + commit ONLY after verified export).
 */
class AndroidSqliteRotationStore(
    context: Context,
    dbFile: File
) : SQLiteOpenHelper(context, dbFile.absolutePath, null, 1), RotationPersistence {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS livraisons (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                profil TEXT NOT NULL,
                job TEXT NOT NULL UNIQUE,
                piste INTEGER NOT NULL,
                horodatage INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun findTrackForJob(profileId: String, runId: String): Int? {
        writableDatabase.rawQuery(
            "SELECT piste FROM livraisons WHERE profil = ? AND job = ? LIMIT 1",
            arrayOf(profileId, runId)
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else null
        }
    }

    override fun getLastTrack(profileId: String): Int? {
        writableDatabase.rawQuery(
            "SELECT piste FROM livraisons WHERE profil = ? ORDER BY seq DESC LIMIT 1",
            arrayOf(profileId)
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else null
        }
    }

    override fun getHistory(profileId: String): List<Int> {
        writableDatabase.rawQuery(
            "SELECT piste FROM livraisons WHERE profil = ? ORDER BY seq ASC",
            arrayOf(profileId)
        ).use { c ->
            val rows = mutableListOf<Int>()
            while (c.moveToNext()) rows += c.getInt(0)
            return rows
        }
    }

    override fun recordSuccess(profileId: String, runId: String, trackNumber: Int) {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            db.execSQL(
                "INSERT OR IGNORE INTO livraisons(profil, job, piste, horodatage) VALUES(?, ?, ?, ?)",
                arrayOf(profileId, runId, trackNumber, System.currentTimeMillis())
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

class TrackRotationCoordinator(
    private val store: RotationPersistence,
    private val resourcesDir: File
) {
    fun selectTrackForRender(profile: AudioProfileSpec, runId: String): Pair<ApprovedTrack, File> {
        val existing = store.findTrackForJob(profile.id, runId)
        val availableNumbers = profile.approvedNumbers.filter { n -> profile.tracks.any { it.number == n } }
        require(availableNumbers.isNotEmpty()) { "Aucune piste approuvée avec fichier disponible pour ${profile.id}" }
        val trackNumber = existing ?: if (profile.rotationMode.contains("équilibré", ignoreCase = true) || profile.rotationMode.contains("balanced", ignoreCase = true)) {
            BalancedAvailableTrackRotationAlgorithm.choose(availableNumbers, store.getHistory(profile.id))
        } else {
            CyclicRotationAlgorithm.nextTrackNumber(
                lastDeliveredTrack = store.getLastTrack(profile.id),
                approvedNumbers = availableNumbers
            )
        }
        val track = profile.tracks.firstOrNull { it.number == trackNumber }
            ?: throw IllegalStateException("Piste #$trackNumber absente ou non redistribuable dans le profil ${profile.id}")

        val file = resolveTrackFile(track)
        require(file.exists()) { "Fichier audio introuvable: ${file.path}" }
        if (track.sha256.isNotEmpty()) {
            val actualSha = PreFlightValidator.sha256File(file)
            require(actualSha.equals(track.sha256, ignoreCase = true)) {
                "Empreinte SHA-256 audio invalide pour ${file.name}"
            }
        }
        return track to file
    }

    fun commitAfterVerifiedRender(
        profileId: String,
        runId: String,
        trackNumber: Int,
        approvedNumbers: List<Int> = emptyList()
    ) {
        if (approvedNumbers.isNotEmpty()) {
            // Replaying the same successful job must not advance the cycle, and a
            // failed render never reaches this method (no commit, no state change).
            if (store.findTrackForJob(profileId, runId) != null) return
            StrictMusicRotation.assertSuccess(store.getLastTrack(profileId), approvedNumbers, trackNumber)
        }
        store.recordSuccess(profileId, runId, trackNumber)
    }

    private fun resolveTrackFile(track: ApprovedTrack): File {
        val direct = File(resourcesDir, track.relativePath)
        if (direct.exists()) return direct
        return File(resourcesDir, "audio/${track.relativePath}")
    }
}
