package se.kryo.iodine.probe

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class ProbeActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var logView: TextView
    private lateinit var logFile: File
    private var activeNetwork: Network? = null
    private var activeInterfaceName: String? = null
    private var activeDnsServers: List<InetAddress> = emptyList()

    external fun nativePingIcmp(target: String, count: Int, timeoutMs: Int): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_probe)
        logView = findViewById(R.id.logView)
        logFile = File(filesDir, "probe.log")
        logFile.writeText("")
        appendLog("Probe activity started.")
        val mode = intent.getStringExtra(EXTRA_MODE)?.lowercase() ?: "all"
        val target = intent.getStringExtra(EXTRA_TARGET)?.trim().orEmpty().ifEmpty { "1.1.1.1" }
        val url = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty().ifEmpty { "http://neverssl.com" }
        val count = intent.getIntExtra(EXTRA_COUNT, 3).coerceIn(1, 10)
        appendLog("mode=$mode target=$target url=$url count=$count")
        logActiveNetwork()
        executor.execute {
            when (mode) {
                "ping" -> runPing(target, count)
                "dns" -> runDns(target)
                "http" -> runHttp(url)
                else -> {
                    runDns(target)
                    runPing(target, count)
                    runHttp(url)
                }
            }
        }
    }

    private fun logActiveNetwork() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.activeNetwork
        val capabilities = active?.let { manager.getNetworkCapabilities(it) }
        val linkProperties = active?.let { manager.getLinkProperties(it) }
        activeNetwork = active
        appendLog("network active=${active != null}")
        if (active != null) {
            val bound = manager.bindProcessToNetwork(active)
            appendLog("network bindProcessToNetwork=$bound")
        }
        if (capabilities != null) {
            val transports = buildList {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
            }
            appendLog("network transports=${transports.joinToString(",")}")
        }
        if (linkProperties != null) {
            activeInterfaceName = linkProperties.interfaceName
            activeDnsServers = linkProperties.dnsServers
            appendLog("network iface=${linkProperties.interfaceName}")
            val dns = linkProperties.dnsServers.joinToString(", ") { it.hostAddress ?: "?" }
            appendLog("network dns=$dns")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun runDns(target: String) {
        appendLog("DNS start: $target")
        val startedAt = System.nanoTime()
        try {
            val addresses = InetAddress.getAllByName(target).joinToString(", ") { it.hostAddress ?: "?" }
            appendLog("DNS ok after ${elapsedMs(startedAt)}ms: $target -> $addresses")
        } catch (t: Throwable) {
            appendLog("DNS failed after ${elapsedMs(startedAt)}ms: $target -> ${t.javaClass.simpleName}: ${t.message}")
        }
        runExplicitDns(target)
        runUdpDns(target)
    }

    private fun runExplicitDns(target: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            appendLog("DNS explicit skipped: API<29")
            return
        }
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.activeNetwork
        if (active == null) {
            appendLog("DNS explicit skipped: no active network")
            return
        }
        appendLog("DNS explicit start: $target")
        val startedAt = System.nanoTime()
        val latch = CountDownLatch(1)
        var result: String? = null
        var error: String? = null
        DnsResolver.getInstance().query(
            active,
            target,
            DnsResolver.FLAG_EMPTY,
            executor,
            CancellationSignal(),
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    result = answer.joinToString(", ") { it.hostAddress ?: "?" } + " rcode=$rcode"
                    latch.countDown()
                }

                override fun onError(errorCode: DnsResolver.DnsException) {
                    error = "${errorCode.javaClass.simpleName}: ${errorCode.message}"
                    latch.countDown()
                }
            }
        )
        if (!latch.await(8, TimeUnit.SECONDS)) {
            appendLog("DNS explicit timeout after ${elapsedMs(startedAt)}ms")
            return
        }
        if (result != null) {
            appendLog("DNS explicit ok after ${elapsedMs(startedAt)}ms: $target -> $result")
        } else {
            appendLog("DNS explicit failed after ${elapsedMs(startedAt)}ms: $target -> $error")
        }
    }

    private fun runUdpDns(target: String) {
        val network = activeNetwork
        val dnsServer = activeDnsServers.firstOrNull()
        if (network == null || dnsServer == null) {
            appendLog("DNS udp skipped: network=${network != null} dnsServer=${dnsServer != null}")
            return
        }
        val startedAt = System.nanoTime()
        appendLog("DNS udp start: $target via ${dnsServer.hostAddress}")
        try {
            val queryId = 0x4242
            val question = buildDnsQuery(queryId, target)
            val socket = DatagramSocket()
            socket.soTimeout = 4000
            network.bindSocket(socket)
            socket.connect(InetSocketAddress(dnsServer, 53))
            socket.send(DatagramPacket(question, question.size))

            val buf = ByteArray(1500)
            val reply = DatagramPacket(buf, buf.size)
            socket.receive(reply)
            socket.close()

            val summary = parseDnsAnswerSummary(reply.data, reply.length)
            appendLog("DNS udp ok after ${elapsedMs(startedAt)}ms: id=${queryId} len=${reply.length} $summary")
        } catch (t: Throwable) {
            appendLog("DNS udp failed after ${elapsedMs(startedAt)}ms: $target -> ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun runPing(target: String, count: Int) {
        appendLog("Ping start: target=$target count=$count")
        try {
            val output = nativePingIcmp(target, count, 2500).trim()
            if (output.isNotEmpty()) {
                appendLog(output)
            }
        } catch (t: Throwable) {
            appendLog("native ping failed: $target -> ${t.javaClass.simpleName}: ${t.message}")
        }
        runShellPing(listOf("ping", "-c", count.toString(), target), "shell ping")
        activeInterfaceName?.takeIf { it.isNotBlank() }?.let { iface ->
            runShellPing(listOf("ping", "-I", iface, "-c", count.toString(), target), "shell ping via $iface")
        }
    }

    private fun runShellPing(command: List<String>, label: String) {
        appendLog("$label start: ${command.joinToString(" ")}")
        try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                appendLog("$label timeout after 8s")
                return
            }
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            appendLog("$label exit=${process.exitValue()}")
            if (output.isNotEmpty()) {
                appendLog(output)
            }
        } catch (t: Throwable) {
            appendLog("$label failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun runHttp(url: String) {
        appendLog("HTTP start: $url")
        try {
            val client = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .callTimeout(8, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                appendLog("HTTP ${response.code} ${response.message} via ${response.protocol} for $url")
            }
        } catch (t: Throwable) {
            appendLog("HTTP failed: $url -> ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun appendLog(message: String) {
        Log.i(TAG, message)
        if (::logFile.isInitialized) {
            logFile.appendText(message + "\n")
        }
        runOnUiThread {
            val existing = logView.text?.toString().orEmpty()
            logView.text = if (existing.isEmpty()) {
                message
            } else {
                "$existing\n$message"
            }
        }
    }

    companion object {
        const val TAG = "iodine-probe"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TARGET = "target"
        const val EXTRA_URL = "url"
        const val EXTRA_COUNT = "count"

        init {
            System.loadLibrary("iodine_probe")
        }
    }

    private fun elapsedMs(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private fun buildDnsQuery(id: Int, host: String): ByteArray {
        val out = ArrayList<Byte>()
        out += ((id ushr 8) and 0xff).toByte()
        out += (id and 0xff).toByte()
        out += 0x01
        out += 0x00
        out += 0x00
        out += 0x01
        out += 0x00
        out += 0x00
        out += 0x00
        out += 0x00
        out += 0x00
        out += 0x00
        host.split('.').forEach { label ->
            val bytes = label.toByteArray(StandardCharsets.US_ASCII)
            out += bytes.size.toByte()
            bytes.forEach { out += it }
        }
        out += 0x00
        out += 0x00
        out += 0x01
        out += 0x00
        out += 0x01
        return out.toByteArray()
    }

    private fun parseDnsAnswerSummary(buf: ByteArray, len: Int): String {
        if (len < 12) {
            return "short-reply"
        }
        val rcode = buf[3].toInt() and 0x0f
        val qd = ((buf[4].toInt() and 0xff) shl 8) or (buf[5].toInt() and 0xff)
        val an = ((buf[6].toInt() and 0xff) shl 8) or (buf[7].toInt() and 0xff)
        return "rcode=$rcode qd=$qd an=$an"
    }
}
