package se.kryo.iodine

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var serverView: EditText
    private lateinit var domainView: EditText
    private lateinit var passwordView: EditText
    private lateinit var optionsView: EditText
    private lateinit var logView: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button

    private var preparedBinary: File? = null
    private var runningProcess: Process? = null
    @Volatile
    private var stopping = false

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
        status("Idle. Root and TUN support are still required on the device.")
        appendLog("APK contains the iodine client binary in app assets.")
        appendLog("Fill in the server and delegated domain, then tap Connect.")

        connectButton.setOnClickListener { startIodine() }
        disconnectButton.setOnClickListener { stopIodine() }
    }

    override fun onDestroy() {
        stopIodine()
        super.onDestroy()
    }

    private fun startIodine() {
        if (runningProcess != null) {
            appendLog("The iodine client is already running.")
            return
        }

        val domain = domainView.text.toString().trim()
        if (domain.isBlank()) {
            status("Missing domain.")
            appendLog("Enter the delegated iodine domain before connecting.")
            return
        }

        runInBackground {
            try {
                val binary = preparedBinary ?: IodineBinary.prepare(this).also { preparedBinary = it }
                val args = buildCommandArgs()
                stopping = false

                runOnUiThread {
                    status("Launching iodine via su.")
                    appendLog("Command: ${binary.absolutePath}${if (args.isBlank()) "" else " $args"}")
                }

                val process = ShellRunner.start(binary, args)
                runningProcess = process

                val stdout = Thread {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { appendLogFromWorker(it) }
                    }
                }
                val stderr = Thread {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { appendLogFromWorker(it) }
                    }
                }

                stdout.start()
                stderr.start()

                val exitCode = process.waitFor()
                stdout.join()
                stderr.join()
                runningProcess = null

                runOnUiThread {
                    if (stopping) {
                        status("Stopped.")
                        appendLog("iodine process terminated by app request.")
                    } else {
                        status("Exited with code $exitCode.")
                        appendLog("iodine process exited with code $exitCode.")
                    }
                }
            } catch (e: Exception) {
                runningProcess = null
                runOnUiThread {
                    status("Launch failed.")
                    appendLog("Launch failed: ${e.message}")
                }
            }
        }
    }

    private fun stopIodine() {
        val process = runningProcess ?: return
        stopping = true
        status("Disconnecting.")
        appendLog("Stopping iodine process.")
        process.destroy()
    }

    private fun buildCommandArgs(): String {
        val parts = mutableListOf<String>()
        val options = optionsView.text.toString().trim()
        val server = serverView.text.toString().trim()
        val domain = domainView.text.toString().trim()
        val password = passwordView.text.toString()

        if (options.isNotBlank()) {
            parts += options
        }
        if (password.isNotBlank()) {
            parts += "-P"
            parts += shellQuote(password)
        }
        if (server.isNotBlank()) {
            parts += shellQuote(server)
        }
        parts += shellQuote(domain)

        return parts.joinToString(" ")
    }

    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\\''")}'"
    }

    private fun runInBackground(block: () -> Unit) {
        Thread(block).start()
    }

    private fun status(message: String) {
        statusView.text = message
    }

    private fun appendLog(message: String) {
        val current = logView.text.toString()
        logView.text = if (current.isEmpty()) message else "$current\n$message"
    }

    private fun appendLogFromWorker(message: String) {
        runOnUiThread { appendLog(message) }
    }
}
