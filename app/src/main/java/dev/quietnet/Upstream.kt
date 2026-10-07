package dev.quietnet

import java.net.InetAddress

/** Where lookups that aren't blocked are sent. */
object Upstream {
    class Option(val id: String, val name: String, val about: String, ips: List<String>) {
        val addresses: List<InetAddress> by lazy { ips.map { InetAddress.getByName(it) } }
    }

    val options = listOf(
        Option("auto", "Automatic", "Your Wi-Fi or mobile network's DNS server", emptyList()),
        Option("adguard", "AdGuard DNS", "Blocks even more ads on its own servers", listOf("94.140.14.14", "94.140.15.15")),
        Option("cloudflare", "Cloudflare", "1.1.1.1, fast and private", listOf("1.1.1.1", "1.0.0.1")),
        Option("quad9", "Quad9", "9.9.9.9, also blocks malware sites", listOf("9.9.9.9", "149.112.112.112")),
        Option("google", "Google", "8.8.8.8", listOf("8.8.8.8", "8.8.4.4")),
    )

    private val fallback by lazy { listOf(InetAddress.getByName("1.1.1.1")) }

    fun servers(id: String, system: List<InetAddress>): List<InetAddress> {
        val chosen = options.firstOrNull { it.id == id }?.addresses.orEmpty()
        return chosen.ifEmpty { system.take(2) + fallback }
    }
}
