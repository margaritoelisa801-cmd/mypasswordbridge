package it.keybridge.hid

class UnsupportedHidController : HidController {
    override suspend fun isSupported(): Boolean = false

    override suspend fun typeItalianLayoutForTesting(text: CharArray): Result<Unit> =
        Result.failure(
            UnsupportedOperationException(
                "USB HID diretto non è disponibile: serve verificare il supporto del telefono."
            )
        )
}
