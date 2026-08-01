package com.example.offnav.region

import com.graphhopper.config.Profile
import com.graphhopper.json.Statement
import com.graphhopper.util.CustomModel
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Single source of truth for the routing profile + the graph-compatibility stamp. */
object GraphProfile {
    const val PROFILE = "car"
    const val GRAPH_CONFIG_VERSION = 4
    const val ENCODED_VALUES =
        "car_access,road_access,road_class,road_environment,car_average_speed"
    const val VERSION_ENTRY = "offnav.graph.version"

    fun carProfile(): Profile = Profile(PROFILE).apply {
        setCustomModel(
            CustomModel().apply {
                addToSpeed(Statement.If("true", Statement.Op.LIMIT, "car_average_speed"))
                addToPriority(Statement.If("!car_access", Statement.Op.MULTIPLY, "0"))
                addToPriority(Statement.ElseIf("road_access == DESTINATION", Statement.Op.MULTIPLY, "0.1"))
                addToPriority(Statement.If("road_class == TRACK", Statement.Op.MULTIPLY, "0.5"))
                addToPriority(Statement.If("road_environment == FERRY", Statement.Op.MULTIPLY, "0.5"))
                distanceInfluence = 70.0
            }
        )
    }

    fun expectedVersion(): String =
        "$GRAPH_CONFIG_VERSION:${carProfile().version}:$ENCODED_VALUES"

    fun installedVersion(graphDir: File): String? =
        File(graphDir, VERSION_ENTRY).takeIf { it.isFile }?.readText()?.trim()
}

/** Extracts a `.ghz` into [destDir] with zip-slip, budget and name hardening. */
object RoutingGraphArchive {

    fun extract(ghz: File, destDir: File, budget: ByteBudget) {
        check(destDir.isDirectory || destDir.mkdirs()) { "Could not create $destDir" }
        val rootPath = destDir.canonicalPath + File.separator
        ghz.inputStream().buffered(BundleSpec.IO_BUFFER).use { raw ->
            ZipInputStream(raw).use { zip ->
                val buffer = ByteArray(BundleSpec.IO_BUFFER)
                val seen = HashSet<String>()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (!isSafeName(name)) importFailure("routing.ghz contains an unsafe entry")
                    if (!seen.add(name)) importFailure("routing.ghz contains duplicate entries")

                    val out = File(destDir, name)
                    if (!out.canonicalPath.startsWith(rootPath)) {
                        importFailure("routing.ghz contains an unsafe entry")
                    }
                    if (entry.isDirectory || name.endsWith("/")) {
                        check(out.isDirectory || out.mkdirs()) { "Could not create ${entry.name}" }
                    } else {
                        out.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "mkdirs failed" } }
                        writeEntry(zip, out, buffer, budget)
                    }
                    zip.closeEntry()
                }
            }
        }

        val found = GraphProfile.installedVersion(destDir)
        val expected = GraphProfile.expectedVersion()
        if (found != expected) {
            importFailure("Routing graph is not compatible with this app version")
        }
    }

    private fun writeEntry(zip: InputStream, out: File, buffer: ByteArray, budget: ByteBudget) {
        FileOutputStream(out).use { fos ->
            val sink = fos.buffered(BundleSpec.IO_BUFFER)
            while (true) {
                val n = zip.read(buffer)
                if (n < 0) break
                budget.consume(n.toLong())
                sink.write(buffer, 0, n)
            }
            sink.flush()
            fos.fd.sync()
        }
    }

    /** Rejects absolute paths, `..`, backslashes, drive letters, commas and control characters. */
    private fun isSafeName(name: String): Boolean {
        if (name.isEmpty() || name.length > 255) return false
        if (name.startsWith("/")) return false
        if (name.any { it == '\\' || it == ':' || it == ',' || it.code < 0x20 || it.code == 0x7F }) return false
        val parts = name.trimEnd('/').split('/')
        if (parts.isEmpty()) return false
        return parts.none { it.isEmpty() || it == "." || it == ".." || it != it.trim() }
    }
}