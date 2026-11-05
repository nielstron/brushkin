package io.github.nielstron.brushkin.store

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class PurchaseVerifierTest {
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(key.public.encoded)
    private val receipt = """{"productId":"remove_ads","purchaseState":0,"purchaseToken":"test"}"""
    private fun sign(value: String) = Signature.getInstance("SHA1withRSA").run {
        initSign(key.private)
        update(value.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(sign())
    }

    @Test fun `authentic purchase receipt verifies`() {
        assertTrue(verifyPurchase(publicKey, receipt, sign(receipt)))
    }

    @Test fun `altering the product or purchase status invalidates a receipt`() {
        val signature = sign(receipt)
        assertFalse(verifyPurchase(publicKey, receipt.replace("remove_ads", "other_product"), signature))
        assertFalse(verifyPurchase(publicKey, receipt.replace("\"purchaseState\":0", "\"purchaseState\":2"), signature))
    }

    @Test fun `another signing key cannot grant the purchase`() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertFalse(verifyPurchase(Base64.getEncoder().encodeToString(other.public.encoded), receipt, sign(receipt)))
    }

    @Test fun `missing and malformed credentials never grant purchases`() {
        assertFalse(verifyPurchase("", receipt, sign(receipt)))
        assertFalse(verifyPurchase(publicKey, receipt, "not a signature!"))
        assertFalse(verifyPurchase(publicKey, receipt, ""))
    }
}
