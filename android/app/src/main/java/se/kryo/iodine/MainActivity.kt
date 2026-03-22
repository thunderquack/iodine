package se.kryo.iodine

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var serverView: EditText
    private lateinit var domainView: EditText
    private lateinit var passwordView: EditText
    private lateinit var optionsView: EditText
    private lateinit var logView: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button

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

        optionsView.setText("-f")
        status("Idle.")
        appendLog("Connect uses Android VpnService, not root.")
        appendLog("Server is optional. If blank, the app tries the active network's DNS resolver.")

        val filter = IntentFilter(IodineVpnService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }

        connectButton.setOnClickListener { connect() }
        disconnectButton.setOnClickListener { disconnect() }
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }

    private fun connect() {
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

    private fun appendLog(message: String) {
        val current = logView.text.toString()
        logView.text = if (current.isEmpty()) message else "$current\n$message"
    }
}
