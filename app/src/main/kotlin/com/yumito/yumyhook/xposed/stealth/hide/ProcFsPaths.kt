package com.yumito.yumyhook.xposed.stealth.hide

/** /proc 敏感路径判定。 */
object ProcFsPaths {

    enum class Kind { MAPS, SMAPS, STATUS, MOUNTINFO, MOUNTS, MEM }

    private val PROC_MAPS = Regex("""^/proc/(?:self|\d+)/(?:task/\d+/)?maps$""")
    private val PROC_SMAPS = Regex("""^/proc/(?:self|\d+)/(?:task/\d+/)?smaps$""")
    private val PROC_STATUS = Regex("""^/proc/(?:self|\d+)/(?:task/\d+/)?status$""")
    private val PROC_MOUNTINFO = Regex("""^/proc/(?:self|\d+)?/?mountinfo$""")
    private val PROC_MOUNTS = Regex("""^/proc/(?:self|\d+)?/?mounts$""")
    private val PROC_MEM = Regex("""^/proc/(?:self|\d+)/mem$""")

    fun kind(path: String?): Kind? {
        if (path.isNullOrBlank()) return null
        val normalized = SensitivePathFilter.normalize(path)
        return when {
            normalized == "/proc/mounts" -> Kind.MOUNTS
            normalized == "/proc/mountinfo" -> Kind.MOUNTINFO
            PROC_MEM.matches(normalized) -> Kind.MEM
            normalized.contains("/map_files") -> Kind.MAPS
            PROC_MAPS.matches(normalized) -> Kind.MAPS
            PROC_SMAPS.matches(normalized) -> Kind.SMAPS
            PROC_STATUS.matches(normalized) -> Kind.STATUS
            PROC_MOUNTINFO.matches(normalized) -> Kind.MOUNTINFO
            PROC_MOUNTS.matches(normalized) -> Kind.MOUNTS
            normalized.endsWith("/self/maps") -> Kind.MAPS
            normalized.endsWith("/self/smaps") -> Kind.SMAPS
            normalized.endsWith("/self/status") -> Kind.STATUS
            normalized.endsWith("/self/mountinfo") -> Kind.MOUNTINFO
            normalized.endsWith("/self/mounts") -> Kind.MOUNTS
            normalized.endsWith("/self/mem") -> Kind.MEM
            else -> null
        }
    }

    fun isSensitive(path: String?): Boolean = kind(path) != null

    fun isDenied(path: String?): Boolean = kind(path) == Kind.MEM
}
