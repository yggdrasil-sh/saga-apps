package io.github.yggdrasil_sh.saga.util

/** Versionsvergleich wie in der iOS-App: "1.2.0" < "1.10.0" (SemVer, Zusaetze wie -beta werden ignoriert). */
object Versions {
    fun parts(s: String): List<Int> {
        val core = s.trim().trim('v', 'V', ' ').substringBefore('-').substringBefore('+')
        val n = core.split('.').take(3).map { it.trim().toIntOrNull() ?: 0 }
        return n + List(maxOf(0, 3 - n.size)) { 0 }
    }

    /** true, wenn a aelter als b ist */
    fun isLess(a: String, b: String): Boolean {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until 3) {
            if (pa[i] != pb[i]) return pa[i] < pb[i]
        }
        return false
    }

    /** Version v (z. B. vom Server) mindestens min? Unbekannt (null/leer) -> false */
    fun atLeast(v: String?, min: String): Boolean = !v.isNullOrBlank() && !isLess(v, min)
}
