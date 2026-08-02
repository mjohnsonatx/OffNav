package com.example.offnav.region

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.Locale

/**
 * Owns `filesDir/regions`. Everything lives on the same filesystem as the live region,
 * so staging -> published is a single `rename(2)`.
 */
class RegionStore(context: Context) {

    private val appContext = context.applicationContext
    val filesDir: File = appContext.filesDir

    val root = File(filesDir, "regions")
    private val stagingRoot = File(root, STAGING)
    private val pointerFile = File(root, POINTER)
    private val pointerTmp = File(root, "$POINTER.tmp")

    private val random = SecureRandom()

    fun ensureDirs() {
        check(root.isDirectory || root.mkdirs()) { "Could not create $root" }
        check(stagingRoot.isDirectory || stagingRoot.mkdirs()) { "Could not create $stagingRoot" }
    }

    // ── cold-start pointer ───────────────────────────────────────────────

    /** The cold-start value that names the region to use. Absent/garbage => built-in. */
    fun readPointer(): String {
        val raw = runCatching { pointerFile.takeIf { it.isFile }?.readText() }.getOrNull()
            ?.trim() ?: return RegionSnapshot.BuiltIn.pointerValue
        return if (isValidPointer(raw)) raw else RegionSnapshot.BuiltIn.pointerValue
    }

    /** write-temp -> fsync -> rename -> fsync(dir). Survives power loss with either old or new value. */
    fun publishPointer(value: String) {
        require(isValidPointer(value)) { "Illegal pointer value" }
        ensureDirs()
        FileOutputStream(pointerTmp).use { out ->
            out.write(value.toByteArray(Charsets.US_ASCII))
            out.flush()
            out.fd.sync()
        }
        if (!pointerTmp.renameTo(pointerFile)) {
            pointerTmp.delete()
            throw RegionImportException("Could not record the active region")
        }
        fsyncDir(root)
    }

    // ── install directories ──────────────────────────────────────────────

    fun installDir(installId: String) = File(root, installId)

    fun newInstallId(regionId: String, version: String): String {
        val salt = ByteArray(6).also(random::nextBytes)
            .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        return "r_${regionId}_${version}_$salt"
    }

    fun newStagingDir(): File {
        ensureDirs()
        val salt = ByteArray(8).also(random::nextBytes)
            .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        val dir = File(stagingRoot, "stage_$salt")
        check(dir.mkdirs()) { "Could not create staging directory" }
        return dir
    }

    /** Leftovers from a crashed/killed import. Safe to nuke: nothing published points here. */
    fun clearStaging() {
        stagingRoot.listFiles()?.forEach { it.deleteRecursively() }
    }

    // ── descriptors ──────────────────────────────────────────────────────
    fun writeDescriptor(dir: File, manifest: RegionManifest) {
        val json = JSONObject()
            .put("format", 1)
            .put("id", manifest.regionId)
            .put("displayName", manifest.displayName)
            .put("version", manifest.version)
            .put("searchSchema", manifest.searchSchema)
            .put("minLatitude", manifest.bounds.minLatitude)
            .put("maxLatitude", manifest.bounds.maxLatitude)
            .put("minLongitude", manifest.bounds.minLongitude)
            .put("maxLongitude", manifest.bounds.maxLongitude)
            .toString()
        val file = File(dir, DESCRIPTOR)
        FileOutputStream(file).use { it.write(json.toByteArray()); it.flush(); it.fd.sync() }
    }

    // ── descriptors ──────────────────────────────────────────────────────

    fun writeDescriptor(dir: File, descriptor: RegionDescriptor) {
        FileOutputStream(File(dir, DESCRIPTOR)).use {
            it.write(descriptor.toByteArray())
            it.flush()
            it.fd.sync()
        }
    }

    private fun RegionDescriptor.toByteArray() = toJson().toByteArray(Charsets.UTF_8)

    fun readDescriptor(installId: String): RegionDescriptor? {
        if (!isValidPointer(installId) || installId == RegionSnapshot.BuiltIn.pointerValue) return null
        val file = File(installDir(installId), DESCRIPTOR).takeIf { it.isFile } ?: return null
        return runCatching { file.readText() }.getOrNull()?.let(RegionDescriptor::parse)
    }

    fun readSnapshot(installId: String): RegionSnapshot.Installed? {
        val descriptor = readDescriptor(installId) ?: return null
        return RegionSnapshot.Installed(
            installId = installId,
            regionId = descriptor.regionId,
            displayName = descriptor.displayName,
            version = descriptor.version,
            searchSchema = descriptor.searchSchema,
            bounds = descriptor.bounds,
            installedBytes = descriptor.installedBytes,
            dir = installDir(installId),
        ).takeIf { it.isIntact() }
    }

    /** Every install directory that currently parses as a real region. */
    fun listInstallIds(): List<String> =
        root.listFiles()
            ?.filter { it.isDirectory && it.name != STAGING }
            ?.map { it.name }
            ?.filter { readSnapshot(it) != null }
            ?.sorted()
            .orEmpty()

    // ── retention ────────────────────────────────────────────────────────

    /**
     * Cold-start sweep. Installed regions are NEVER removed here — they stay on the device
     * until the user deletes them. We only reap staging leftovers from a killed import and
     * directories that do not parse as a region (a torn publish, or hand-placed junk).
     */
    fun pruneOrphans() {
        clearStaging()
        root.listFiles()?.forEach { child ->
            val name = child.name
            if (name == STAGING || name == POINTER || name == "$POINTER.tmp") return@forEach
            if (!child.isDirectory || readSnapshot(name) == null) {
                Log.w(TAG, "Removing orphaned region directory $name")
                child.deleteRecursively()
            }
        }
    }

    /**
     * User-initiated removal. Callers must have already refused the active snapshot and the
     * pending pointer target — this is the last line of defence, not the first.
     */
    fun deleteInstall(installId: String, activePointer: String, activeSnapshotId: String) {
        require(installId != RegionSnapshot.BuiltIn.pointerValue) { "The bundled region cannot be deleted" }
        require(installId != activePointer) { "Cannot delete the region selected for next launch" }
        require(installId != activeSnapshotId) { "Cannot delete the region currently in use" }
        val dir = installDir(installId)
        check(dir.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Refusing to delete $dir" }
        check(dir.deleteRecursively()) { "Could not delete region" }
        fsyncDir(root)
    }

    companion object {
        private const val TAG = "RegionStore"
        private const val STAGING = "staging"
        private const val POINTER = "active.pointer"
        const val DESCRIPTOR = "region.json"

        private val POINTER_RE = Regex("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}")
        private fun isValidPointer(v: String) = POINTER_RE.matches(v) && v != "." && v != ".."

        /** Directory metadata is only durable after the directory itself is fsynced. */
        fun fsyncDir(dir: File) {
            val fd = Os.open(dir.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }
    }
}