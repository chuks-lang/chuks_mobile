// Secure storage for Chuks Mobile, the Android half. AES-GCM with a key that never
// leaves the AndroidKeyStore; the ciphertext in a private SharedPreferences. A plain
// value uses a key anyone in the app can use; a protected value uses a second key
// that the keystore will only unlock behind a fresh biometric (or device-credential)
// authentication, bound cryptographically through a BiometricPrompt CryptoObject.
//
// The traps:
//
//   1. A per-use auth key (validity 0 / -1) can only be used inside a BiometricPrompt
//      that carries the very Cipher being used, as a CryptoObject. Encrypting to store
//      and decrypting to read both go through the prompt; there is no "unlock once".
//   2. The IV must travel with the ciphertext. For encryption the keystore generates
//      it (a caller IV is refused), so it is read off the cipher AFTER init and stored;
//      for decryption it is fed back as a GCMParameterSpec.
//   3. A stored blob is tagged "0|" (plain) or "1|" (protected) so get knows whether to
//      prompt without a second lookup. Base64's alphabet has no "|", so the tag is
//      unambiguous.
//   4. BiometricPrompt and its callbacks run on the main thread; the crypto around them
//      is cheap, so it all stays there.
package com.chuks.app

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ChuksSecure(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("chuks_secure", Activity.MODE_PRIVATE)

    private fun key(name: String, auth: Boolean): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null)
        (ks.getEntry(name, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val b = KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        if (auth) {
            b.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            else @Suppress("DEPRECATION") b.setUserAuthenticationValidityDurationSeconds(-1)
        }
        kg.init(b.build()); return kg.generateKey()
    }

    fun setPlain(k: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key("chuks_secure", false))
        prefs.edit().putString(k, "0|" + Base64.encodeToString(c.iv + c.doFinal(value.toByteArray()), Base64.NO_WRAP)).apply()
    }

    /** Store behind the gate: the prompt carries the encrypt cipher (traps 1, 2). */
    fun setProtected(k: String, value: String, reason: String, ok: () -> Unit, err: (String) -> Unit) {
        if (available() != BiometricManager.BIOMETRIC_SUCCESS) { err("no biometrics enrolled"); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        try { c.init(Cipher.ENCRYPT_MODE, key("chuks_secure_auth", true)) } catch (e: Exception) { err("cannot use the keystore: ${e.message}"); return }
        prompt(reason, c, err) { cipher ->
            val blob = cipher.iv + cipher.doFinal(value.toByteArray())
            prefs.edit().putString(k, "1|" + Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
            ok()
        }
    }

    /** ok(value), miss(), or err(message). A protected value prompts (trap 1). */
    fun get(k: String, reason: String, ok: (String) -> Unit, miss: () -> Unit, err: (String) -> Unit) {
        val stored = prefs.getString(k, null) ?: return miss()
        val protectedItem = stored.startsWith("1|")
        val blob = Base64.decode(stored.substring(if (stored.length > 1 && stored[1] == '|') 2 else 0), Base64.NO_WRAP)
        val iv = blob.copyOfRange(0, 12); val ct = blob.copyOfRange(12, blob.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        val kName = if (protectedItem) "chuks_secure_auth" else "chuks_secure"
        try { c.init(Cipher.DECRYPT_MODE, key(kName, protectedItem), GCMParameterSpec(128, iv)) }
        catch (e: Exception) { err("cannot read $k: ${e.message}"); return }
        if (protectedItem) prompt(reason, c, err) { cipher -> ok(String(cipher.doFinal(ct))) }
        else try { ok(String(c.doFinal(ct))) } catch (e: Exception) { err("cannot read $k: ${e.message}") }
    }

    fun has(k: String) = prefs.contains(k)
    fun keys(): List<String> = prefs.all.keys.sorted().map { ChuksWire.esc(it) }
    fun delete(k: String) = prefs.edit().remove(k).apply()
    fun deleteAll() = prefs.edit().clear().apply()

    fun available(): Int {
        if (Build.VERSION.SDK_INT < 29) return BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        val bm = activity.getSystemService(BiometricManager::class.java)
        val kinds = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return bm.canAuthenticate(kinds)
    }
    fun availableBool() = available() == BiometricManager.BIOMETRIC_SUCCESS

    private fun prompt(reason: String, cipher: Cipher, err: (String) -> Unit, use: (Cipher) -> Unit) {
        val builder = BiometricPrompt.Builder(activity)
            .setTitle("Authenticate")
            .setSubtitle(if (reason.isEmpty()) "Confirm your identity" else reason)
        if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        else @Suppress("DEPRECATION") builder.setNegativeButton("Cancel", activity.mainExecutor) { _, _ -> }
        builder.build().authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (c == null) { activity.runOnUiThread { err("no crypto object") }; return }
                    activity.runOnUiThread { try { use(c) } catch (e: Exception) { err("cannot use the key: ${e.message}") } }
                }
                override fun onAuthenticationError(code: Int, s: CharSequence) { activity.runOnUiThread { err(s.toString()) } }
                override fun onAuthenticationFailed() {}
            })
    }
}
