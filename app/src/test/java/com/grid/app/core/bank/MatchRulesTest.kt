package com.grid.app.core.bank

import com.google.common.truth.Truth.assertThat
import com.grid.app.core.model.BankTxKind
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import org.junit.Test

class MatchRulesTest {

    private val day = 86_400_000L
    private val t0 = 1_790_000_000_000L

    private fun bank(amount: Long, merchant: String?, type: TxType = TxType.EXPENSE, kind: BankTxKind = BankTxKind.CARD_SPEND, at: Long = t0) =
        BankFacts(type, kind, amount, at, merchant)

    private fun cand(id: Long, source: TxSource, amount: Long, merchant: String?, at: Long = t0) = Candidate(id, source, amount, at, merchant)

    @Test fun captureOfSamePaymentMatches() {
        val hit = MatchRules.bestMatch(bank(450, "Starbucks"), listOf(cand(1, TxSource.CAPTURE, 450, "STARBUCKS")))
        assertThat(hit?.id).isEqualTo(1)
    }

    @Test fun differentMerchantDoesNotMatch() {
        assertThat(MatchRules.bestMatch(bank(450, "Starbucks"), listOf(cand(1, TxSource.CAPTURE, 450, "Lidl")))).isNull()
    }

    @Test fun captureWithoutMerchantMatchesOnAmount() {
        assertThat(MatchRules.bestMatch(bank(450, "Starbucks"), listOf(cand(1, TxSource.MANUAL, 450, null)))?.id).isEqualTo(1)
    }

    @Test fun nearestOfTwoIdenticalCoffeesIsPicked() {
        val hit = MatchRules.bestMatch(
            bank(350, "Coffee", at = t0),
            listOf(cand(1, TxSource.CAPTURE, 350, "Coffee", at = t0 - 2 * day), cand(2, TxSource.CAPTURE, 350, "Coffee", at = t0 - 3_600_000)),
        )
        assertThat(hit?.id).isEqualTo(2)
    }

    @Test fun captureTooOldDoesNotMatch() {
        assertThat(MatchRules.bestMatch(bank(450, "Starbucks"), listOf(cand(1, TxSource.CAPTURE, 450, "Starbucks", at = t0 - 6 * day)))).isNull()
        // Captured after the bank date by more than a day: not the same payment.
        assertThat(MatchRules.bestMatch(bank(450, "Starbucks"), listOf(cand(1, TxSource.CAPTURE, 450, "Starbucks", at = t0 + 2 * day)))).isNull()
    }

    @Test fun tipsAndFxWithinFivePercentMatchCaptures() {
        assertThat(MatchRules.bestMatch(bank(1050, "Le Comptoir"), listOf(cand(1, TxSource.CAPTURE, 1000, "Le Comptoir")))?.id).isEqualTo(1)
        assertThat(MatchRules.bestMatch(bank(1100, "Le Comptoir"), listOf(cand(1, TxSource.CAPTURE, 1000, "Le Comptoir")))).isNull()
    }

    @Test fun subscriptionPriceChangeWithinTenPercentMatches() {
        assertThat(MatchRules.bestMatch(bank(1399, "Netflix"), listOf(cand(1, TxSource.SUBSCRIPTION, 1349, "Netflix", at = t0 - 2 * day)))?.id).isEqualTo(1)
    }

    @Test fun rentMatchesItsBillOnAmountAlone() {
        val rent = cand(1, TxSource.SUBSCRIPTION, 85_000, "Rent", at = t0 + day)
        assertThat(MatchRules.bestMatch(bank(85_000, "J. Dupont", kind = BankTxKind.TRANSFER_OUT), listOf(rent))?.id).isEqualTo(1)
        assertThat(MatchRules.bestMatch(bank(90_000, "J. Dupont", kind = BankTxKind.TRANSFER_OUT), listOf(rent))).isNull()
    }

    @Test fun salaryMatchesTheCheckInWithinAWeek() {
        val checkIn = cand(1, TxSource.CHECKIN, 250_000, "Salary", at = t0 - 4 * day)
        assertThat(MatchRules.bestMatch(bank(246_300, "ACME SAS", type = TxType.INCOME, kind = BankTxKind.MONEY_IN), listOf(checkIn))?.id).isEqualTo(1)
    }

    @Test fun bankEntriesNeverMatch() {
        assertThat(MatchRules.bestMatch(bank(450, "Starbucks"), listOf(cand(1, TxSource.BANK, 450, "Starbucks")))).isNull()
    }

    @Test fun similarityNeedsAMeaningfulSharedWord() {
        assertThat(MatchRules.similar("Uber Eats", "UBER *EATS PENDING")).isTrue()
        assertThat(MatchRules.similar("Le Comptoir", "Le Bistrot")).isFalse() // "le" is too short to count
        assertThat(MatchRules.similar(null, "Anything")).isTrue()
    }
}
