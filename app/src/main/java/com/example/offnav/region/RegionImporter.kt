package com.example.offnav.region

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

class RegionImporter(
    context: Context,
    private val store: RegionStore,
) {
    private val appContext = context.applicationContext

    /**
     * Streams, verifies, stages and atomically publishes a `.offnav` bundle.
     * The live region is never read, written, moved or deleted by this method.
     *
     * On *any* failure: all streams closed, staging removed, every partially written byte
     * of the new region deleted, exception raised. The caller publishes the failure state.
     */
    fun import(uri: Uri, onProgress: (copied: Long, total: Long) -> Unit): RegionSnapshot.Installed {
        store.ensureDirs()
        val staging = store.newStagingDir()
        var published: File? = null

        try {
            val input = runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
                ?: importFailure("Bundle not found or cannot be opened")

            val budget = ByteBudget(BundleSpec.MAX_BUNDLE_BYTES)
            val manifest = input.use { raw ->
                ZipInputStream(raw.buffered(BundleSpec.IO_BUFFER)).use { zip ->
                    readBundle(zip, staging, budget, onProgress)
                }
            }

            // ── post-copy validation, still entirely inside staging ──
            val ghz = File(staging, BundleSpec.ROUTING_ENTRY)
            RoutingGraphArchive.extract(ghz, File(staging, "routing"), ByteBudget(BundleSpec.MAX_BUNDLE_BYTES))
            check(ghz.delete()) { "Could not clean up routing archive" }

            validateSqlite(File(staging, BundleSpec.SEARCH_ENTRY), "search.db") { db ->
                if (db.version != manifest.searchSchema) {
                    importFailure(
                        "search.db schema is ${db.version}, manifest declares ${manifest.searchSchema}"
                    )
                }
                requireTable(db, "places", "search.db")
            }
            validateSqlite(File(staging, BundleSpec.TILES_ENTRY), "tiles.mbtiles") { db ->
                requireTable(db, "tiles", "tiles.mbtiles")
                requireTable(db, "metadata", "tiles.mbtiles")
            }

            store.writeDescriptor(staging, manifest)
            RegionStore.fsyncDir(File(staging, "routing"))
            RegionStore.fsyncDir(staging)

            // ── measure what we actually wrote, before the rename ──
            val installedBytes = safeDiskUsage(staging)
            store.writeDescriptor(staging, RegionDescriptor.of(manifest, installedBytes))
            RegionStore.fsyncDir(File(staging, "routing"))
            RegionStore.fsyncDir(staging)

            val installId = store.newInstallId(manifest.regionId, manifest.version)
            val target = store.installDir(installId)
            if (target.exists()) target.deleteRecursively()
            if (!staging.renameTo(target)) importFailure("Could not finalize the new region")
            published = target
            RegionStore.fsyncDir(store.root)

            // Replace any older version of this logical region while preserving other metros.
            val nextSelection = store.readSelection()
                .filterNot { pointer -> store.regionIdFor(pointer) == manifest.regionId }
                .plus(installId)
            store.publishSelection(nextSelection)

            Log.i(TAG, "Imported ${manifest.displayName} (${manifest.regionId}@${manifest.version}) as $installId")
            return RegionSnapshot.Installed(
                installId = installId,
                regionId = manifest.regionId,
                displayName = manifest.displayName,
                version = manifest.version,
                searchSchema = manifest.searchSchema,
                bounds = manifest.bounds,
                installedBytes = installedBytes,
                dir = target,
            )
        } catch (c: CancellationException) {
            cleanup(staging, published)
            throw c
        } catch (t: Throwable) {
            cleanup(staging, published)
            throw t
        }
    }

    /** Nothing here can touch the live region: both paths are exclusive to this import. */
    private fun cleanup(staging: File, published: File?) {
        runCatching { staging.deleteRecursively() }
        runCatching { published?.deleteRecursively() }   // pointer was never flipped if we got here
    }

    // ── zip walk ─────────────────────────────────────────────────────────

    private fun readBundle(
        zip: ZipInputStream,
        staging: File,
        budget: ByteBudget,
        onProgress: (Long, Long) -> Unit,
    ): RegionManifest {
        val first = zip.nextEntry ?: importFailure("Bundle is empty or not a .offnav file")
        requireEntry(first, BundleSpec.MANIFEST_ENTRY)
        val manifestBytes = readManifestBytes(zip)
        budget.consume(manifestBytes.size.toLong())
        zip.closeEntry()

        val manifest = ManifestParser.parse(manifestBytes)
        requireFreeSpace(manifest)

        val total = manifest.declaredPayloadBytes
        var copied = 0L
        val specs = mapOf(
            BundleSpec.TILES_ENTRY to manifest.tiles,
            BundleSpec.ROUTING_ENTRY to manifest.routing,
            BundleSpec.SEARCH_ENTRY to manifest.search,
        )

        for (name in BundleSpec.ORDERED_PAYLOAD) {
            val entry = zip.nextEntry ?: importFailure("Bundle is missing \"$name\"")
            requireEntry(entry, name)
            copyVerified(zip, File(staging, name), name, specs.getValue(name), budget) { delta ->
                copied += delta
                onProgress(copied, total)
            }
            zip.closeEntry()
        }

        if (zip.nextEntry != null) importFailure("Bundle contains unexpected extra entries")
        return manifest
    }

    /**
     * Exact-literal comparison. This is what rejects directories, duplicates, reordering,
     * absolute paths, `..`, `\`, `C:\…`, comma-separated names and anything else exotic:
     * none of them are byte-equal to the expected ASCII constant.
     */
    private fun requireEntry(entry: ZipEntry, expected: String) {
        if (entry.isDirectory || entry.name != expected) {
            importFailure("Bundle layout is invalid (expected \"$expected\")")
        }
    }

    private fun readManifestBytes(zip: InputStream): ByteArray {
        val cap = BundleSpec.MAX_MANIFEST_BYTES.toInt()
        val buffer = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            if (out.size() + n > cap) importFailure("Bundle manifest exceeds 64 KB")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * The *only* size authority: bytes actually read from the stream, counted while the
     * SHA-256 is computed over the same bytes. `ZipEntry.getSize()` is ignored entirely.
     */
    private fun copyVerified(
        src: InputStream,
        dest: File,
        name: String,
        spec: PayloadSpec,
        budget: ByteBudget,
        onDelta: (Long) -> Unit,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        val buffer = ByteArray(BundleSpec.IO_BUFFER)

        FileOutputStream(dest).use { fos ->
            val sink = fos.buffered(BundleSpec.IO_BUFFER)
            while (true) {
                val n = src.read(buffer)
                if (n < 0) break
                written += n
                if (written > spec.bytes) importFailure("\"$name\" is larger than the manifest declares")
                budget.consume(n.toLong())                       // aborts the instant 4 GB is crossed
                digest.update(buffer, 0, n)
                sink.write(buffer, 0, n)
                onDelta(n.toLong())
            }
            sink.flush()
            fos.fd.sync()
        }

        if (written != spec.bytes) importFailure("\"$name\" is truncated")
        if (digest.digest().toHex() != spec.sha256) importFailure("\"$name\" failed its SHA-256 check")
    }

    // ── validation helpers ───────────────────────────────────────────────

    private fun requireFreeSpace(manifest: RegionManifest) {
        val stat = StatFs(store.filesDir.absolutePath)
        val needed = manifest.declaredPayloadBytes + manifest.routing.bytes * 3 + 128L * 1024 * 1024
        if (stat.availableBytes < needed) {
            importFailure("Not enough free storage to import ${manifest.displayName}")
        }
    }

    private inline fun validateSqlite(file: File, label: String, check: (SQLiteDatabase) -> Unit) {
        val db = runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrElse { importFailure("\"$label\" is not a readable database", it) }
        db.use(check)
    }

    private fun requireTable(db: SQLiteDatabase, table: String, label: String) {
        val exists = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type IN ('table','view') AND name = ? LIMIT 1",
            arrayOf(table)
        ).use { it.moveToFirst() }
        if (!exists) importFailure("\"$label\" is missing the \"$table\" table")
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    private companion object { const val TAG = "RegionImporter" }
}
