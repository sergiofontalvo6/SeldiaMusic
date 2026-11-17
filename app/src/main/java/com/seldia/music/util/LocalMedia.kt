package com.seldia.music.util

import android.content.Context
import android.provider.MediaStore
import androidx.core.net.toUri
import java.io.File

/** Utilidades para archivos multimedia locales/descargados. */
object LocalMedia {

    /**
     * Indica si un URI o ruta local sigue siendo accesible. Soporta `content://`,
     * `file://` y rutas absolutas. Devuelve `false` para valores vacíos o inaccesibles.
     *
     * Para URIs de contenido se intenta resolver la ruta física (`_data`) y abrir el
     * archivo; si el archivo fue borrado del almacenamiento, se considera inexistente
     * aunque quede algún registro obsoleto.
     */
    fun exists(context: Context, uriString: String?): Boolean {
        val raw = uriString?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            val uri = raw.toUri()
            when (uri.scheme) {
                "content" -> contentExists(context, raw)
                "file" -> uri.path?.let { File(it).exists() } == true
                null, "" -> File(raw).exists()
                else -> File(raw).exists()
            }
        }.getOrDefault(false)
    }

    private fun contentExists(context: Context, raw: String): Boolean {
        val uri = raw.toUri()
        // 1) Si MediaStore expone la ruta real, la ruta física es la fuente de verdad.
        //    Si el archivo fue borrado del almacenamiento, se considera inexistente
        //    aunque el proveedor mantenga una entrada obsoleta.
        val dataPath = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        if (!dataPath.isNullOrBlank()) {
            return File(dataPath).exists()
        }

        // 2) Sin ruta física resoluble: exigir lectura real de contenido.
        val canRead = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(1)
                input.read(buf) > 0
            } ?: false
        }.getOrDefault(false)
        if (canRead) return true

        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length > 0L
            } ?: false
        }.getOrDefault(false)
    }
}
