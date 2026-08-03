package io.github.micferna.resonate.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scelle les mots de passe et clés privées des sources distantes.
 *
 * La clé de chiffrement est générée dans `AndroidKeyStore` et n'est jamais exportable :
 * même avec un accès complet au fichier de base de données (sauvegarde extraite, appareil
 * rooté, `adb backup`), les secrets restent illisibles hors de cet appareil.
 *
 * Format produit : `[1 octet de version][12 octets d'IV][texte chiffré || tag GCM 16 octets]`.
 * L'IV est tiré par le fournisseur pour chaque chiffrement — le réutiliser avec GCM
 * casserait la confidentialité, il ne faut donc jamais l'imposer.
 */
@Singleton
class CredentialCipher @Inject constructor() {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(PROVIDER).apply { load(null) }
    }

    /** Chiffre [plaintext]. Renvoie `null` si l'entrée est `null`, pour un accès anonyme. */
    fun seal(plaintext: String?): ByteArray? {
        if (plaintext == null) return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, orCreateKey())
        }
        val iv = cipher.iv
        require(iv.size == IV_LENGTH) { "IV GCM inattendu : ${iv.size} octets" }
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return ByteArray(1 + IV_LENGTH + body.size).also { out ->
            out[0] = FORMAT_VERSION
            iv.copyInto(out, destinationOffset = 1)
            body.copyInto(out, destinationOffset = 1 + IV_LENGTH)
        }
    }

    /**
     * Déchiffre une valeur produite par [seal].
     *
     * Renvoie `null` si le secret est irrécupérable — typiquement après une
     * restauration sur un autre appareil, ou si l'utilisateur a réinitialisé son
     * verrouillage d'écran, ce qui invalide les clés du KeyStore. L'appelant doit
     * alors redemander l'identifiant plutôt que de faire échouer l'app.
     */
    fun open(sealed: ByteArray?): String? {
        if (sealed == null || sealed.size <= 1 + IV_LENGTH) return null
        if (sealed[0] != FORMAT_VERSION) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    existingKey() ?: return null,
                    GCMParameterSpec(TAG_LENGTH_BITS, sealed, 1, IV_LENGTH),
                )
            }
            val plain = cipher.doFinal(sealed, 1 + IV_LENGTH, sealed.size - 1 - IV_LENGTH)
            String(plain, Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun existingKey(): SecretKey? =
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun orCreateKey(): SecretKey = existingKey() ?: generateKey()

    private fun generateKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    // Un service de lecture doit pouvoir se reconnecter à un partage
                    // écran verrouillé : exiger une authentification utilisateur ici
                    // couperait la musique au verrouillage.
                    .setUserAuthenticationRequired(false)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "resonate.source-credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val TAG_LENGTH_BITS = 128
        const val IV_LENGTH = 12
        const val FORMAT_VERSION: Byte = 1
    }
}
