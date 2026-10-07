package dev.quietnet

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A local VPN that carries only DNS. Android sends every app's lookups to a
 * private address routed into this tunnel; blocked names get an empty answer
 * right away and the rest are forwarded to the real DNS server. All other
 * traffic goes straight to the network, so speed and battery are unaffected.
 */
class BlockerService : VpnService() {
    companion object {
        const val ACTION_START = "dev.quietnet.START"
        const val ACTION_STOP = "dev.quietnet.STOP"
        const val ACTION_RESTART = "dev.quietnet.RESTART"
        private const val TAG = "QuietNet"
        private const val ADDRESS = "10.188.53.1"
        private const val DNS = "10.188.53.53"
        // Firefox checks this name and turns off its own encrypted DNS when it doesn't resolve.
        private const val FIREFOX_CANARY = "use-application-dns.net"
    }

    private val main = Handler(Looper.getMainLooper())
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var wakeFd: FileDescriptor? = null
    @Volatile private var output: FileOutputStream? = null
    @Volatile private var systemDns: List<InetAddress> = emptyList()
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    // Threads exist only while lookups are in flight and exit after 30 s idle.
    private val pool = ThreadPoolExecutor(0, 48, 30, TimeUnit.SECONDS, SynchronousQueue(), ThreadPoolExecutor.DiscardPolicy())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> if (worker != null) {
                stopTunnel()
                startTunnel()
            } else {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // A null intent is a restart by the system after the process was killed.
                if (intent == null && !Prefs.enabled) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                Prefs.enabled = true
                startTunnel()
                FilterUpdater.updateIfStale(this)
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over, or the user turned us off in system settings.
        Prefs.enabled = false
        shutdown()
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun startTunnel() {
        if (worker != null) return
        Blocker.setStatus(this, Status.STARTING)
        watchNetwork()
        val pfd = try {
            builder().establish()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start the tunnel", e)
            null
        }
        if (pfd == null) {
            Prefs.enabled = false
            shutdown()
            stopSelf()
            return
        }
        tun = pfd
        val pipe = Os.pipe()
        wakeFd = pipe[1]
        output = FileOutputStream(pfd.fileDescriptor)
        val thread = Thread({ readLoop(pfd.fileDescriptor, pipe[0]) }, "quietnet-dns")
        worker = thread
        thread.start()
        Blocker.setStatus(this, Status.ON)
    }

    private fun stopTunnel() {
        val w = worker ?: return
        worker = null
        wakeFd?.let { closeQuietly(it) }
        wakeFd = null
        try {
            w.join(2000)
        } catch (_: InterruptedException) {
        }
        output = null
        try {
            tun?.close()
        } catch (_: IOException) {
        }
        tun = null
    }

    private fun shutdown() {
        stopTunnel()
        unwatchNetwork()
        Blocker.setStatus(this, Status.OFF)
    }

    private fun builder(): Builder {
        val b = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(ADDRESS, 32)
            .addRoute(DNS, 32)
            .addDnsServer(DNS)
            .setMtu(1500)
            .setConfigureIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        // Our own downloads and forwarded lookups go straight to the network.
        val skip = Prefs.excludedApps + packageName
        for (p in skip) {
            try {
                b.addDisallowedApplication(p)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return b
    }

    private fun readLoop(fd: FileDescriptor, wake: FileDescriptor) {
        val input = FileInputStream(fd)
        val buf = ByteArray(32767)
        val pollIn = OsConstants.POLLIN.toShort()
        val broken = OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL
        var stopped = false
        try {
            while (true) {
                val fds = arrayOf(
                    StructPollfd().apply { this.fd = fd; events = pollIn },
                    StructPollfd().apply { this.fd = wake; events = pollIn },
                )
                try {
                    Os.poll(fds, -1)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    throw e
                }
                if (fds[1].revents.toInt() != 0) {
                    stopped = true
                    break
                }
                val r = fds[0].revents.toInt()
                if (r and OsConstants.POLLIN != 0) {
                    val n = input.read(buf)
                    if (n > 0) handle(buf, n)
                } else if (r and broken != 0) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tunnel stopped", e)
        } finally {
            closeQuietly(wake)
        }
        if (!stopped) {
            // The tunnel failed underneath us; reflect that instead of looking on.
            val me = Thread.currentThread()
            main.post { if (worker === me) shutdown() }
        }
    }

    private fun handle(buf: ByteArray, len: Int) {
        val q = Packet.parse(buf, len) ?: return
        val domain = q.domain
        if (domain != null) {
            if (domain == FIREFOX_CANARY) {
                send(Packet.wrap(q, Packet.blockedReply(q, nxdomain = true)))
                return
            }
            val blocked = Rules.isBlocked(domain)
            Blocker.record(domain, blocked)
            if (blocked) {
                send(Packet.wrap(q, Packet.blockedReply(q)))
                return
            }
        }
        try {
            pool.execute { forward(q) }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun forward(q: Packet.Query) {
        for (server in Upstream.servers(Prefs.upstream, systemDns)) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 3000
                    s.connect(server, 53)
                    s.send(DatagramPacket(q.dns, q.dns.size))
                    val rb = ByteArray(4096)
                    val rp = DatagramPacket(rb, rb.size)
                    s.receive(rp)
                    send(Packet.wrap(q, rb, rp.length))
                }
                return
            } catch (_: IOException) {
                // Try the next server.
            }
        }
    }

    private fun send(packet: ByteArray) {
        val o = output ?: return
        try {
            synchronized(o) { o.write(packet) }
        } catch (_: IOException) {
        }
    }

    private fun watchNetwork() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        // This app is outside its own tunnel, so its default network is the real Wi-Fi or mobile one.
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                systemDns = lp.dnsServers.filter { it.hostAddress != DNS }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't watch the network", e)
        }
    }

    private fun unwatchNetwork() {
        val cb = netCallback ?: return
        netCallback = null
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    private fun closeQuietly(fd: FileDescriptor) {
        try {
            Os.close(fd)
        } catch (_: ErrnoException) {
        }
    }
}
