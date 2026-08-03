package io.github.micferna.resonate.source

import androidx.media3.datasource.DataSource
import io.github.micferna.resonate.data.db.entity.SourceKind

/**
 * Ce qu'un protocole distant doit savoir faire pour alimenter la bibliothèque.
 *
 * Trois responsabilités seulement : se tester, s'énumérer, et se laisser lire à
 * accès aléatoire. Tout le reste — cache, hors-ligne, file de lecture, appréciations —
 * est commun à tous les protocoles et vit au-dessus de cette interface.
 */
interface SourceConnector {

    val kind: SourceKind

    /** Vérifie identifiants et joignabilité, sans rien indexer. */
    suspend fun probe(source: ResolvedSource): ProbeResult

    /**
     * Parcourt la source et remet les fichiers audio par lots.
     *
     * Le découpage en lots permet à l'indexeur d'écrire au fil de l'eau : sur une
     * bibliothèque volumineuse, l'utilisateur voit sa musique apparaître pendant
     * le balayage au lieu d'attendre la fin.
     *
     * @param onBatch invoqué pour chaque lot ; peut suspendre le parcours.
     */
    suspend fun index(source: ResolvedSource, onBatch: suspend (List<RemoteAudioFile>) -> Unit)

    /**
     * Crée un [DataSource] Media3 lisant cette source en accès aléatoire.
     *
     * Appelé depuis les threads de lecture et de téléchargement de Media3 : les
     * opérations bloquantes y sont attendues, et chaque appel doit rendre une
     * instance indépendante, plusieurs lectures pouvant progresser en parallèle.
     */
    fun createDataSource(source: ResolvedSource): DataSource

    /** Libère les connexions maintenues pour cette source (suppression, modification). */
    fun invalidate(sourceId: Long)
}

/** Taille des lots remis par [SourceConnector.index]. */
const val INDEX_BATCH_SIZE = 256

/** Profondeur maximale d'exploration, garde-fou contre les boucles de liens symboliques. */
const val MAX_SCAN_DEPTH = 12
