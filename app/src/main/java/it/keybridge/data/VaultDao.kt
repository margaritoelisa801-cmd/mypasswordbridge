package it.keybridge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_entries ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<VaultEntry>>

    @Query("SELECT * FROM vault_entries ORDER BY title COLLATE NOCASE")
    suspend fun getAllEntries(): List<VaultEntry>

    @Query("SELECT * FROM vault_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): VaultEntry?

    @Query("SELECT * FROM vault_entries WHERE title LIKE '%' || :query || '%' OR (note IS NOT NULL AND note LIKE '%' || :query || '%') ORDER BY title COLLATE NOCASE LIMIT 50")
    fun searchByTitle(query: String): Flow<List<VaultEntry>>

    @Query("SELECT * FROM vault_entries WHERE note IS NOT NULL AND note != '' AND (note LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%') ORDER BY title COLLATE NOCASE LIMIT 50")
    fun searchNotes(query: String): Flow<List<VaultEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: VaultEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<VaultEntry>)

    @Query("DELETE FROM vault_entries WHERE id = :id")
    suspend fun deleteById(id: String)
}
