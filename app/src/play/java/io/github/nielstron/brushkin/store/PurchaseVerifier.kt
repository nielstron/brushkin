package io.github.nielstron.brushkin.store

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Play signs the original purchase JSON. Never grant an entitlement from an unsigned response. */
fun verifyPurchase(publicKey: String, json: String, signature: String): Boolean = try {
    val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
    Signature.getInstance("SHA1withRSA").run {
        initVerify(key)
        update(json.toByteArray(Charsets.UTF_8))
        verify(Base64.getDecoder().decode(signature))
    }
} catch (_: java.security.GeneralSecurityException) {
    false
} catch (_: IllegalArgumentException) {
    false
}
