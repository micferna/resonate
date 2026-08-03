package io.github.micferna.resonate.di

import javax.inject.Qualifier

/** Répartiteur pour les E/S bloquantes : réseau, disque, base de données. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Portée coroutine vivant aussi longtemps que le processus.
 *
 * Réservée aux travaux qui doivent survivre à l'écran qui les a déclenchés :
 * mémorisation d'une clé d'hôte, mise à jour d'un compteur de lecture, réconciliation
 * d'un téléchargement terminé.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
