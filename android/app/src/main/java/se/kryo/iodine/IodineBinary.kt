package se.kryo.iodine

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException

object IodineBinary {
    private const val ASSET_BASE = "bin"

    fun selectedAbi(): String {
        val supported = Build.SUPPORTED_ABIS.toList()
        return supported.firstOrNull { abi ->
            abi == "arm64-v8a" || abi == "armeabi-v7a"
        } ?: throw IOException("Unsupported ABI: ${supported.joinToString()}")
    }

    fun prepare(context: Context): File {
        val abi = selectedAbi()
        val binaryFile = File(context.filesDir, "iodine-$abi")
        val assetPath = "$ASSET_BASE/$abi/iodine"

        context.assets.open(assetPath).use { input ->
            binaryFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        if (!binaryFile.setExecutable(true, true)) {
            throw IOException("Could not mark ${binaryFile.absolutePath} executable")
        }

        return binaryFile
    }
}
