package dev.quietnet

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Arrays

/**
 * Decides whether a domain is blocked.
 *
 * Filter lists are compiled into sorted arrays of 64-bit hashes, which keeps
 * hundreds of thousands of domains in a few megabytes and makes each lookup a
 * handful of binary searches. A rule for example.com also covers every
 * subdomain, such as ads.example.com.
 */
object Rules {
    private const val MAGIC = 0x514e5231 // "QNR1"

    @Volatile private var block = LongArray(0)
    @Volatile private var allow = LongArray(0)
    @Volatile private var userAllow: Set<String> = emptySet()
    @Volatile private var userBlock: Set<String> = emptySet()

    val count: Int get() = block.size

    fun file(context: Context) = File(context.filesDir, "rules.bin")

    fun isBlocked(domain: String): Boolean {
        val ua = userAllow
        val ub = userBlock
        val b = block
        val a = allow
        val hasUser = ua.isNotEmpty() || ub.isNotEmpty()
        var listBlocked = false
        var start = 0
        // Walk from the full name up to the registrable part; the most specific user rule wins.
        while (true) {
            val dot = domain.indexOf('.', start)
            if (dot < 0) break
            if (hasUser) {
                val s = if (start == 0) domain else domain.substring(start)
                if (s in ua) return false
                if (s in ub) return true
            }
            val h = hash(domain, start)
            if (a.isNotEmpty() && Arrays.binarySearch(a, h) >= 0) return false
            if (!listBlocked && Arrays.binarySearch(b, h) >= 0) listBlocked = true
            start = dot + 1
        }
        return listBlocked
    }

    fun allowDomain(d: String) {
        Prefs.userBlock = Prefs.userBlock - d
        Prefs.userAllow = Prefs.userAllow + d
        refreshUser()
    }

    fun blockDomain(d: String) {
        Prefs.userAllow = Prefs.userAllow - d
        Prefs.userBlock = Prefs.userBlock + d
        refreshUser()
    }

    fun forget(d: String) {
        Prefs.userAllow = Prefs.userAllow - d
        Prefs.userBlock = Prefs.userBlock - d
        refreshUser()
    }

    fun refreshUser() {
        userAllow = Prefs.userAllow
        userBlock = Prefs.userBlock
        Blocker.userRules.value = userAllow to userBlock
    }

    fun set(context: Context, newBlock: LongArray, newAllow: LongArray) {
        val tmp = File(context.filesDir, "rules.tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { o ->
            o.writeInt(MAGIC)
            o.writeInt(newBlock.size)
            o.writeInt(newAllow.size)
            for (v in newBlock) o.writeLong(v)
            for (v in newAllow) o.writeLong(v)
        }
        tmp.renameTo(file(context))
        block = newBlock
        allow = newAllow
        Blocker.ruleCount.value = newBlock.size
    }

    fun load(context: Context) {
        val f = file(context)
        if (!f.exists()) return
        try {
            DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 16)).use { i ->
                if (i.readInt() != MAGIC) return
                val nb = i.readInt()
                val na = i.readInt()
                val b = LongArray(nb) { i.readLong() }
                val a = LongArray(na) { i.readLong() }
                block = b
                allow = a
                Blocker.ruleCount.value = b.size
            }
        } catch (_: Exception) {
            f.delete()
        }
    }

    /** FNV-1a over [s] from [start]. */
    fun hash(s: String, start: Int = 0): Long {
        var h = -0x340d631b7bdddcdbL
        for (i in start until s.length) {
            h = (h xor s[i].code.toLong()) * 0x100000001b3L
        }
        return h
    }
}
