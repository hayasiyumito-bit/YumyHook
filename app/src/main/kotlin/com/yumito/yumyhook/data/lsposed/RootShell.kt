package com.yumito.yumyhook.data.lsposed

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** Root shell 封装；首次 su 授权后缓存结果。 */
object RootShell {

    data class Result(
        val exitCode: Int,
        val output: String,
        val error: String,
    )

    @Volatile
    private var rootGranted: Boolean? = null

    /** 首次调用弹一次 su；之后用缓存，避免重复授权与 Toast。 */
    fun ensureRoot(): Boolean {
        rootGranted?.let { return it }
        val result = execInternal("id")
        val ok = result.exitCode == 0 && result.output.contains("uid=0")
        rootGranted = ok
        return ok
    }

    fun isAvailable(): Boolean = rootGranted == true

    fun forceStop(packageName: String): Boolean {
        if (!ensureRoot()) return false
        val safe = packageName.filter { it.isLetterOrDigit() || it == '.' }
        if (safe != packageName || safe.isBlank()) return false
        return execInternal("am force-stop $safe").exitCode == 0
    }

    fun exec(command: String): Result {
        if (!ensureRoot()) {
            return Result(-1, "", "root not granted")
        }
        return execInternal(command)
    }

    private fun execInternal(command: String): Result {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = readStream(process.inputStream)
            val error = readStream(process.errorStream)
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return Result(-1, output, error + "\n(timeout)")
            }
            Result(process.exitValue(), output.trim(), error.trim())
        } catch (e: Exception) {
            Result(-1, "", e.message.orEmpty())
        }
    }

    private fun readStream(stream: java.io.InputStream): String {
        return BufferedReader(InputStreamReader(stream)).use { it.readText() }
    }
}
