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
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val resolverSuggestions = listOf(
        "195.208.4.1",
        "195.208.5.1",
        "77.88.8.8",
        "77.88.8.1"
    )

    private lateinit var statusView: TextView
    private lateinit var serverView: AutoCompleteTextView
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
        serverView = findViewById(R.id.serverInput)
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
        serverView.threshold = 0

        restoreInputs()
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
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
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
            putExtra(IodineVpnService.EXTRA_SERVER, serverView.text.toString().trim())
            putExtra(IodineVpnService.EXTRA_DOMAIN, domainView.text.toString().trim())
            putExtra(IodineVpnService.EXTRA_PASSWORD, passwordView.text.toString())
            putExtra(IodineVpnService.EXTRA_OPTIONS, optionsView.text.toString().trim())
        }
        startService(intent)
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
        serverView.setText(prefs.getString(KEY_SERVER, "").orEmpty())

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

        optionsView.setText(prefs.getString(KEY_OPTIONS, "-f").orEmpty())
    }

    private fun saveInputs() {
        prefs.edit()
            .putString(KEY_SERVER, serverView.text.toString().trim())
            .putString(KEY_DOMAIN, domainView.text.toString().trim())
            .putString(KEY_PASSWORD, passwordView.text.toString())
            .putString(KEY_OPTIONS, optionsView.text.toString().trim())
            .apply()
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
        private const val PREFS_NAME = "iodine_prefs"
        private const val KEY_SERVER = "server"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_PASSWORD = "password"
        private const val KEY_OPTIONS = "options"
    }
}
