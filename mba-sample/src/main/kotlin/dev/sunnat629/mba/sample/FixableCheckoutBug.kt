package dev.sunnat629.mba.sample

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

    private fun submitCheckout(checkout: CheckoutDraft): CheckoutReceipt {
        val token = checkout.paymentToken?.trim().orEmpty()

        // Demo-safe guardrail: never crash the sample app if tokenization fails.
        // In a real app this should surface a recoverable UI error state.
        if (token.isBlank()) {
            return CheckoutReceipt(
                cartId = checkout.cartId,
                tokenPreview = "(missing)",
                totalCents = checkout.totalCents,
            )
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
