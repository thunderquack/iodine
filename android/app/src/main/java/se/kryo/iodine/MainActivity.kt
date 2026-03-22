package se.kryo.iodine

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var commandView: EditText
    private lateinit var logView: TextView
    private lateinit var prepareButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var preparedBinary: File? = null
    private var runningProcess: Process? = null
    @Volatile
    private var stopping = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.statusText)
        commandView = findViewById(R.id.commandInput)
        logView = findViewById(R.id.logText)
        prepareButton = findViewById(R.id.prepareButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        commandView.setText("-f")
        status("Idle. Root and TUN support are still required on the device.")
        appendLog("APK contains the iodine client binary in app assets.")

        prepareButton.setOnClickListener { prepareBinary() }
        startButton.setOnClickListener { startIodine() }
        stopButton.setOnClickListener { stopIodine() }
    }

    override fun onDestroy() {
        stopIodine()
        super.onDestroy()
    }

    private fun prepareBinary() {
        if (runningProcess != null) {
            appendLog("Stop the running process before re-preparing the binary.")
            return
        }

        runInBackground {
            try {
                val binary = IodineBinary.prepare(this)
                preparedBinary = binary
                runOnUiThread {
                    status("Prepared ${binary.name} for ${IodineBinary.selectedAbi()}.")
                    appendLog("Prepared binary at ${binary.absolutePath}")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status("Prepare failed.")
                    appendLog("Prepare failed: ${e.message}")
                }
            }
        }
    }

    private fun startIodine() {
        if (runningProcess != null) {
            appendLog("The iodine client is already running.")
            return
        }

        runInBackground {
            try {
                val binary = preparedBinary ?: IodineBinary.prepare(this).also { preparedBinary = it }
                val args = commandView.text.toString().trim()
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
        appendLog("Stopping iodine process.")
        process.destroy()
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
