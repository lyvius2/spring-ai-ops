package com.walter.spring.ai.ops.sonar.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * Manages the sonar-scanner-cli binary and analysis engine lifecycle for embedded SonarQube analysis.
 *
 * On first analysis request the binary is not available, this component downloads
 * sonar-scanner-cli from SonarSource CDN and extracts it to [installDir].
 * Subsequent calls reuse the cached installation without re-downloading.
 *
 * The sonar-scanner engine JAR (sonar-scanner-engine-shaded) is extracted from
 * the SonarQube Community Edition distribution ZIP using HTTP Range requests,
 * avoiding a full 400 MB download — only ~50 MB of targeted range reads are needed.
 *
 * Explicit override: set [sonar.scanner.path] to a non-empty value to skip
 * auto-install and use a pre-installed binary instead.
 *
 * Install layout (inside [installDir]):
 *   sonar-scanner-{version}/
 *     bin/
 *       sonar-scanner       ← Unix executable (this component ensures +x after extraction)
 *       sonar-scanner.bat
 *     lib/  *.jar
 *     conf/ sonar-scanner.properties
 *   engine/
 *     sonar-scanner-engine-shaded-{engineVersion}.jar
 */
@Component
class SonarScannerInstaller(
    @Value("\${sonar.scanner.path:}") private val configuredPath: String,
    @Value("\${sonar.scanner.version:6.2.1.4610}") private val scannerVersion: String,
    @Value("\${sonar.scanner.install-dir:}") private val configuredInstallDir: String,
    @Value("\${sonar.scanner.engine-version:10.3.0.82913}") private val engineVersion: String,
) {
    private val log = LoggerFactory.getLogger(SonarScannerInstaller::class.java)

    companion object {
        private const val SCANNER_CLI_BASE =
            "https://binaries.sonarsource.com/Distribution/sonar-scanner-cli"
        private const val SONARQUBE_DIST_BASE =
            "https://binaries.sonarsource.com/Distribution/sonarqube"

        // ZIP local file header signature and field offsets
        private const val LFH_SIGNATURE = 0x04034b50
        private const val LFH_FNAME_LEN_OFFSET = 26
        private const val LFH_EXTRA_LEN_OFFSET = 28
        private const val LFH_FIXED_SIZE = 30

        // ZIP central directory and EOCD constants
        private const val EOCD_SIGNATURE = 0x06054b50
        private const val EOCD_MIN_SIZE = 22
        private const val CD_SIGNATURE = 0x02014b50
        private const val CD_ENTRY_FIXED_SIZE = 46
        private const val CD_COMPRESSION_OFFSET = 10
        private const val CD_COMPRESSED_SIZE_OFFSET = 20
        private const val CD_LOCAL_HEADER_OFFSET_FIELD = 42
        private const val CD_FNAME_LEN_OFFSET = 28
        private const val CD_EXTRA_LEN_OFFSET = 30
        private const val CD_COMMENT_LEN_OFFSET = 32

        private const val COMPRESSION_STORED = 0
        private const val COMPRESSION_DEFLATED = 8

        // Bytes to fetch for the end of the ZIP when searching for the central directory
        private const val CD_TAIL_FETCH_SIZE = 1_048_576 // 1 MB
    }

    private val installDir: File
        get() = if (configuredInstallDir.isNotBlank()) File(configuredInstallDir)
                else File(System.getProperty("user.home"), ".spring-ai-ops/sonar-scanner")

    /**
     * Returns the sonar-scanner binary path, installing it first if necessary.
     * Also triggers an opportunistic engine download so the engine is ready before
     * sonar-scanner issues its bootstrap HTTP request to the mock server.
     *
     * Priority:
     * 1. [sonar.scanner.path] — explicit override (env or config)
     * 2. Managed installation under [installDir]
     * 3. Auto-download if neither is present
     */
    fun resolveOrInstall(): String {
        if (configuredPath.isNotBlank()) {
            log.debug("Using configured sonar-scanner: {}", configuredPath)
            return configuredPath
        }

        val binary = managedBinaryPath()
        if (!binary.exists()) {
            log.info("sonar-scanner not found — auto-installing v{} to {}", scannerVersion, installDir)
            install()
        } else {
            log.debug("Using managed sonar-scanner at {}", binary)
        }

        // Opportunistically download engine alongside CLI so it is ready when the mock
        // API serves GET /api/v2/analysis/engine during the sonar-scanner bootstrap phase.
        if (!enginePath().exists()) {
            runCatching { downloadEngine() }
                .onFailure { e ->
                    log.warn(
                        "sonar-scanner engine download failed — SonarQube static analysis will be unavailable.\n" +
                        "  Source    : SonarQube CE distribution ZIP (range requests)\n" +
                        "  Version   : {}\n" +
                        "  Reason    : {}\n" +
                        "  Fix hint  : set sonar.scanner.engine-version to a valid SonarQube CE build version " +
                        "(format: major.minor.patch.build, e.g. 10.3.0.82913).",
                        engineVersion, e.message
                    )
                }
        }

        return binary.absolutePath
    }

    /**
     * Returns true if the managed binary is already present or an explicit path is configured.
     */
    fun isInstalled(): Boolean = configuredPath.isNotBlank() || managedBinaryPath().exists()

    /**
     * Returns the engine JAR file if it is present, downloading it first if needed.
     * Returns null if the download fails (sonar-scanner bootstrap will then 404).
     */
    fun resolveOrInstallEngine(): File? {
        val engine = enginePath()
        if (engine.exists()) {
            log.debug("Using managed sonar-scanner engine at {}", engine)
            return engine
        }
        return try {
            downloadEngine()
            engine
        } catch (e: Exception) {
            log.warn(
                "sonar-scanner engine v{} download failed — GET /api/v2/analysis/engine will return 404.\n" +
                "  Reason    : {}\n" +
                "  Fix hint  : set sonar.scanner.engine-version to a valid SonarQube CE build version " +
                "(format: major.minor.patch.build, e.g. 10.3.0.82913).",
                engineVersion, e.message
            )
            null
        }
    }

    /** Returns the engine JAR filename, e.g. sonar-scanner-engine-shaded-10.3.0.82913.jar. */
    fun engineFilename(): String = "sonar-scanner-engine-shaded-$engineVersion.jar"

    /**
     * Downloads sonar-scanner-cli ZIP and extracts it under [installDir].
     * Sets the Unix executable bit on the extracted binary.
     */
    fun install() {
        val url = "$SCANNER_CLI_BASE/sonar-scanner-cli-$scannerVersion.zip"
        log.info("Downloading sonar-scanner-cli from {}", url)
        val zipBytes = fetchZip(url)
        installDir.mkdirs()
        extractZip(zipBytes, installDir)
        managedBinaryPath().setExecutable(true)
        log.info("sonar-scanner v{} ready at {}", scannerVersion, managedBinaryPath())
    }

    // ── protected for test override ───────────────────────────────────────────────

    /** Downloads the sonar-scanner-cli ZIP bytes. Override in tests to avoid network calls. */
    protected open fun fetchZip(url: String): ByteArray = URL(url).readBytes()

    /**
     * Extracts the sonar-scanner-engine-shaded JAR from the SonarQube CE distribution ZIP
     * using HTTP Range requests to avoid downloading the full ~400 MB archive.
     *
     * Override in tests to return pre-built fake bytes without network calls.
     */
    protected open fun fetchEngineFromDistribution(version: String): ByteArray {
        val distUrl = "$SONARQUBE_DIST_BASE/sonarqube-$version.zip"
        val entryPath = "sonarqube-$version/lib/scanner/sonar-scanner-engine-shaded-$version.jar"
        log.info("Locating engine entry '{}' in distribution ZIP via range requests", entryPath)

        val totalSize = getRemoteFileSize(distUrl)
        log.debug("Distribution ZIP size: {} bytes", totalSize)

        val tailSize = minOf(CD_TAIL_FETCH_SIZE.toLong(), totalSize).toInt()
        val tailStart = totalSize - tailSize
        val tail = fetchRange(distUrl, tailStart, totalSize - 1)

        val eocdOffset = findEocdOffset(tail)
            ?: throw IllegalStateException("EOCD record not found in last $tailSize bytes of $distUrl")

        val buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        // EOCD: offset 16 = CD offset (4 bytes), offset 12 = CD size (4 bytes)
        val cdSize = buf.getInt(eocdOffset + 12).toLong() and 0xFFFFFFFFL
        val cdOffsetInFile = (buf.getInt(eocdOffset + 16).toLong() and 0xFFFFFFFFL)

        log.debug("Central directory: offset={}, size={}", cdOffsetInFile, cdSize)

        // Re-use tail bytes if the central directory falls within the already-fetched range
        val cdBytes = if (cdOffsetInFile >= tailStart) {
            val relStart = (cdOffsetInFile - tailStart).toInt()
            tail.copyOfRange(relStart, relStart + cdSize.toInt())
        } else {
            fetchRange(distUrl, cdOffsetInFile, cdOffsetInFile + cdSize - 1)
        }

        val entry = findCdEntry(cdBytes, entryPath)
            ?: throw IllegalStateException("Entry '$entryPath' not found in central directory of $distUrl")

        val localHeaderOffset = entry.localHeaderOffset
        val compressedSize = entry.compressedSize
        val compressionMethod = entry.compressionMethod

        // Read the local file header to determine the variable-length fields
        val lfhBytes = fetchRange(distUrl, localHeaderOffset, localHeaderOffset + LFH_FIXED_SIZE - 1)
        val lfhBuf = ByteBuffer.wrap(lfhBytes).order(ByteOrder.LITTLE_ENDIAN)
        val sig = lfhBuf.getInt(0)
        require(sig == LFH_SIGNATURE) { "Expected LFH signature at offset $localHeaderOffset, got 0x${sig.toString(16)}" }
        val fnameLen = lfhBuf.getShort(LFH_FNAME_LEN_OFFSET).toInt() and 0xFFFF
        val extraLen = lfhBuf.getShort(LFH_EXTRA_LEN_OFFSET).toInt() and 0xFFFF
        val dataStart = localHeaderOffset + LFH_FIXED_SIZE + fnameLen + extraLen

        log.info(
            "Downloading engine JAR entry: offset={}, compressed size={} bytes, method={}",
            dataStart, compressedSize, if (compressionMethod == COMPRESSION_DEFLATED) "deflate" else "stored"
        )

        val compressedData = fetchRange(distUrl, dataStart, dataStart + compressedSize - 1)
        return when (compressionMethod) {
            COMPRESSION_STORED -> compressedData
            COMPRESSION_DEFLATED -> inflate(compressedData)
            else -> throw IllegalStateException("Unsupported ZIP compression method: $compressionMethod")
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────────

    private fun managedBinaryPath(): File =
        installDir.resolve("sonar-scanner-$scannerVersion").resolve("bin").resolve("sonar-scanner")

    private fun enginePath(): File =
        installDir.resolve("engine").resolve(engineFilename())

    private fun downloadEngine() {
        log.info("Downloading sonar-scanner engine v{} from SonarQube CE distribution ZIP", engineVersion)
        val bytes = fetchEngineFromDistribution(engineVersion)
        val engine = enginePath()
        engine.parentFile.mkdirs()
        engine.writeBytes(bytes)
        log.info("sonar-scanner engine v{} ready at {} ({} bytes)", engineVersion, engine, bytes.size)
    }

    private fun getRemoteFileSize(url: String): Long {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        conn.connect()
        try {
            check(conn.responseCode == 200) { "HEAD $url returned HTTP ${conn.responseCode}" }
            return conn.contentLengthLong.also {
                check(it > 0) { "HEAD $url returned non-positive Content-Length: $it" }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchRange(url: String, startByte: Long, endByte: Long): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Range", "bytes=$startByte-$endByte")
        conn.connect()
        try {
            val code = conn.responseCode
            check(code == 206 || code == 200) { "Range request $url [$startByte-$endByte] returned HTTP $code" }
            return conn.inputStream.readBytes()
        } finally {
            conn.disconnect()
        }
    }

    private data class CdEntry(
        val compressionMethod: Int,
        val compressedSize: Long,
        val localHeaderOffset: Long,
    )

    /**
     * Scans the central directory bytes for an entry whose filename matches [targetPath].
     * Returns null if not found.
     */
    private fun findCdEntry(cdBytes: ByteArray, targetPath: String): CdEntry? {
        val buf = ByteBuffer.wrap(cdBytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + CD_ENTRY_FIXED_SIZE <= cdBytes.size) {
            val sig = buf.getInt(pos)
            if (sig != CD_SIGNATURE) break

            val fnameLen = buf.getShort(pos + CD_FNAME_LEN_OFFSET).toInt() and 0xFFFF
            val extraLen = buf.getShort(pos + CD_EXTRA_LEN_OFFSET).toInt() and 0xFFFF
            val commentLen = buf.getShort(pos + CD_COMMENT_LEN_OFFSET).toInt() and 0xFFFF
            val entrySize = CD_ENTRY_FIXED_SIZE + fnameLen + extraLen + commentLen

            val fname = String(cdBytes, pos + CD_ENTRY_FIXED_SIZE, fnameLen, Charsets.UTF_8)
            if (fname == targetPath) {
                val compressionMethod = buf.getShort(pos + CD_COMPRESSION_OFFSET).toInt() and 0xFFFF
                val compressedSize = buf.getInt(pos + CD_COMPRESSED_SIZE_OFFSET).toLong() and 0xFFFFFFFFL
                val localHeaderOffset = buf.getInt(pos + CD_LOCAL_HEADER_OFFSET_FIELD).toLong() and 0xFFFFFFFFL
                return CdEntry(compressionMethod, compressedSize, localHeaderOffset)
            }

            pos += entrySize
        }
        return null
    }

    /**
     * Searches [tail] (the last N bytes of the ZIP) for the EOCD record.
     * Returns the offset of the EOCD record within [tail], or null if not found.
     */
    private fun findEocdOffset(tail: ByteArray): Int? {
        // Search backwards from end (EOCD is near the end, but may have variable-length comment)
        for (i in tail.size - EOCD_MIN_SIZE downTo 0) {
            if (tail[i] == 0x50.toByte() &&
                tail[i + 1] == 0x4B.toByte() &&
                tail[i + 2] == 0x05.toByte() &&
                tail[i + 3] == 0x06.toByte()
            ) {
                return i
            }
        }
        return null
    }

    private fun inflate(compressed: ByteArray): ByteArray {
        val inflater = Inflater(true) // true = raw DEFLATE (no zlib wrapper)
        inflater.setInput(compressed)
        val out = java.io.ByteArrayOutputStream(compressed.size * 3)
        val buf = ByteArray(65536)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n > 0) out.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun extractZip(zipBytes: ByteArray, targetDir: File) {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val dest = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    dest.mkdirs()
                } else {
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}