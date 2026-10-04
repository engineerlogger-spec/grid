package com.grid.app.core.bank

import com.google.common.truth.Truth.assertThat
import com.grid.app.core.model.BankTxKind
import org.junit.Test

class TxClassifierTest {

    private val own = setOf("LT12 3250 0000 0000 0001")

    private fun out(name: String? = null, iban: String? = null, remittance: List<String> = emptyList(), code: String? = null) =
        RemoteTx(amount = "10.00", currency = "EUR", creditDebit = "DBIT", creditorName = name, creditorIban = iban, remittance = remittance, bankTxCode = code)

    private fun inn(name: String? = null, iban: String? = null, remittance: List<String> = emptyList()) =
        RemoteTx(amount = "10.00", currency = "EUR", creditDebit = "CRDT", debtorName = name, debtorIban = iban, remittance = remittance)

    private fun kind(tx: RemoteTx) = TxClassifier.classify(tx, own)

    @Test fun cardPayments() {
        assertThat(kind(out("Starbucks"))).isEqualTo(BankTxKind.CARD_SPEND)
        assertThat(kind(out("PAYPAL *NETFLIX"))).isEqualTo(BankTxKind.CARD_SPEND)
    }

    @Test fun movesBetweenOwnPocketsAreInternal() {
        assertThat(kind(out(remittance = listOf("To EUR Vault")))).isEqualTo(BankTxKind.INTERNAL)
        assertThat(kind(out(remittance = listOf("Exchanged to USD")))).isEqualTo(BankTxKind.INTERNAL)
        assertThat(kind(inn(remittance = listOf("Top-Up by *1234")))).isEqualTo(BankTxKind.INTERNAL)
        assertThat(kind(out(remittance = listOf("To pocket EUR Holiday")))).isEqualTo(BankTxKind.INTERNAL)
        assertThat(kind(out("Revolut", remittance = listOf("Stocks purchase")))).isEqualTo(BankTxKind.INTERNAL)
    }

    @Test fun transferToOwnIbanIsInternal() {
        assertThat(kind(out("Me", iban = "lt123250000000000001"))).isEqualTo(BankTxKind.INTERNAL)
    }

    @Test fun transfersToPeople() {
        assertThat(kind(out("J. Dupont", iban = "FR7630006000011234567890189", remittance = listOf("To J. Dupont")))).isEqualTo(BankTxKind.TRANSFER_OUT)
        assertThat(kind(out(remittance = listOf("To Sam Smith")))).isEqualTo(BankTxKind.TRANSFER_OUT)
        assertThat(kind(out("Sam", code = "ICDT"))).isEqualTo(BankTxKind.TRANSFER_OUT)
    }

    @Test fun directDebits() {
        assertThat(kind(out("EDF", code = "PMDD"))).isEqualTo(BankTxKind.DIRECT_DEBIT)
        assertThat(kind(out("Free Mobile", iban = "FR7612345", remittance = listOf("Prélèvement SEPA")))).isEqualTo(BankTxKind.DIRECT_DEBIT)
    }

    @Test fun moneyIn() {
        assertThat(kind(inn("ACME SAS", iban = "FR7699999"))).isEqualTo(BankTxKind.MONEY_IN)
        assertThat(kind(inn(remittance = listOf("Payment from Sam")))).isEqualTo(BankTxKind.MONEY_IN)
    }
}
