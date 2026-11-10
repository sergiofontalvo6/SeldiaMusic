package com.seldia.music.data.backup

import com.google.gson.annotations.SerializedName
import com.seldia.music.data.db.HistoryEntity
import com.seldia.music.data.db.PlaylistEntity
import com.seldia.music.data.db.PlaylistTrackCrossRef
import com.seldia.music.data.db.TrackEntity

/**
 * Formato JSON de copia de seguridad de Seldia Music.
 *
 * Los nombres de las claves se fijan con [SerializedName]: en el build release R8 ofusca
 * los campos de esta clase (a, b, c...) y, sin las anotaciones, las copias generadas por un
 * build no se pueden restaurar en otro. `alternate` permite leer además las copias antiguas
 * ya exportadas por builds release sin anotar.
 */
data class BackupData(
    @SerializedName("version")
    val version: Int = 1,
    @SerializedName("exportedAt")
    val exportedAt: Long = System.currentTimeMillis(),
    @SerializedName(value = "tracks", alternate = ["c"])
    val tracks: List<TrackEntity> = emptyList(),
    @SerializedName(value = "playlists", alternate = ["d"])
    val playlists: List<PlaylistEntity> = emptyList(),
    @SerializedName(value = "playlistTracks", alternate = ["e"])
    val playlistTracks: List<PlaylistTrackCrossRef> = emptyList(),
    @SerializedName(value = "history", alternate = ["f"])
    val history: List<HistoryEntity> = emptyList(),
    @SerializedName(value = "prefs", alternate = ["g"])
    val prefs: Map<String, String> = emptyMap()
)
