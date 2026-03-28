package se.kryo.iodine

import android.util.Base64
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import javax.net.SocketFactory

class DohRelay(
    private val service: IodineVpnService,
    private val dohUrl: String,
    private val emitLog: (String) -> Unit
) {
    private val loopbackAddress: InetAddress = InetAddress.getByName("127.0.0.1")
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null
    private var httpClient: OkHttpClient? = null
    private lateinit var endpointHost: String
    private lateinit var resolvedAddresses: List<InetAddress>

    val localPort: Int
        get() = socket?.localPort ?: -1

    fun start(): Int {
        val httpUrl = dohUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid DoH URL: $dohUrl")

        endpointHost = httpUrl.host
        resolvedAddresses = InetAddress.getAllByName(endpointHost).toList()
        if (resolvedAddresses.isEmpty()) {
            throw IOException("Could not resolve DoH host $endpointHost")
        }

        emitLog(
            "Resolved DoH host $endpointHost to " +
                resolvedAddresses.joinToString(", ") { it.hostAddress ?: "unknown" }
        )

        httpClient = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2))
            .socketFactory(ProtectingSocketFactory(SocketFactory.getDefault(), service))
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return if (hostname.equals(endpointHost, ignoreCase = true)) {
                        resolvedAddresses
                    } else {
                        Dns.SYSTEM.lookup(hostname)
                    }
                }
            })
            .build()

        socket = DatagramSocket(0, loopbackAddress).apply {
            soTimeout = LOOP_TIMEOUT_MS
        }

        worker = Thread { runLoop(httpUrl) }.also {
            it.name = "iodine-doh-relay"
            it.start()
        }

        return localPort
    }

    fun stop() {
        socket?.close()
        socket = null
        httpClient?.dispatcher?.cancelAll()
        httpClient?.connectionPool?.evictAll()
        httpClient = null
        worker?.interrupt()
        worker = null
    }

    private fun runLoop(httpUrl: HttpUrl) {
        val currentSocket = socket ?: return
        val receiveBuffer = ByteArray(MAX_DNS_PACKET)

        while (!Thread.currentThread().isInterrupted) {
            try {
                val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                currentSocket.receive(packet)
                val query = packet.data.copyOf(packet.length)
                val response = executeDohQuery(httpUrl, query) ?: continue

                val reply = DatagramPacket(
                    response,
                    response.size,
                    packet.socketAddress as InetSocketAddress
                )
                currentSocket.send(reply)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                break
            } catch (e: Exception) {
                emitLog("DoH relay error: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun executeDohQuery(httpUrl: HttpUrl, query: ByteArray): ByteArray? {
        val client = httpClient ?: return null
        val encodedQuery = Base64.encodeToString(
            query,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val request = Request.Builder()
            .url(httpUrl.newBuilder().addQueryParameter("dns", encodedQuery).build())
            .header("Accept", "application/dns-message")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.protocol != Protocol.HTTP_2) {
                emitLog("DoH relay expected HTTP/2, got ${response.protocol}")
                return null
            }
            if (!response.isSuccessful) {
                emitLog("DoH relay HTTP ${response.code}")
                return null
            }

            val body = response.body?.bytes()
            if (body == null || body.isEmpty()) {
                emitLog("DoH relay got empty response body")
                return null
            }
            return body
        }
    }

    private class ProtectingSocketFactory(
        private val delegate: SocketFactory,
        private val service: IodineVpnService
    ) : SocketFactory() {
        override fun createSocket(): Socket = protect(delegate.createSocket())

        override fun createSocket(host: String?, port: Int): Socket =
            protect(delegate.createSocket(host, port))

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            protect(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            protect(delegate.createSocket(host, port))

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            protect(delegate.createSocket(address, port, localAddress, localPort))

        private fun protect(socket: Socket): Socket {
            service.protect(socket)
            return socket
        }
    }

    companion object {
        private const val MAX_DNS_PACKET = 4096
        private const val LOOP_TIMEOUT_MS = 1000
    }
}
