package it.keybridge.hid

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UsbGadgetHidController(
    private val devicePath: String = "/dev/hidg0"
) : HidController {

    override suspend fun isSupported(): Boolean = withContext(Dispatchers.IO) {
        val file = File(devicePath)
        file.exists() && file.canWrite()
    }

    override suspend fun typeItalianLayoutForTesting(text: CharArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(devicePath)
            if (!file.exists()) {
                throw UnsupportedOperationException("Nodo USB HID ($devicePath) non trovato sul dispositivo.")
            }
            FileOutputStream(file).use { stream ->
                for (char in text) {
                    val (modifier, keyCode) = charToHidCode(char)
                    val report = byteArrayOf(modifier, 0, keyCode, 0, 0, 0, 0, 0)
                    stream.write(report)
                    stream.flush()
                    // Release key report
                    stream.write(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
                    stream.flush()
                }
            }
        }
    }

    private fun charToHidCode(char: Char): Pair<Byte, Byte> {
        return when (char) {
            in 'a'..'z' -> Pair(0, (0x04 + (char - 'a')).toByte())
            in 'A'..'Z' -> Pair(0x02, (0x04 + (char - 'A')).toByte())
            in '1'..'9' -> Pair(0, (0x1E + (char - '1')).toByte())
            '0' -> Pair(0, 0x27)
            ' ' -> Pair(0, 0x2C)
            '\n' -> Pair(0, 0x28)
            else -> Pair(0, 0x2C)
        }
    }
}
