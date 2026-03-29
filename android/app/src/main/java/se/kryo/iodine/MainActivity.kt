package se.kryo.iodine

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.switchmaterial.SwitchMaterial
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.Collections

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val resolverSuggestions = listOf(
        "195.208.4.1",
        "195.208.5.1",
        "77.88.8.8",
        "77.88.8.1"
    )
    private val dohSuggestions = listOf(
        "common.dot.dns.yandex.net"
    )

    private lateinit var statusView: TextView
    private lateinit var transportSwitch: SwitchMaterial
    private lateinit var serverLabel: TextView
    private lateinit var serverView: AutoCompleteTextView
    private lateinit var dohLabel: TextView
    private lateinit var dohView: AutoCompleteTextView
    private lateinit var domainView: EditText
    private lateinit var passwordView: EditText
    private lateinit var optionsView: EditText
    private lateinit var logView: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var collectNetworkButton: Button
    private lateinit var clearLogButton: Button

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            status("VPN permission denied.")
            appendLog("Android did not grant VPN permission.")
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(IodineVpnService.EXTRA_STATUS)
            val logLine = intent.getStringExtra(IodineVpnService.EXTRA_LOG)

            if (!status.isNullOrBlank()) {
                status(status)
            }
            if (!logLine.isNullOrBlank()) {
                appendLog(logLine)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.statusText)
        transportSwitch = findViewById(R.id.transportSwitch)
        serverLabel = findViewById(R.id.serverLabel)
        serverView = findViewById(R.id.serverInput)
        dohLabel = findViewById(R.id.dohLabel)
        dohView = findViewById(R.id.dohInput)
        domainView = findViewById(R.id.domainInput)
        passwordView = findViewById(R.id.passwordInput)
        optionsView = findViewById(R.id.optionsInput)
        logView = findViewById(R.id.logText)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        collectNetworkButton = findViewById(R.id.collectNetworkButton)
        clearLogButton = findViewById(R.id.clearLogButton)

        serverView.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                resolverSuggestions
            )
        )
        dohView.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                dohSuggestions
            )
        )
        configureDropdown(serverView)
        configureDropdown(dohView)

        restoreInputs()
        updateTransportUi(transportSwitch.isChecked)
        status("Idle.")
        appendLog("Connect uses Android VpnService, not root.")
        appendLog("Server is optional. If blank, the app tries the active network's DNS resolver.")
        appendLog(buildNetworkSummary())

        val filter = IntentFilter(IodineVpnService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }

        connectButton.setOnClickListener { connect() }
        disconnectButton.setOnClickListener { disconnect() }
        collectNetworkButton.setOnClickListener { collectNetworkSnapshot() }
        clearLogButton.setOnClickListener { clearLog() }
        logView.setOnClickListener { copyLogToClipboard() }
        transportSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateTransportUi(isChecked)
        }

        handleLaunchIntent(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun connect() {
        saveInputs()

        val domain = domainView.text.toString().trim()
        if (domain.isBlank()) {
            status("Missing domain.")
            appendLog("Enter the delegated iodine domain before connecting.")
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, IodineVpnService::class.java).apply {
            action = IodineVpnService.ACTION_CONNECT
            putExtra(IodineVpnService.EXTRA_USE_DOH, transportSwitch.isChecked)
            putExtra(IodineVpnService.EXTRA_SERVER, serverView.text.toString().trim())
            putExtra(IodineVpnService.EXTRA_DOH_SERVER, dohView.text.toString().trim())
            putExtra(IodineVpnService.EXTRA_DOMAIN, domainView.text.toString().trim())
            putExtra(IodineVpnService.EXTRA_PASSWORD, passwordView.text.toString())
            putExtra(IodineVpnService.EXTRA_OPTIONS, optionsView.text.toString().trim())
        }
        startService(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) {
            return
        }

        if (intent.hasExtra(IodineVpnService.EXTRA_USE_DOH)) {
            transportSwitch.isChecked = intent.getBooleanExtra(IodineVpnService.EXTRA_USE_DOH, transportSwitch.isChecked)
        }
        intent.getStringExtra(IodineVpnService.EXTRA_SERVER)?.let { serverView.setText(it) }
        intent.getStringExtra(IodineVpnService.EXTRA_DOH_SERVER)?.let { dohView.setText(it) }
        intent.getStringExtra(IodineVpnService.EXTRA_DOMAIN)?.let { domainView.setText(it) }
        intent.getStringExtra(IodineVpnService.EXTRA_PASSWORD)?.let { passwordView.setText(it) }
        intent.getStringExtra(IodineVpnService.EXTRA_OPTIONS)?.let { optionsView.setText(it) }

        updateTransportUi(transportSwitch.isChecked)

        when (intent.action) {
            ACTION_ADB_CONNECT -> {
                appendLog("ADB requested connect.")
                connect()
            }
            ACTION_ADB_DISCONNECT -> {
                appendLog("ADB requested disconnect.")
                disconnect()
            }
            ACTION_ADB_HTTP_PROBE -> {
                appendLog("ADB requested HTTP probe.")
                runHttpProbe()
            }
            ACTION_ADB_ICMP_PROBE -> {
                val target = intent.getStringExtra(EXTRA_PROBE_TARGET)?.trim().orEmpty()
                appendLog("ADB requested ICMP probe: ${if (target.isBlank()) DEFAULT_ICMP_PROBE_TARGET else target}")
                runIcmpProbe(if (target.isBlank()) DEFAULT_ICMP_PROBE_TARGET else target)
            }
            ACTION_ADB_EXTERNAL_PROBE -> {
                val target = intent.getStringExtra(EXTRA_PROBE_URL)?.trim().orEmpty()
                val probePackage = intent.getStringExtra(EXTRA_PROBE_PACKAGE)?.trim().orEmpty()
                appendLog("ADB requested external probe: ${if (target.isBlank()) DEFAULT_EXTERNAL_PROBE_URL else target}")
                if (probePackage.isNotBlank()) {
                    appendLog("ADB requested external probe package: $probePackage")
                }
                launchExternalProbe(
                    if (target.isBlank()) DEFAULT_EXTERNAL_PROBE_URL else target,
                    probePackage.ifBlank { null }
                )
            }
        }
    }

    private fun disconnect() {
        val intent = Intent(this, IodineVpnService::class.java).apply {
            action = IodineVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    private fun status(message: String) {
        statusView.text = message
    }

    private fun restoreInputs() {
        transportSwitch.isChecked = prefs.getBoolean(KEY_USE_DOH, false)
        serverView.setText(prefs.getString(KEY_SERVER, "").orEmpty())
        dohView.setText(prefs.getString(KEY_DOH_SERVER, "").orEmpty())

        if (prefs.contains(KEY_DOMAIN)) {
            domainView.setText(prefs.getString(KEY_DOMAIN, "").orEmpty())
        } else {
            domainView.setText(BuildConfig.DEFAULT_DOMAIN)
        }

        if (prefs.contains(KEY_PASSWORD)) {
            passwordView.setText(prefs.getString(KEY_PASSWORD, "").orEmpty())
        } else {
            passwordView.setText(BuildConfig.DEFAULT_PASSWORD)
        }

        optionsView.setText(prefs.getString(KEY_OPTIONS, DEFAULT_OPTIONS).orEmpty())
    }

    private fun saveInputs() {
        prefs.edit()
            .putBoolean(KEY_USE_DOH, transportSwitch.isChecked)
            .putString(KEY_SERVER, serverView.text.toString().trim())
            .putString(KEY_DOH_SERVER, dohView.text.toString().trim())
            .putString(KEY_DOMAIN, domainView.text.toString().trim())
            .putString(KEY_PASSWORD, passwordView.text.toString())
            .putString(KEY_OPTIONS, optionsView.text.toString().trim())
            .apply()
    }

    private fun updateTransportUi(useDoh: Boolean) {
        serverLabel.isVisible = !useDoh
        serverView.isVisible = !useDoh
        dohLabel.isVisible = useDoh
        dohView.isVisible = useDoh
    }

    private fun configureDropdown(view: AutoCompleteTextView) {
        view.threshold = 0
        view.setOnClickListener { view.showDropDown() }
        view.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                view.showDropDown()
            }
        }
        view.setOnTouchListener { _, _ ->
            view.showDropDown()
            false
        }
    }

    private fun collectNetworkSnapshot() {
        appendLog(buildNetworkSummary())
        status("Network configuration collected.")
    }

    private fun appendLog(message: String) {
        val current = logView.text.toString()
        logView.text = if (current.isEmpty()) message else "$current\n$message"
    }

    private fun clearLog() {
        logView.text = ""
        status("Log cleared.")
    }

    private fun runHttpProbe() {
        status("Running HTTP probe.")
        Thread {
            appendLogOnUi("HTTP probe note: this runs inside the VPN app process and may not reflect how external apps are routed through VpnService.")

            val targets = listOf(
                "http://1.1.1.1",
                "https://one.one.one.one",
                "https://example.com",
                "http://neverssl.com"
            )

            for (target in targets) {
                appendLogOnUi("HTTP probe starting: $target")
                val host = try {
                    URL(target).host
                } catch (e: Exception) {
                    appendLogOnUi("HTTP probe skipped: $target -> bad URL")
                    continue
                }

                try {
                    val resolved = InetAddress.getAllByName(host)
                    appendLogOnUi(
                        "DNS probe result: $host -> ${
                            resolved.joinToString(", ") { it.hostAddress ?: "?" }
                        }"
                    )
                } catch (e: Exception) {
                    appendLogOnUi("DNS probe failed: $host -> ${e.message ?: e.javaClass.simpleName}")
                }

                appendLogOnUi("Ping probe: $host")
                appendLogOnUi(runCommandProbe(listOf("/system/bin/ping", "-c", "1", "-W", "3", host), "Ping"))

                appendLogOnUi("Traceroute probe: $host")
                appendLogOnUi(
                    runCommandProbe(
                        listOf("/system/bin/toybox", "traceroute", "-n", "-m", "5", "-q", "1", "-w", "1", host),
                        "Traceroute"
                    )
                )

                try {
                    val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                        instanceFollowRedirects = false
                        requestMethod = "GET"
                    }
                    connection.connect()
                    val code = connection.responseCode
                    val location = connection.getHeaderField("Location")
                    val summary = buildString {
                        append("HTTP probe result: ")
                        append(target)
                        append(" -> ")
                        append(code)
                        if (!location.isNullOrBlank()) {
                            append(", Location=")
                            append(location)
                        }
                    }
                    appendLogOnUi(summary)
                    connection.inputStream?.close()
                    connection.errorStream?.close()
                    connection.disconnect()
                } catch (e: Exception) {
                    appendLogOnUi("HTTP probe failed: $target -> ${e.message ?: e.javaClass.simpleName}")
                }
            }

            runOnUiThread { status("HTTP probe finished.") }
        }.start()
    }

    private fun runIcmpProbe(target: String) {
        status("Running ICMP probe.")
        Thread {
            appendLogOnUi("ICMP probe note: this runs inside the VPN app process.")
            appendLogOnUi(buildNetworkSummary())

            val activeInterface = try {
                val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val active = manager.activeNetwork
                manager.getLinkProperties(active)?.interfaceName
            } catch (_: Exception) {
                null
            }

            appendLogOnUi("ICMP probe starting: $target")
            appendLogOnUi(runCommandProbe(listOf("/system/bin/ping", "-c", "3", "-W", "3", target), "Ping"))

            if (!activeInterface.isNullOrBlank()) {
                appendLogOnUi("ICMP probe active interface: $activeInterface")
                appendLogOnUi(
                    runCommandProbe(
                        listOf("/system/bin/ping", "-I", activeInterface, "-c", "3", "-W", "3", target),
                        "Ping[$activeInterface]"
                    )
                )
            }

            runOnUiThread { status("ICMP probe finished.") }
        }.start()
    }

    private fun launchExternalProbe(target: String, packageName: String? = null) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!packageName.isNullOrBlank()) {
                    setPackage(packageName)
                }
            }
            appendLog(
                if (packageName.isNullOrBlank()) {
                    "External probe launching browser: $target"
                } else {
                    "External probe launching browser: $target via $packageName"
                }
            )
            status("Launching external probe.")
            startActivity(intent)
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            appendLog("External probe failed to launch: $target -> $reason")
            status("External probe launch failed.")
        }
    }

    private fun runCommandProbe(command: List<String>, label: String): String {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return "$label probe failed: timeout"
            }

            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            val summary = output.lineSequence().take(6).joinToString(" | ")
            if (summary.isBlank()) {
                "$label probe exit=${process.exitValue()} with no output"
            } else {
                "$label probe exit=${process.exitValue()}: $summary"
            }
        } catch (e: Exception) {
            "$label probe failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun appendLogOnUi(message: String) {
        Log.i("iodine-android", message)
        runOnUiThread { appendLog(message) }
    }

    private fun copyLogToClipboard() {
        val text = logView.text.toString()
        if (text.isBlank()) {
            status("Nothing to copy.")
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("iodine-log", text))
        status("Log copied to clipboard.")
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun buildNetworkSummary(): String {
        val lines = mutableListOf<String>()
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val linkProperties = activeNetwork?.let { connectivityManager.getLinkProperties(it) }

        lines += "Network snapshot:"
        if (activeNetwork == null) {
            lines += "Active network: none"
        } else {
            lines += "Active network: present"
        }

        if (capabilities != null) {
            val transports = mutableListOf<String>()
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports += "WIFI"
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports += "CELLULAR"
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports += "ETHERNET"
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports += "VPN"
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports += "BLUETOOTH"
            lines += "Transports: ${if (transports.isEmpty()) "unknown" else transports.joinToString(", ")}"
            lines += "Validated: ${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
            lines += "Internet: ${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}"
        }

        if (linkProperties != null) {
            lines += "Link interface: ${linkProperties.interfaceName ?: "unknown"}"
            if (linkProperties.linkAddresses.isNotEmpty()) {
                lines += "Link addresses:"
                linkProperties.linkAddresses.forEach { lines += "  ${it.address.hostAddress}/${it.prefixLength}" }
            }
            if (linkProperties.routes.isNotEmpty()) {
                lines += "Routes:"
                linkProperties.routes.forEach { lines += "  $it" }
            }
            if (linkProperties.dnsServers.isNotEmpty()) {
                lines += "DNS servers:"
                linkProperties.dnsServers.forEach { lines += "  ${it.hostAddress}" }
            }
        }

        lines += "Interfaces:"
        Collections.list(NetworkInterface.getNetworkInterfaces() ?: return lines.joinToString("\n"))
            .sortedBy { it.name }
            .forEach { networkInterface ->
            lines += "  ${networkInterface.name} up=${networkInterface.isUp} loopback=${networkInterface.isLoopback}"
            networkInterface.interfaceAddresses.forEach { address ->
                lines += "    ${address.address.hostAddress}/${address.networkPrefixLength}"
            }
            }

        return lines.joinToString("\n")
    }

    companion object {
        const val ACTION_ADB_CONNECT = "se.kryo.iodine.action.ADB_CONNECT"
        const val ACTION_ADB_DISCONNECT = "se.kryo.iodine.action.ADB_DISCONNECT"
        const val ACTION_ADB_HTTP_PROBE = "se.kryo.iodine.action.ADB_HTTP_PROBE"
        const val ACTION_ADB_ICMP_PROBE = "se.kryo.iodine.action.ADB_ICMP_PROBE"
        const val ACTION_ADB_EXTERNAL_PROBE = "se.kryo.iodine.action.ADB_EXTERNAL_PROBE"
        const val EXTRA_PROBE_URL = "probe_url"
        const val EXTRA_PROBE_PACKAGE = "probe_package"
        const val EXTRA_PROBE_TARGET = "probe_target"
        private const val PREFS_NAME = "iodine_prefs"
        private const val KEY_USE_DOH = "use_doh"
        private const val KEY_SERVER = "server"
        private const val KEY_DOH_SERVER = "doh_server"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_PASSWORD = "password"
        private const val KEY_OPTIONS = "options"
        private const val DEFAULT_OPTIONS = "-f -r -T CNAME -O Base32 -L 0 -m 200 -M 200"
        private const val DEFAULT_ICMP_PROBE_TARGET = "1.1.1.1"
        private const val DEFAULT_EXTERNAL_PROBE_URL = "http://neverssl.com"
    }
}
