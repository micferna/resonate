package io.github.micferna.resonate.ui

import java.util.Locale
import java.util.concurrent.TimeUnit

/** `3:07`, ou `1:04:22` au-delà de l'heure. */
fun formatDuration(millis: Long): String {
    if (millis <= 0) return "--:--"
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

/** Durée cumulée en langage courant : « 4 h 12 min », « 38 min ». */
fun formatTotalDuration(millis: Long): String {
    if (millis <= 0) return "0 min"
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millis)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "$hours h $minutes min"
        hours > 0 -> "$hours h"
        else -> "$minutes min"
    }
}

/** Tailles en unités binaires, comme les affiche le système de fichiers. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes o"
    val units = listOf("Ko", "Mo", "Go", "To")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
}

/** « il y a 3 min », « il y a 2 j », « jamais ». */
fun formatRelativeTime(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0) return "jamais"
    val elapsed = System.currentTimeMillis() - epochMillis
    if (elapsed < 0) return "à l'instant"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    val days = TimeUnit.MILLISECONDS.toDays(elapsed)
    return when {
        minutes < 1 -> "à l'instant"
        minutes < 60 -> "il y a $minutes min"
        hours < 24 -> "il y a $hours h"
        days < 30 -> "il y a $days j"
        else -> "il y a ${days / 30} mois"
    }
}

/** « 1 morceau » / « 12 morceaux ». */
fun pluralize(count: Int, singular: String, plural: String = "${singular}s"): String =
    "$count ${if (count <= 1) singular else plural}"
