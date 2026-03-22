package se.kryo.iodine

import java.io.File

object ShellRunner {
    fun start(binary: File, args: String): Process {
        val command = if (args.isBlank()) {
            binary.absolutePath
        } else {
            "${binary.absolutePath} $args"
        }

        return ProcessBuilder("su", "-c", command)
            .redirectErrorStream(false)
            .start()
    }
}
