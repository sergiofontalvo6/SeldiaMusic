package com.seldia.music.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: String): TrackEntity?

    @Query("UPDATE tracks SET isFavorite = :fav, addedAt = CASE WHEN :fav = 1 THEN :now ELSE addedAt END WHERE id = :id")
    suspend fun setFavorite(id: String, fav: Boolean, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM tracks WHERE uri IS NOT NULL AND uri != ''")
    suspend fun getWithUri(): List<TrackEntity>

    @Query("SELECT id FROM tracks WHERE uri IS NOT NULL AND uri != ''")
    fun observeDownloadedTrackKeys(): LiveData<List<String>>

    @Query("SELECT * FROM tracks WHERE uri = :uri")
    suspend fun getByUri(uri: String): List<TrackEntity>

    @Query("SELECT id FROM tracks WHERE uri = :uri")
    suspend fun getIdsByUri(uri: String): List<String>

    @Query("DELETE FROM tracks WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("UPDATE tracks SET uri = :uri WHERE id = :id")
    suspend fun updateUri(id: String, uri: String)

    @Query("SELECT isFavorite FROM tracks WHERE id = :id")
    suspend fun isFavorite(id: String): Boolean?

    @Query("SELECT * FROM tracks WHERE isFavorite = 1 ORDER BY addedAt DESC")
    fun observeFavorites(): LiveData<List<TrackEntity>>

    @Query("SELECT * FROM tracks")
    suspend fun getAll(): List<TrackEntity>

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()

    @Delete
    suspend fun delete(track: TrackEntity)
}

data class PlaylistWithCount(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val sortOrder: Int,
    val trackCount: Int
)

@Dao
interface PlaylistDao {
    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    /**
     * Playlists en orden manual (drag & drop) persistido en `sortOrder`.
     * Desempate por fecha de creación (más recientes primero).
     */
    @Query("SELECT p.id AS id, p.name AS name, p.createdAt AS createdAt, p.sortOrder AS sortOrder, COUNT(DISTINCT pt.trackId) AS trackCount FROM playlists p LEFT JOIN playlist_tracks pt ON p.id = pt.playlistId GROUP BY p.id ORDER BY p.sortOrder ASC, p.createdAt DESC")
    fun observePlaylists(): LiveData<List<PlaylistWithCount>>

    @Query("SELECT p.id AS id, p.name AS name, p.createdAt AS createdAt, p.sortOrder AS sortOrder, COUNT(DISTINCT pt.trackId) AS trackCount FROM playlists p LEFT JOIN playlist_tracks pt ON p.id = pt.playlistId GROUP BY p.id ORDER BY p.sortOrder ASC, p.createdAt DESC")
    suspend fun getAllPlaylists(): List<PlaylistWithCount>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM playlists")
    suspend fun maxSortOrder(): Int

    @Query("UPDATE playlists SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Query("UPDATE playlist_tracks SET position = :position WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun updateTrackPosition(playlistId: Long, trackId: String, position: Int)

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylist(id: Long): PlaylistEntity?

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearPlaylistTracks(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTrack(ref: PlaylistTrackCrossRef)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrack(playlistId: Long, trackId: String)

    @Query("DELETE FROM playlist_tracks WHERE trackId IN (:trackIds)")
    suspend fun deleteRefs(trackIds: List<String>)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Transaction
    @Query("SELECT * FROM tracks INNER JOIN playlist_tracks ON tracks.id = playlist_tracks.trackId WHERE playlist_tracks.playlistId = :playlistId ORDER BY playlist_tracks.position ASC")
    fun observePlaylistTracks(playlistId: Long): LiveData<List<TrackEntity>>

    @Query("SELECT * FROM tracks INNER JOIN playlist_tracks ON tracks.id = playlist_tracks.trackId WHERE playlist_tracks.playlistId = :playlistId ORDER BY playlist_tracks.position ASC")
    suspend fun getPlaylistTracksOnce(playlistId: Long): List<TrackEntity>

    @Query("SELECT * FROM playlist_tracks")
    suspend fun getAllRefs(): List<PlaylistTrackCrossRef>

    @Query("DELETE FROM playlists")
    suspend fun deleteAllPlaylists()

    @Query("DELETE FROM playlist_tracks")
    suspend fun deleteAllRefs()
}

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntity)

    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): LiveData<List<HistoryEntity>>

    @Query("SELECT * FROM history ORDER BY playedAt DESC")
    suspend fun getAll(): List<HistoryEntity>

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("DELETE FROM history WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("UPDATE history SET uri = '' WHERE uri = :uri")
    suspend fun clearUri(uri: String)
}
