package io.github.micferna.resonate.core.util

import java.util.Locale

/**
 * Métadonnées déduites d'un chemin de fichier.
 *
 * Lire les tags réels impose d'ouvrir chaque fichier sur le réseau ; sur une
 * bibliothèque de plusieurs milliers de titres derrière un lien SFTP, cela prend
 * de longues minutes. L'indexation retient donc d'abord ce que le chemin révèle —
 * `.../Artiste/Album/03 - Titre.flac` en dit déjà beaucoup — puis un travail de
 * fond remplace ces valeurs par les tags authentiques, morceau par morceau.
 */
data class PathMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val trackNumber: Int,
) {
    companion object {
        const val UNKNOWN_ARTIST = "Artiste inconnu"
        const val UNKNOWN_ALBUM = "Album inconnu"

        /** `01 - Titre`, `01. Titre`, `01 Titre`, `1-02 Titre`… */
        private val LEADING_TRACK_NUMBER =
            Regex("""^\s*(?:\d{1,2}[-_.]\s*)?(\d{1,3})\s*(?:[-–—.)_]\s*|\s+)(.+)$""")

        /** Dossiers qui ne désignent ni un artiste ni un album. */
        private val GENERIC_FOLDERS = setOf(
            "music", "musique", "musiques", "media", "audio", "songs", "tracks",
            "cd", "cd1", "cd2", "disc", "disc1", "disc2", "disk", "vol", "various",
            "downloads", "shared", "public", "home", "data", "files", "library",
        )

        /**
         * [remotePath] doit être un chemin complet, séparé par des `/`.
         * [relativeTo] est la racine configurée de la source : les dossiers situés
         * au-dessus n'apportent rien et fausseraient la déduction.
         */
        fun fromPath(remotePath: String, relativeTo: String = "/"): PathMetadata {
            val root = relativeTo.trim('/')
            val trimmed = remotePath.trim('/')
            val relative = when {
                root.isEmpty() -> trimmed
                trimmed.startsWith("$root/") -> trimmed.removePrefix("$root/")
                else -> trimmed
            }

            val segments = relative.split('/').filter { it.isNotBlank() }
            val fileName = segments.lastOrNull().orEmpty()
            val folders = segments.dropLast(1).filterNot { it.isMeaningless() }

            val (trackNumber, bareTitle) = splitTrackNumber(AudioFile.baseNameOf(fileName))

            // `.../Artiste/Album/piste` est de loin l'agencement le plus répandu.
            val album = folders.getOrNull(folders.lastIndex)?.cleaned() ?: UNKNOWN_ALBUM
            val artist = folders.getOrNull(folders.lastIndex - 1)?.cleaned() ?: UNKNOWN_ARTIST

            // `Artiste - Titre.mp3` dans un dossier fourre-tout : le nom du fichier
            // est alors plus fiable que l'arborescence.
            val dashed = bareTitle.split(" - ", limit = 2)
            return if (folders.isEmpty() && dashed.size == 2 && dashed[0].isNotBlank()) {
                PathMetadata(
                    title = dashed[1].trim().ifBlank { bareTitle },
                    artist = dashed[0].trim(),
                    album = UNKNOWN_ALBUM,
                    trackNumber = trackNumber,
                )
            } else {
                PathMetadata(
                    title = bareTitle.ifBlank { fileName },
                    artist = artist,
                    album = album,
                    trackNumber = trackNumber,
                )
            }
        }

        private fun splitTrackNumber(baseName: String): Pair<Int, String> {
            val match = LEADING_TRACK_NUMBER.matchEntire(baseName) ?: return 0 to baseName.trim()
            val number = match.groupValues[1].toIntOrNull() ?: 0
            val rest = match.groupValues[2].trim()
            return if (rest.isEmpty()) 0 to baseName.trim() else number to rest
        }

        private fun String.isMeaningless(): Boolean {
            val normalized = lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
            return normalized.isEmpty() || normalized in GENERIC_FOLDERS
        }

        private fun String.cleaned(): String = replace('_', ' ').trim().ifBlank { UNKNOWN_ALBUM }
    }
}

/** Clé de recherche : minuscules, accents conservés, champs séparés par des espaces. */
fun buildSearchKey(vararg parts: String): String =
    parts.filter { it.isNotBlank() }.joinToString(" ").lowercase(Locale.ROOT)
