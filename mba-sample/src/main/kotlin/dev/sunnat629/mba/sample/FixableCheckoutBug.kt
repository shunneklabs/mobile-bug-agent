package dev.sunnat629.mba.sample

import dev.sunnat629.mba.core.MBA

/**
 * Intentional demo bug.
 *
 * The checkout flow assumes the payment SDK always returns a token. In the
 * failing state below, tokenization returns null and the app crashes before it
 * can show a recoverable error.
 */
object FixableCheckoutBug {
    fun trigger() {
        val checkout = CheckoutDraft(
            cartId = "cart-demo-42",
            totalCents = 4999,
            paymentToken = null,
        )

        submitCheckout(checkout)
    }

    private fun submitCheckout(checkout: CheckoutDraft): CheckoutReceipt? {
        val token = checkout.paymentToken?.trim()
        if (token.isNullOrEmpty()) {
            MBA.logError(
                IllegalStateException("Checkout payment token missing while charging ${checkout.totalCents} cents"),
                metadata = mapOf(
                    "sample.scenario" to "fixable-checkout-token",
                    "sample.cart_id" to checkout.cartId,
                    "sample.recoverable" to "true",
                ),
            )
            return null
        }

        return CheckoutReceipt(
            cartId = checkout.cartId,
            tokenPreview = token.takeLast(4),
            totalCents = checkout.totalCents,
        )
    }
}

private data class CheckoutDraft(
    val cartId: String,
    val totalCents: Int,
    val paymentToken: String?,
)

private data class CheckoutReceipt(
    val cartId: String,
    val tokenPreview: String,
    val totalCents: Int,
)
