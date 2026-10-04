package com.grid.app.core.bank

import com.google.common.truth.Truth.assertThat
import com.grid.app.core.model.PaymentKind
import org.junit.Test

class DescriptorCleanerTest {

    private fun check(raw: String, merchant: String, via: PaymentKind? = null) {
        assertThat(DescriptorCleaner.clean(raw)).isEqualTo(DescriptorCleaner.Cleaned(merchant, via))
    }

    @Test fun paypalPrefixesRevealTheMerchant() {
        check("PAYPAL *NETFLIX", "Netflix", PaymentKind.PAYPAL)
        check("PayPal *Spotify AB", "Spotify AB", PaymentKind.PAYPAL)
        check("PP*STEAM GAMES", "Steam Games", PaymentKind.PAYPAL)
    }

    @Test fun paypalWithoutMerchantIsPaypal() {
        check("PayPal Europe S.a.r.l. et Cie S.C.A", "PayPal", PaymentKind.PAYPAL)
    }

    @Test fun processorPrefixesAreStripped() {
        check("SQ *BLUE BOTTLE", "Blue Bottle")
        check("SumUp *Café Lola", "Café Lola")
        check("ZETTLE_*Le Comptoir", "Le Comptoir")
    }

    @Test fun storeNumbersAndCountryCodesAreStripped() {
        check("LIDL 1234", "Lidl")
        check("CARREFOUR CITY FRA", "Carrefour City")
        check("MONOPRIX #0456", "Monoprix")
    }

    @Test fun revolutDescriptorsFromRealData() {
        check("Paypal *bolt.eu/o/2609261", "Bolt", PaymentKind.PAYPAL)
        check("Paypal *openai *chatgpt S", "Openai", PaymentKind.PAYPAL)
        check("Anthropic* Claude Sub", "Anthropic")
        check("Sunday*vapiano Villages N", "Vapiano Villages N")
        check("Nyx*caffenero", "Caffenero")
        check("Sc-boul Du Ctre", "Boul Du Ctre")
        check("Restaurant Royau2164469", "Restaurant Royau")
        check("Kocak           4018250", "Kocak")
    }

    @Test fun legalFormsKeepTheirSpelling() {
        check("ACME SAS", "Acme SAS")
        check("SIEMENS GMBH", "Siemens GmbH")
        check("JEAN DUPONT", "Jean Dupont")
    }

    @Test fun acronymsStayCapitalised() {
        check("EDF", "EDF")
        check("H&M", "H&M")
        check("LE COMPTOIR", "Le Comptoir")
        check("BNP PARIBAS", "BNP Paribas")
    }

    @Test fun shortWordsInPeoplesNamesAreNotAcronyms() {
        check("SAM TAYLOR", "Sam Taylor")
        check("ANA LI", "Ana Li")
    }

    @Test fun mixedCaseIsKept() {
        check("Amazon.fr", "Amazon.fr")
        check("  Uber   Eats ", "Uber Eats")
    }

    @Test fun blankIsNull() {
        assertThat(DescriptorCleaner.clean("   ")).isNull()
        assertThat(DescriptorCleaner.clean("PAYPAL *")).isEqualTo(DescriptorCleaner.Cleaned("PayPal", PaymentKind.PAYPAL))
    }
}
