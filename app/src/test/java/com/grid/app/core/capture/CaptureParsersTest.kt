package com.grid.app.core.capture

import com.google.common.truth.Truth.assertThat
import com.grid.app.core.model.CaptureDirection.IN
import com.grid.app.core.model.CaptureDirection.OUT
import com.grid.app.core.model.CaptureSource
import com.grid.app.core.model.CaptureSource.GOOGLE_WALLET
import com.grid.app.core.model.CaptureSource.PAYPAL
import com.grid.app.core.model.CaptureSource.REVOLUT
import org.junit.Test

/**
 * Sample corpus. Real notification wording varies by app version and language; failures in the wild
 * land in the diagnostics log so new samples can be added here.
 */
class CaptureParsersTest {

    private fun parse(source: CaptureSource, title: String, text: String, default: String = "EUR") =
        CaptureParsers.parse(source, title, text, default)

    private fun parsed(source: CaptureSource, title: String, text: String) = parse(source, title, text) as CaptureParse.Parsed

    // --- Google Wallet ---------------------------------------------------------------------------
    @Test fun walletTitleIsMerchant() {
        val p = parsed(GOOGLE_WALLET, "Starbucks", "€4.50 with Visa •••• 1234")
        assertThat(p.amount).isEqualTo(Money(450, "EUR"))
        assertThat(p.merchant).isEqualTo("Starbucks")
        assertThat(p.direction).isEqualTo(OUT)
    }

    @Test fun walletPaidPrefix() {
        val p = parsed(GOOGLE_WALLET, "Carrefour City", "Paid €23.10 with Mastercard ••5678")
        assertThat(p.amount.minor).isEqualTo(2310)
        assertThat(p.merchant).isEqualTo("Carrefour City")
    }

    @Test fun walletGenericTitleUsesAt() {
        assertThat(parsed(GOOGLE_WALLET, "Google Wallet", "You paid €12.00 at Monoprix").merchant).isEqualTo("Monoprix")
    }

    @Test fun walletRefundIsIncoming() {
        val p = parsed(GOOGLE_WALLET, "Refund from Zara", "€39.99 returned to Visa •••• 1234")
        assertThat(p.direction).isEqualTo(IN)
        assertThat(p.merchant).isEqualTo("Zara")
    }

    @Test fun walletNonPaymentIgnored() {
        assertThat(parse(GOOGLE_WALLET, "Pass added", "Your boarding pass was added")).isInstanceOf(CaptureParse.Ignored::class.java)
    }

    @Test fun declinedIgnored() {
        assertThat(parse(GOOGLE_WALLET, "Payment declined", "€12.00 at Fnac was declined")).isInstanceOf(CaptureParse.Ignored::class.java)
    }

    // --- PayPal ----------------------------------------------------------------------------------
    @Test fun paypalSent() {
        val p = parsed(PAYPAL, "Money sent", "You sent €20.00 EUR to John Smith")
        assertThat(p.amount).isEqualTo(Money(2000, "EUR"))
        assertThat(p.merchant).isEqualTo("John Smith")
        assertThat(p.direction).isEqualTo(OUT)
    }

    @Test fun paypalPaidMerchantTrimsPunctuation() {
        assertThat(parsed(PAYPAL, "Payment sent", "You paid €12.99 EUR to Spotify AB.").merchant).isEqualTo("Spotify AB")
    }

    @Test fun paypalSentYouIsIncoming() {
        val p = parsed(PAYPAL, "You've got money!", "Jane Doe sent you €50.00 EUR")
        assertThat(p.direction).isEqualTo(IN)
        assertThat(p.merchant).isEqualTo("Jane Doe")
    }

    @Test fun paypalReceivedFrom() {
        val p = parsed(PAYPAL, "Payment received", "You received €15.00 EUR from Sam.")
        assertThat(p.direction).isEqualTo(IN)
        assertThat(p.merchant).isEqualTo("Sam")
    }

    // --- Revolut ---------------------------------------------------------------------------------
    @Test fun revolutMerchantTitleWithEmoji() {
        val p = parsed(REVOLUT, "Starbucks ☕️", "€4.50")
        assertThat(p.merchant).isEqualTo("Starbucks")
        assertThat(p.amount.minor).isEqualTo(450)
    }

    @Test fun revolutPaidAt() {
        assertThat(parsed(REVOLUT, "Revolut", "Paid €4.50 at Starbucks").merchant).isEqualTo("Starbucks")
    }

    @Test fun revolutReceivedFrom() {
        val p = parsed(REVOLUT, "Revolut", "You received €20 from John")
        assertThat(p.direction).isEqualTo(IN)
        assertThat(p.amount.minor).isEqualTo(2000)
        assertThat(p.merchant).isEqualTo("John")
    }

    @Test fun revolutForeignCurrencyKept() {
        assertThat(parsed(REVOLUT, "Revolut", "Paid £3.40 at Tesco").amount).isEqualTo(Money(340, "GBP"))
    }

    @Test fun revolutInternalMovesIgnored() {
        assertThat(parse(REVOLUT, "Revolut", "You topped up €100 via Apple Pay")).isInstanceOf(CaptureParse.Ignored::class.java)
        assertThat(parse(REVOLUT, "Revolut", "You exchanged €100 to $108.52")).isInstanceOf(CaptureParse.Ignored::class.java)
        assertThat(parse(REVOLUT, "Revolut", "Card payment of €9.99 to Netflix was declined")).isInstanceOf(CaptureParse.Ignored::class.java)
    }

    @Test fun marketingWithoutAmountIgnored() {
        assertThat(parse(REVOLUT, "Revolut", "Your Premium plan renews tomorrow")).isInstanceOf(CaptureParse.Ignored::class.java)
    }

    @Test fun paymentWordingWithoutAmountIsUnparsed() {
        assertThat(parse(REVOLUT, "Revolut", "Payment at Uber")).isEqualTo(CaptureParse.Unparsed)
    }

    @Test fun merchantCleanup() {
        assertThat(CaptureParsers.cleanMerchant("  UBER *TRIP •••• 1234  ")).isEqualTo("UBER *TRIP")
        assertThat(CaptureParsers.cleanMerchant("Café de Flore 🥐!")).isEqualTo("Café de Flore")
        assertThat(CaptureParsers.cleanMerchant("")).isNull()
    }
}
