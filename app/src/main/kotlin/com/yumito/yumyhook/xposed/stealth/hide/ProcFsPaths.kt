package com.yumito.yumyhook.xposed.stealth.hide

/** /proc 敏感路径判定（maps / smaps / status）。 */
object ProcFsPaths {

    enum class Kind { MAPS, SMAPS, STATUS }

    private val PROC_MAPS = Regex("""^/proc/(?:self|\d+)/(?:task/\d+/)?maps$""")
    private val PROC_SMAPS = Regex("""^/proc/(?:self|\d+)/(?:task/\d+/)?smaps$""")
    private val PROC_STATUS = Regex("""^/proc/(?:self|\d+)/(?:task/\d+/)?status$""")

    fun kind(path: String?): Kind? {
        if (path.isNullOrBlank()) return null
        val normalized = SensitivePathFilter.normalize(path)
        return when {
            PROC_MAPS.matches(normalized) -> Kind.MAPS
            PROC_SMAPS.matches(normalized) -> Kind.SMAPS
            PROC_STATUS.matches(normalized) -> Kind.STATUS
            normalized.endsWith("/self/maps") -> Kind.MAPS
            normalized.endsWith("/self/smaps") -> Kind.SMAPS
            normalized.endsWith("/self/status") -> Kind.STATUS
            else -> null
        }
    }

    fun isSensitive(path: String?): Boolean = kind(path) != null
}
