package it.keybridge.data

import it.keybridge.security.CryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

class VaultRepository(
    private val dao: VaultDao,
    private val crypto: CryptoManager
) {
    fun observeEntries(): Flow<List<VaultEntry>> = dao.observeAll()

    fun searchEntries(query: String): Flow<List<VaultEntry>> {
        return if (query.isBlank()) {
            flowOf(emptyList()) // Do NOT load all entries into RAM when search query is blank!
        } else {
            dao.searchByTitle(query.trim())
        }
    }

    fun observeNotes(query: String = ""): Flow<List<VaultEntry>> {
        return if (query.isBlank()) {
            dao.searchNotes("")
        } else {
            dao.searchNotes(query.trim())
        }
    }

    suspend fun add(title: String, username: String?, password: CharArray, note: String? = null) {
        require(title.isNotBlank()) { "Inserisci un nome" }
        require(password.isNotEmpty()) { "La password non può essere vuota" }

        val passwordBytes = encodeChars(password)
        val usernameBytes = username?.toByteArray(StandardCharsets.UTF_8)
        try {
            val encryptedPassword = crypto.encrypt(passwordBytes, aad("password"))
            val encryptedUsername = usernameBytes?.let { crypto.encrypt(it, aad("username")) }
            val now = System.currentTimeMillis()
            dao.upsert(
                VaultEntry(
                    id = UUID.randomUUID().toString(),
                    title = title.trim(),
                    usernameNonce = encryptedUsername?.nonce,
                    encryptedUsername = encryptedUsername?.ciphertext,
                    passwordNonce = encryptedPassword.nonce,
                    encryptedPassword = encryptedPassword.ciphertext,
                    note = note?.trim()?.ifBlank { null },
                    createdAt = now,
                    updatedAt = now
                )
            )
        } finally {
            passwordBytes.fill(0)
            usernameBytes?.fill(0)
            password.fill('\u0000')
        }
    }

    suspend fun update(
        id: String,
        title: String,
        username: String?,
        password: CharArray?,
        note: String?
    ) {
        require(title.isNotBlank()) { "Inserisci un nome" }

        val existing = dao.getById(id) ?: return
        val now = System.currentTimeMillis()

        var passwordNonce = existing.passwordNonce
        var encryptedPassword = existing.encryptedPassword

        if (password != null && password.isNotEmpty()) {
            val passwordBytes = encodeChars(password)
            try {
                val encPass = crypto.encrypt(passwordBytes, aad("password"))
                passwordNonce = encPass.nonce
                encryptedPassword = encPass.ciphertext
            } finally {
                passwordBytes.fill(0)
                password.fill('\u0000')
            }
        }

        val usernameBytes = username?.toByteArray(StandardCharsets.UTF_8)
        val encryptedUsername = usernameBytes?.let { crypto.encrypt(it, aad("username")) }
        usernameBytes?.fill(0)

        dao.upsert(
            VaultEntry(
                id = id,
                title = title.trim(),
                usernameNonce = encryptedUsername?.nonce ?: existing.usernameNonce,
                encryptedUsername = encryptedUsername?.ciphertext ?: existing.encryptedUsername,
                passwordNonce = passwordNonce,
                encryptedPassword = encryptedPassword,
                note = note?.trim()?.ifBlank { null },
                createdAt = existing.createdAt,
                updatedAt = now
            )
        )
    }

    suspend fun decryptPassword(entry: VaultEntry): CharArray {
        val bytes = crypto.decrypt(
            entry.passwordNonce,
            entry.encryptedPassword,
            aad("password")
        )
        return try {
            String(bytes, StandardCharsets.UTF_8).toCharArray()
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun decryptUsername(entry: VaultEntry): String? {
        val nonce = entry.usernameNonce ?: return null
        val encrypted = entry.encryptedUsername ?: return null
        val bytes = crypto.decrypt(
            nonce,
            encrypted,
            aad("username")
        )
        return try {
            String(bytes, StandardCharsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun exportVaultJson(): String {
        val entries = dao.getAllEntries()
        val jsonArray = JSONArray()
        for (entry in entries) {
            val obj = JSONObject()
            obj.put("title", entry.title)
            val username = decryptUsername(entry)
            if (username != null) {
                obj.put("username", username)
            }
            val secretChars = decryptPassword(entry)
            val passString = String(secretChars)
            secretChars.fill('\u0000')
            obj.put("password", passString)
            if (entry.note != null) {
                obj.put("note", entry.note)
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString(2)
    }

    suspend fun importVaultJson(jsonString: String): Int {
        val jsonArray = JSONArray(jsonString)
        val entriesToInsert = mutableListOf<VaultEntry>()
        val now = System.currentTimeMillis()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val title = obj.optString("title").trim()
            val username = obj.optString("username").ifBlank { null }
            val passwordStr = obj.optString("password")
            val note = obj.optString("note").ifBlank { null }

            if (title.isNotBlank() && passwordStr.isNotEmpty()) {
                val passwordBytes = encodeChars(passwordStr.toCharArray())
                val usernameBytes = username?.toByteArray(StandardCharsets.UTF_8)
                try {
                    val encryptedPassword = crypto.encrypt(passwordBytes, aad("password"))
                    val encryptedUsername = usernameBytes?.let { crypto.encrypt(it, aad("username")) }
                    entriesToInsert.add(
                        VaultEntry(
                            id = UUID.randomUUID().toString(),
                            title = title,
                            usernameNonce = encryptedUsername?.nonce,
                            encryptedUsername = encryptedUsername?.ciphertext,
                            passwordNonce = encryptedPassword.nonce,
                            encryptedPassword = encryptedPassword.ciphertext,
                            note = note,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                } finally {
                    passwordBytes.fill(0)
                    usernameBytes?.fill(0)
                }
            }
        }

        if (entriesToInsert.isNotEmpty()) {
            dao.upsertAll(entriesToInsert)
        }
        return entriesToInsert.size
    }

    suspend fun delete(id: String) = dao.deleteById(id)

    private fun aad(field: String) = "keybridge:v1:$field".toByteArray(StandardCharsets.UTF_8)

    private fun encodeChars(chars: CharArray): ByteArray {
        val buffer = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
}
