package it.keybridge.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vault_entries")
data class VaultEntry(
    @PrimaryKey val id: String,
    val title: String,
    val usernameNonce: ByteArray?,
    val encryptedUsername: ByteArray?,
    val passwordNonce: ByteArray,
    val encryptedPassword: ByteArray,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)
