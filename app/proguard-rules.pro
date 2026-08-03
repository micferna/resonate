# =============================================================================
# Règles R8 pour la compilation `release`.
#
# Room, Hilt, Media3, Coil et kotlinx-serialization embarquent leurs propres
# règles : rien à ajouter pour eux. Ce fichier ne traite que les bibliothèques
# réseau bas niveau, qui reposent massivement sur la réflexion et les fichiers
# de service — deux mécanismes que R8 ne sait pas suivre.
#
# Sans ces règles, l'APK release compile sans erreur puis échoue à l'exécution,
# au moment précis d'ouvrir une connexion. C'est le genre de panne qui ne se voit
# qu'en production.
# =============================================================================

# --- SSH / SFTP (sshj) ------------------------------------------------------
# sshj instancie ses algorithmes de chiffrement, d'échange de clés et de MAC par
# nom, à travers des fabriques déclarées dans DefaultConfig. Rien n'y est
# référencé statiquement : tout serait supprimé.
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.sshj.** { *; }
-dontwarn net.schmizz.sshj.**
-dontwarn com.hierynomus.sshj.**

# Ed25519 est fourni par BouncyCastle, déjà conservé plus bas.

# --- SMB (SMBJ) -------------------------------------------------------------
-keep class com.hierynomus.smbj.** { *; }
-keep class com.hierynomus.mssmb2.** { *; }
-keep class com.hierynomus.msdtyp.** { *; }
-keep class com.hierynomus.msfscc.** { *; }
-keep class com.hierynomus.mserref.** { *; }
-keep class com.hierynomus.ntlm.** { *; }
-keep class com.hierynomus.spnego.** { *; }
-keep class com.hierynomus.asn1.** { *; }
-dontwarn com.hierynomus.**

# SMBJ distribue ses événements via mbassador, qui repère les gestionnaires par
# annotation au moment de l'exécution.
-keep class net.engio.mbassy.** { *; }
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler <methods>;
}
-dontwarn net.engio.mbassy.**

# --- BouncyCastle -----------------------------------------------------------
# Le fournisseur JCE s'enregistre par réflexion, algorithme par algorithme.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- Journalisation ---------------------------------------------------------
# sshj et SMBJ écrivent via SLF4J. Aucune implémentation n'est embarquée : le
# fournisseur nul intégré à SLF4J 2 prend le relais, ces avertissements de
# classes absentes sont donc attendus.
-dontwarn org.slf4j.**
-dontwarn org.apache.log4j.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**

# --- OkHttp / Okio ----------------------------------------------------------
# Références optionnelles à Conscrypt et aux APIs Java absentes sur Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# --- Modèles sérialisés -----------------------------------------------------
# Les réponses Subsonic et GitHub sont désérialisées par nom de champ ; renommer
# ces propriétés couperait la correspondance avec le JSON.
-keepclassmembers class io.github.micferna.resonate.source.subsonic.** {
    <fields>;
}
-keepclassmembers class io.github.micferna.resonate.update.GitHubRelease { <fields>; }
-keepclassmembers class io.github.micferna.resonate.update.GitHubAsset { <fields>; }

# --- Diagnostic -------------------------------------------------------------
# Conserve les numéros de ligne pour que les rapports de plantage restent
# lisibles, tout en masquant les noms de fichiers d'origine.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
