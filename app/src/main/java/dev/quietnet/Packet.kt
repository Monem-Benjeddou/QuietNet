package dev.quietnet

/** Just enough IPv4, UDP and DNS to answer lookups that arrive on the tunnel. */
object Packet {
    val DNS_IP = byteArrayOf(10, 188.toByte(), 53, 53)

    class Query(
        val clientIp: ByteArray,
        val clientPort: Int,
        /** The raw DNS message. */
        val dns: ByteArray,
        /** Lowercase name asked for, or null if the message couldn't be read. */
        val domain: String?,
        val type: Int,
        /** Offset just past the question section. */
        val questionEnd: Int,
    )

    fun parse(buf: ByteArray, len: Int): Query? {
        if (len < 28 || (buf[0].toInt() shr 4) and 0xF != 4) return null
        val ihl = (buf[0].toInt() and 0xF) * 4
        if (buf[9].toInt() and 0xFF != 17 || len < ihl + 8 + 12) return null
        for (k in 0..3) if (buf[16 + k] != DNS_IP[k]) return null
        if (u16(buf, ihl + 2) != 53) return null
        val end = minOf(len, ihl + u16(buf, ihl + 4))
        if (end <= ihl + 8) return null
        val dns = buf.copyOfRange(ihl + 8, end)
        val clientIp = buf.copyOfRange(12, 16)
        val clientPort = u16(buf, ihl)

        if (dns.size < 12 || dns[2].toInt() and 0x80 != 0 || u16(dns, 4) != 1) {
            return Query(clientIp, clientPort, dns, null, -1, 0)
        }
        val name = StringBuilder()
        var i = 12
        while (true) {
            if (i >= dns.size) return Query(clientIp, clientPort, dns, null, -1, 0)
            val l = dns[i].toInt() and 0xFF
            if (l == 0) { i++; break }
            if (l and 0xC0 != 0 || i + 1 + l > dns.size || name.length > 253) {
                return Query(clientIp, clientPort, dns, null, -1, 0)
            }
            if (name.isNotEmpty()) name.append('.')
            for (k in 1..l) name.append(((dns[i + k].toInt() and 0xFF).toChar()).lowercaseChar())
            i += 1 + l
        }
        if (i + 4 > dns.size) return Query(clientIp, clientPort, dns, null, -1, 0)
        return Query(clientIp, clientPort, dns, name.toString().trimEnd('.'), u16(dns, i), i + 4)
    }

    /**
     * The answer for a blocked name: 0.0.0.0 or :: for address lookups, so
     * apps fail fast instead of retrying, and an empty answer otherwise.
     */
    fun blockedReply(q: Query, nxdomain: Boolean = false): ByteArray {
        val d = q.dns
        val rdLen = when {
            nxdomain -> -1
            q.type == 1 -> 4
            q.type == 28 -> 16
            else -> -1
        }
        val out = ByteArray(q.questionEnd + if (rdLen >= 0) 12 + rdLen else 0)
        System.arraycopy(d, 0, out, 0, q.questionEnd)
        out[2] = (0x80 or (d[2].toInt() and 0x79)).toByte() // response, same opcode and RD
        out[3] = (0x80 or if (nxdomain) 3 else 0).toByte() // RA, NOERROR or NXDOMAIN
        out[4] = 0; out[5] = 1
        out[6] = 0; out[7] = if (rdLen >= 0) 1 else 0
        for (k in 8..11) out[k] = 0
        if (rdLen >= 0) {
            var p = q.questionEnd
            out[p++] = 0xC0.toByte(); out[p++] = 12 // name: pointer to the question
            out[p++] = 0; out[p++] = q.type.toByte()
            out[p++] = 0; out[p++] = 1 // class IN
            out[p++] = 0; out[p++] = 0; out[p++] = 0; out[p++] = 60 // TTL 60 s
            out[p++] = 0; out[p] = rdLen.toByte() // address bytes stay zero
        }
        return out
    }

    /** Wraps a DNS message in IPv4 and UDP headers addressed back to the asking app. */
    fun wrap(q: Query, payload: ByteArray, length: Int = payload.size): ByteArray {
        val total = 28 + length
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[8] = 64 // TTL
        p[9] = 17 // UDP
        System.arraycopy(DNS_IP, 0, p, 12, 4)
        System.arraycopy(q.clientIp, 0, p, 16, 4)
        val sum = checksum(p, 0, 20)
        p[10] = (sum shr 8).toByte(); p[11] = sum.toByte()
        p[20] = 0; p[21] = 53
        p[22] = (q.clientPort shr 8).toByte(); p[23] = q.clientPort.toByte()
        val udpLen = 8 + length
        p[24] = (udpLen shr 8).toByte(); p[25] = udpLen.toByte()
        // A zero UDP checksum means "none", which IPv4 allows.
        System.arraycopy(payload, 0, p, 28, length)
        return p
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        while (i < off + len) {
            sum += u16(b, i)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
}
