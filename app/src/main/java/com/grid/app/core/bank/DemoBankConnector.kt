package com.grid.app.core.bank

import com.grid.app.core.time.AppClock
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * A pretend Revolut with three months of realistic history, for debug builds: exercises connect, import,
 * review and de-duplication on an emulator without real bank credentials. Its "bank login" link points
 * straight back into Grid.
 */
class DemoBankConnector(private val clock: AppClock) : BankConnector {

    override suspend fun aspsps(country: String) = listOf(Aspsp("Revolut", country, TimeUnit.DAYS.toSeconds(180)))

    override suspend fun startAuth(aspsp: Aspsp, validUntil: Long, redirectUrl: String, state: String) =
        AuthStart("grid://bank-callback?code=demo&state=$state", "demo-auth")

    override suspend fun createSession(code: String) = BankSession(
        sessionId = "demo-session",
        validUntil = clock.millis() + TimeUnit.DAYS.toMillis(180),
        accounts = listOf(
            RemoteAccount("demo-eur", "demo-hash-eur", "EUR", "Sam Taylor", IBAN),
            RemoteAccount("demo-usd", "demo-hash-usd", "USD", "Dollars", null),
        ),
    )

    override suspend fun transactions(accountUid: String, dateFrom: LocalDate, continuationKey: String?): TxPage {
        if (accountUid != "demo-eur") return TxPage(emptyList(), null)
        val today = clock.today()
        val all = buildList {
            add(card("sbux", today, "4.50", "Starbucks", "5814"))
            add(card("uber", today, "12.00", "Uber", "4121", status = "PDNG"))
            listOf(2L, 9L, 16L).forEach { add(card("lidl", today.minusDays(it), "23.40", "LIDL 1234")) }
            add(own("vault", today.minusDays(5), "200.00", "To EUR Vault"))
            add(topUp("topup", today.minusDays(6), "100.00"))
            for (back in 0L..3L) {
                val month = today.minusMonths(back)
                add(card("netflix", month.withDayOfMonth(15), "13.49", "PAYPAL *NETFLIX"))
                add(directDebit("edf", month.withDayOfMonth(10), "62.00", "EDF"))
                add(transfer("rent", month.withDayOfMonth(1), "850.00", "J. Dupont", "FR7630006000011234567890189"))
                add(salary("salary", month.withDayOfMonth(28), "2500.00", "ACME SAS"))
                // The owner's own salary account: money moved in on the 29th, a little sent back on the 20th.
                add(salary("fromsalaryacct", month.withDayOfMonth(29), "1500.00", OWNER).copy(debtorIban = OWNER_IBAN))
                add(transfer("tosalaryacct", month.withDayOfMonth(20), "200.00", OWNER, OWNER_IBAN))
            }
        }
        return TxPage(all.filter { tx -> LocalDate.parse(tx.bookingDate).let { !it.isBefore(dateFrom) && !it.isAfter(today) } }, null)
    }

    override suspend fun deleteSession(sessionId: String) = Unit

    private fun card(tag: String, date: LocalDate, amount: String, merchant: String, mcc: String? = null, status: String = "BOOK") = RemoteTx(
        transactionId = "demo-$tag-$date", amount = amount, currency = "EUR", creditDebit = "DBIT", status = status,
        bookingDate = date.toString(), transactionDate = date.toString(), creditorName = merchant, mcc = mcc,
        rawJson = """{"demo":"$tag","date":"$date","amount":"$amount"}""",
    )

    private fun own(tag: String, date: LocalDate, amount: String, text: String) =
        card(tag, date, amount, "Revolut").copy(creditorName = null, remittance = listOf(text))

    private fun directDebit(tag: String, date: LocalDate, amount: String, creditor: String) =
        card(tag, date, amount, creditor).copy(bankTxCode = "PMDD Direct debit")

    private fun transfer(tag: String, date: LocalDate, amount: String, name: String, iban: String) =
        card(tag, date, amount, name).copy(creditorIban = iban, remittance = listOf("To $name"), bankTxCode = "ICDT Transfer")

    private fun salary(tag: String, date: LocalDate, amount: String, from: String) =
        card(tag, date, amount, from).copy(creditDebit = "CRDT", creditorName = null, debtorName = from, debtorIban = "FR7699999000001")

    private fun topUp(tag: String, date: LocalDate, amount: String) =
        card(tag, date, amount, "Revolut").copy(creditDebit = "CRDT", creditorName = null, remittance = listOf("Top-Up by *4421"))

    companion object {
        const val IBAN = "LT00DEMO0000000001"
        const val OWNER = "SAM TAYLOR"
        const val OWNER_IBAN = "FR7612345000019876543210"
    }
}
