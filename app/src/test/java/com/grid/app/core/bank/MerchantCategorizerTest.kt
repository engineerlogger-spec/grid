package com.grid.app.core.bank

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MerchantCategorizerTest {
    private fun card(name: String) = MerchantCategorizer.iconKeyFor(name)
    private fun transfer(name: String) = MerchantCategorizer.iconKeyFor(name, isTransfer = true)

    @Test fun everydayShops() {
        assertThat(card("E.leclerc")).isEqualTo("groceries")
        assertThat(card("Carref Claye")).isEqualTo("groceries")
        assertThat(card("Market Le B Mesn")).isEqualTo("groceries")
        assertThat(card("Boucherie Quatre")).isEqualTo("groceries")
        assertThat(card("Starbucks Claye Souilly")).isEqualTo("restaurant")
        assertThat(card("Maxicoffee Idf")).isEqualTo("restaurant")
        assertThat(card("Sc-boul Du Ctre")).isEqualTo("restaurant")
        assertThat(card("Cafe Du Marche")).isEqualTo("restaurant") // a café, not a market
        assertThat(card("Iletaitunburger")).isEqualTo("restaurant")
        assertThat(card("Okaidi 0720")).isEqualTo("clothing")
        assertThat(card("Pharmacie Forum")).isEqualTo("health")
        assertThat(card("Ikea Paris")).isEqualTo("shopping")
        assertThat(card("Hair Coiff'moi")).isEqualTo("personal_care")
    }

    @Test fun transportAndSubscriptions() {
        assertThat(card("Paypal *bolt.eu/o/2609261")).isEqualTo("transport")
        assertThat(card("Service Navigo")).isEqualTo("transport")
        assertThat(card("Lavage Auto")).isEqualTo("transport")
        assertThat(card("Paypal *netflix")).isEqualTo("subscriptions")
        assertThat(card("Anthropic* Claude Sub")).isEqualTo("subscriptions")
        assertThat(card("Paypal *openai *chatgpt S")).isEqualTo("subscriptions")
        assertThat(card("Euro Disney Associes")).isEqualTo("entertainment") // not the Disney+ subscription
    }

    @Test fun billersEvenByTransfer() {
        assertThat(transfer("SFR")).isEqualTo("bills")
        assertThat(transfer("ENGIE")).isEqualTo("bills")
        assertThat(transfer("Allianz Direct Vers.")).isEqualTo("insurance")
        assertThat(transfer("AVANSSUR")).isEqualTo("insurance")
        assertThat(transfer("PMG (CENTURY 21 AGENCE DU CEDRE)")).isEqualTo("housing")
        assertThat(transfer("IDFM")).isEqualTo("transport")
    }

    @Test fun shopRulesNeverApplyToPeople() {
        assertThat(transfer("Paul Bakela")).isNull() // "Paul" is a bakery only on a card payment
        assertThat(transfer("Magdi Hamdaoui")).isNull()
        assertThat(card("Berfin")).isNull()
        assertThat(card("")).isNull()
    }
}
