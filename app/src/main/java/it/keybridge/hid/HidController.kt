package it.keybridge.hid

/**
 * Safe boundary for a future device-specific USB HID implementation.
 *
 * Android's public USB APIs do not let an ordinary app turn the phone into a USB
 * keyboard. This starter deliberately has no shell/root writer and does not send
 * real secrets. Implement this only after verifying a supported HID Gadget path
 * on the target phone, with explicit authorization and a test device.
 */
interface HidController {
    suspend fun isSupported(): Boolean

    /**
     * Types a password only after the UI has confirmed the destination is focused.
     * The implementation must always send key-release reports in a finally block.
     */
    suspend fun typeItalianLayoutForTesting(text: CharArray): Result<Unit>
}
