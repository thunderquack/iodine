package se.kryo.iodine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.VpnService
import android.os.ParcelFileDescriptor

class IodineVpnService : VpnService() {
    private var tunInterface: ParcelFileDescriptor? = null
    private var worker: Thread? = null

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
        nativeAttach()
    }

    override fun onDestroy() {
        disconnectTunnel("Service destroyed.")
        nativeDetach()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connectTunnel(intent)
            ACTION_DISCONNECT -> disconnectTunnel("Disconnected.")
        }
        return Service.START_NOT_STICKY
    }

    fun protectSocket(fd: Int): Boolean = protect(fd)

    fun emitLog(line: String) {
        broadcastStatus(log = line)
    }

    private fun connectTunnel(intent: Intent) {
        if (worker != null) {
            broadcastStatus(status = "Already connecting or connected.")
            return
        }

        val resolver = intent.getStringExtra(EXTRA_SERVER)?.trim().orEmpty()
        val domain = intent.getStringExtra(EXTRA_DOMAIN)?.trim().orEmpty()
        val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        val options = intent.getStringExtra(EXTRA_OPTIONS)?.trim().orEmpty()

        worker = Thread {
            val effectiveResolver = if (resolver.isNotBlank()) resolver else detectSystemResolver()
            if (effectiveResolver.isNullOrBlank()) {
                broadcastStatus(
                    status = "No resolver available.",
                    log = "Could not determine a DNS resolver for the active network."
                )
                worker = null
                return@Thread
            }

            broadcastStatus(status = "Handshaking.", log = "Using resolver $effectiveResolver")

            val ok = nativeHandshake(effectiveResolver, domain, password, options)
            if (!ok) {
                broadcastStatus(status = "Handshake failed.")
                worker = null
                return@Thread
            }

            val clientIp = nativeGetClientIp()
            val serverIp = nativeGetServerIp()
            val netmask = nativeGetNetmask()
            val mtu = nativeGetMtu()

            broadcastStatus(
                status = "Establishing VPN.",
                log = "Client IP $clientIp, server IP $serverIp, mtu $mtu, prefix $netmask"
            )

            tunInterface = Builder()
                .setSession("Iodine")
                .setMtu(mtu)
                .addAddress(clientIp, netmask)
                .addRoute("0.0.0.0", 0)
                .establish()

            val tunFd = tunInterface?.detachFd()
            if (tunFd == null) {
                broadcastStatus(status = "VPN establish failed.")
                nativeStop()
                worker = null
                return@Thread
            }

            broadcastStatus(status = "Connected.")
            nativeRunTunnel(tunFd)
            tunInterface?.close()
            tunInterface = null
            broadcastStatus(status = "Stopped.")
            stopSelf()
            worker = null
        }.also { it.start() }
    }

    private fun disconnectTunnel(status: String) {
        nativeStop()
        tunInterface?.close()
        tunInterface = null
        worker = null
        broadcastStatus(status = status)
        stopSelf()
    }

    private fun detectSystemResolver(): String? {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.activeNetwork ?: return null
        val linkProperties: LinkProperties = manager.getLinkProperties(active) ?: return null

        return linkProperties.dnsServers
            .map { it.hostAddress.orEmpty() }
            .firstOrNull { it.contains(".") }
            ?: linkProperties.dnsServers.firstOrNull()?.hostAddress
    }

    private fun broadcastStatus(status: String? = null, log: String? = null) {
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
        const val EXTRA_DOMAIN = "domain"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_OPTIONS = "options"
        const val EXTRA_STATUS = "status"
        const val EXTRA_LOG = "log"

        init {
            System.loadLibrary("iodine_android")
        }
    }
}
