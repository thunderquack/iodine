package se.kryo.iodine

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
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
        resolvedAddresses = InetAddress.getAllByName(endpointHost)
            .sortedWith(
                compareBy<InetAddress> { if (it.address.size == 4) 0 else 1 }
                    .thenBy { it.hostAddress ?: "" }
            )
        if (resolvedAddresses.isEmpty()) {
            throw IOException("Could not resolve DoH host $endpointHost")
        }

        emitLog(
            "Resolved DoH host $endpointHost to " +
                resolvedAddresses.joinToString(", ") { it.hostAddress ?: "unknown" }
        )

        httpClient = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
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
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(READ_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
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
                emitLog("DoH relay query ${describeDnsQuery(query)} from ${packet.socketAddress}")
                val response = executeDohQuery(httpUrl, query) ?: continue
                emitLog("DoH relay response ${describeDnsResponse(response)}")

                val reply = DatagramPacket(
                    response,
                    response.size,
                    packet.socketAddress as InetSocketAddress
                )
                currentSocket.send(reply)
                emitLog("DoH relay reply ${response.size} bytes to ${packet.socketAddress}")
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                break
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    emitLog("DoH relay error: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun executeDohQuery(httpUrl: HttpUrl, query: ByteArray): ByteArray? {
        val client = httpClient ?: return null
        val request = Request.Builder()
            .url(httpUrl)
            .header("Accept", "application/dns-message")
            .header("Content-Type", "application/dns-message")
            .post(query.toRequestBody("application/dns-message".toMediaType()))
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
            emitLog("DoH relay HTTP/2 ${response.code}, ${body.size} bytes")
            return body
        }
    }

    private fun describeDnsQuery(query: ByteArray): String {
        if (query.size < 12) {
            return "len=${query.size}"
        }

        val id = ((query[0].toInt() and 0xff) shl 8) or (query[1].toInt() and 0xff)
        var offset = 12
        val labels = mutableListOf<String>()

        while (offset < query.size) {
            val length = query[offset].toInt() and 0xff
            if (length == 0) {
                offset++
                break
            }
            offset++
            if (offset + length > query.size) {
                return "id=$id len=${query.size}"
            }
            labels += query.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
            offset += length
        }

        if (offset + 4 > query.size) {
            return "id=$id qname=${labels.joinToString(".")}"
        }

        val qtype = ((query[offset].toInt() and 0xff) shl 8) or (query[offset + 1].toInt() and 0xff)
        return "id=$id qtype=$qtype qname=${labels.joinToString(".")}"
    }

    private fun describeDnsResponse(response: ByteArray): String {
        if (response.size < 12) {
            return "len=${response.size}"
        }

        val id = ((response[0].toInt() and 0xff) shl 8) or (response[1].toInt() and 0xff)
        val flags = ((response[2].toInt() and 0xff) shl 8) or (response[3].toInt() and 0xff)
        val rcode = flags and 0x000f
        val qd = ((response[4].toInt() and 0xff) shl 8) or (response[5].toInt() and 0xff)
        val an = ((response[6].toInt() and 0xff) shl 8) or (response[7].toInt() and 0xff)
        val ns = ((response[8].toInt() and 0xff) shl 8) or (response[9].toInt() and 0xff)
        val ar = ((response[10].toInt() and 0xff) shl 8) or (response[11].toInt() and 0xff)
        val truncated = (flags and 0x0200) != 0

        return "id=$id rcode=$rcode qd=$qd an=$an ns=$ns ar=$ar tc=$truncated len=${response.size}"
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
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 10000
    }
}
