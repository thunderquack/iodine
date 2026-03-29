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
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class ProbeActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var logView: TextView
    private lateinit var logFile: File
    private var activeInterfaceName: String? = null

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
        try {
            val addresses = InetAddress.getAllByName(target).joinToString(", ") { it.hostAddress ?: "?" }
            appendLog("DNS ok: $target -> $addresses")
        } catch (t: Throwable) {
            appendLog("DNS failed: $target -> ${t.javaClass.simpleName}: ${t.message}")
        }
        runExplicitDns(target)
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
            appendLog("DNS explicit timeout after 8s")
            return
        }
        if (result != null) {
            appendLog("DNS explicit ok: $target -> $result")
        } else {
            appendLog("DNS explicit failed: $target -> $error")
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
}
