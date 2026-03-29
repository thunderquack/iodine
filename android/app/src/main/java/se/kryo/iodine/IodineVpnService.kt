package se.kryo.iodine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import kotlin.random.Random

class IodineVpnService : VpnService() {
    private var tunInterface: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var dohRelay: DohRelay? = null

    external fun nativeAttach()
    external fun nativeDetach()
    external fun nativeHandshake(resolver: String, domain: String, password: String, options: String): Boolean
    external fun nativeGetClientIp(): String
    external fun nativeGetServerIp(): String
    external fun nativeGetNetmask(): Int
    external fun nativeGetMtu(): Int
    external fun nativeRunTunnel(tunFd: Int): Int
    external fun nativeStop()

    override fun onCreate() {
        super.onCreate()
        broadcastStatus(log = "IodineVpnService.onCreate")
        nativeAttach()
    }

    override fun onDestroy() {
        broadcastStatus(log = "IodineVpnService.onDestroy")
        disconnectTunnel("Service destroyed.")
        nativeDetach()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        broadcastStatus(log = "IodineVpnService.onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_CONNECT -> connectTunnel(intent)
            ACTION_DISCONNECT -> disconnectTunnel("Disconnected.")
        }
        return Service.START_NOT_STICKY
    }

    fun protectSocket(fd: Int): Boolean = protect(fd)
    fun protectSocket(socket: Socket): Boolean = protect(socket)

    fun emitLog(line: String) {
        broadcastStatus(log = line)
    }

    private fun connectTunnel(intent: Intent) {
        if (worker != null) {
            broadcastStatus(status = "Already connecting or connected.")
            return
        }

        val useDoh = intent.getBooleanExtra(EXTRA_USE_DOH, false)
        val resolver = intent.getStringExtra(EXTRA_SERVER)?.trim().orEmpty()
        val dohServer = intent.getStringExtra(EXTRA_DOH_SERVER)?.trim().orEmpty()
        val domain = intent.getStringExtra(EXTRA_DOMAIN)?.trim().orEmpty()
        val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        val options = intent.getStringExtra(EXTRA_OPTIONS)?.trim().orEmpty()

        worker = Thread {
            if (useDoh) {
                val dohUrl = normalizeDohUrl(dohServer)
                if (dohUrl == null) {
                    broadcastStatus(status = "Missing DoH server.", log = "Enter a DoH host or URL before connecting.")
                    worker = null
                    return@Thread
                }

                broadcastStatus(log = "Using DoH endpoint $dohUrl")
                dohRelay = try {
                    DohRelay(this, dohUrl) { line -> broadcastStatus(log = line) }.also { relay ->
                        relay.start()
                        broadcastStatus(log = "DoH relay listening on 127.0.0.1:${relay.localPort}")
                    }
                } catch (e: Exception) {
                    broadcastStatus(
                        status = "DoH setup failed.",
                        log = "Failed to start DoH relay: ${e.message ?: e.javaClass.simpleName}"
                    )
                    worker = null
                    return@Thread
                }

                broadcastStatus(status = "Handshaking.", log = "Running iodine handshake over DoH.")
                if (!nativeHandshake("127.0.0.1:${dohRelay?.localPort}", domain, password, options)) {
                    broadcastStatus(status = "Handshake failed.", log = "DoH handshake failed.")
                    dohRelay?.stop()
                    dohRelay = null
                    worker = null
                    return@Thread
                }

                broadcastStatus(log = "Using VPN DNS server $DEFAULT_VPN_DNS inside the tunnel.")
                establishTunnel(DEFAULT_VPN_DNS)
                return@Thread
            }

            val resolvers = resolverCandidates(resolver)
            if (resolvers.isEmpty()) {
                broadcastStatus(
                    status = "No resolver available.",
                    log = "Could not determine any DNS resolvers for the active network."
                )
                worker = null
                return@Thread
            }

            broadcastStatus(log = "Resolver candidates: ${resolvers.joinToString(", ")}")

            var effectiveResolver: String? = null
            for (candidate in resolvers) {
                broadcastStatus(log = "Probing resolver $candidate")
                if (!probeResolver(candidate, domain)) {
                    continue
                }

                broadcastStatus(status = "Handshaking.", log = "Probe ok via $candidate, starting handshake.")
                if (nativeHandshake(candidate, domain, password, options)) {
                    effectiveResolver = candidate
                    break
                }

                broadcastStatus(log = "Handshake failed via $candidate")
            }

            if (effectiveResolver == null) {
                broadcastStatus(status = "Handshake failed.", log = "All resolver candidates failed.")
                worker = null
                return@Thread
            }

            broadcastStatus(log = "Using VPN DNS server $DEFAULT_VPN_DNS inside the tunnel.")
            establishTunnel(DEFAULT_VPN_DNS)
        }.also { it.start() }
    }

    private fun establishTunnel(effectiveResolver: String? = null) {
        val clientIp = nativeGetClientIp()
        val serverIp = nativeGetServerIp()
        val netmask = nativeGetNetmask()
        val mtu = nativeGetMtu()

        broadcastStatus(
            status = "Establishing VPN.",
            log = "Client IP $clientIp, server IP $serverIp, mtu $mtu, prefix $netmask"
        )

        val builder = Builder()
            .setSession("Iodine")
            .setMtu(mtu)
            .addAddress(clientIp, netmask)
            .addRoute("0.0.0.0", 0)

        if (!effectiveResolver.isNullOrBlank()) {
            builder.addDnsServer(effectiveResolver)
        }

        tunInterface = builder.establish()

        val tunFd = tunInterface?.detachFd()
        if (tunFd == null) {
            broadcastStatus(status = "VPN establish failed.")
            nativeStop()
            worker = null
            return
        }

        broadcastStatus(status = "Connected.")
        val tunnelResult = nativeRunTunnel(tunFd)
        broadcastStatus(log = "Tunnel loop finished with code $tunnelResult")
        dohRelay?.stop()
        dohRelay = null
        tunInterface?.close()
        tunInterface = null
        broadcastStatus(status = "Stopped.")
        stopSelf()
        worker = null
    }

    private fun disconnectTunnel(status: String) {
        broadcastStatus(log = "disconnectTunnel called: $status")
        nativeStop()
        dohRelay?.stop()
        dohRelay = null
        tunInterface?.close()
        tunInterface = null
        worker = null
        broadcastStatus(status = status)
        stopSelf()
    }

    private fun resolverCandidates(explicitResolver: String): List<String> {
        if (explicitResolver.isNotBlank()) {
            return explicitResolver
                .split(',', ';', ' ', '\n', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }

        return detectSystemResolvers()
    }

    private fun detectSystemResolvers(): List<String> {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.activeNetwork ?: return emptyList()
        val linkProperties: LinkProperties = manager.getLinkProperties(active) ?: return emptyList()

        return linkProperties.dnsServers
            .map { it.hostAddress.orEmpty() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun probeResolver(resolver: String, domain: String): Boolean {
        val dnsServer = try {
            InetAddress.getByName(resolver)
        } catch (_: Exception) {
            broadcastStatus(log = "Probe failed via $resolver: invalid address")
            return false
        }

        val probes = listOf(
            Triple("NS", buildProbeName("probe", domain), 2),
            Triple("TXT", buildProbeName("z", domain), 16),
            Triple("NULL", buildProbeName("z", domain), 10)
        )

        var anySuccess = false
        for ((label, name, qtype) in probes) {
            val ok = runSingleProbe(dnsServer, resolver, name, qtype)
            if (ok) {
                broadcastStatus(log = "Probe $label ok via $resolver")
                anySuccess = true
            } else {
                broadcastStatus(log = "Probe $label failed via $resolver")
            }
        }

        return anySuccess
    }

    private fun runSingleProbe(dnsServer: InetAddress, resolver: String, name: String, qtype: Int): Boolean {
        return try {
            val socket = DatagramSocket()
            socket.soTimeout = PROBE_TIMEOUT_MS

            try {
                val query = buildDnsQuery(name, qtype)
                val packet = DatagramPacket(query, query.size, InetSocketAddress(dnsServer, 53))
                socket.send(packet)

                val response = ByteArray(2048)
                val reply = DatagramPacket(response, response.size)
                socket.receive(reply)
                reply.length >= 12
            } finally {
                socket.close()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun buildProbeName(prefix: String, domain: String): String {
        val suffix = Random.nextInt(0x10000).toString(16).padStart(4, '0')
        return "$prefix$suffix.$domain".lowercase(Locale.US)
    }

    private fun buildDnsQuery(name: String, qtype: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val id = Random.nextInt(0x10000)

        out.write((id shr 8) and 0xff)
        out.write(id and 0xff)
        out.write(0x01)
        out.write(0x00)
        out.write(0x00)
        out.write(0x01)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)

        name.split('.')
            .filter { it.isNotEmpty() }
            .forEach { label ->
                val bytes = label.toByteArray(Charsets.US_ASCII)
                out.write(bytes.size)
                out.write(bytes)
            }
        out.write(0x00)

        out.write((qtype shr 8) and 0xff)
        out.write(qtype and 0xff)
        out.write(0x00)
        out.write(0x01)

        return out.toByteArray()
    }

    private fun normalizeDohUrl(value: String): String? {
        if (value.isBlank()) {
            return null
        }

        val trimmed = value.trim()
        return if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
            trimmed
        } else {
            "https://$trimmed/dns-query"
        }
    }

    private fun broadcastStatus(status: String? = null, log: String? = null) {
        status?.let { Log.i("iodine-android", "STATUS: $it") }
        log?.let { Log.i("iodine-android", it) }
        val intent = Intent(ACTION_STATUS).setPackage(packageName)
        if (status != null) {
            intent.putExtra(EXTRA_STATUS, status)
        }
        if (log != null) {
            intent.putExtra(EXTRA_LOG, log)
        }
        sendBroadcast(intent)
    }

    companion object {
        const val ACTION_CONNECT = "se.kryo.iodine.action.CONNECT"
        const val ACTION_DISCONNECT = "se.kryo.iodine.action.DISCONNECT"
        const val ACTION_STATUS = "se.kryo.iodine.action.STATUS"

        const val EXTRA_SERVER = "server"
        const val EXTRA_USE_DOH = "use_doh"
        const val EXTRA_DOH_SERVER = "doh_server"
        const val EXTRA_DOMAIN = "domain"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_OPTIONS = "options"
        const val EXTRA_STATUS = "status"
        const val EXTRA_LOG = "log"
        private const val PROBE_TIMEOUT_MS = 1500
        private const val DEFAULT_VPN_DNS = "8.8.8.8"

        init {
            System.loadLibrary("iodine_android")
        }
    }
}
