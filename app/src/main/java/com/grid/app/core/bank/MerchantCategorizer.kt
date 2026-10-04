package com.grid.app.core.bank

import java.text.Normalizer
import java.util.Locale

/**
 * Guesses a category from a merchant or payee name, because Revolut's Open Banking feed carries no merchant
 * category code. Returns a seed category icon key, or null when unsure (the payment then goes to "Other").
 *
 * Rules are checked in order (specific before generic). Billers — telecom, energy, insurance, rent agencies —
 * also apply to bank transfers; everything else only to card payments, so a person named "Paul" never
 * becomes a bakery.
 */
object MerchantCategorizer {
    private class Rule(val iconKey: String, val transfers: Boolean, patterns: List<String>) {
        val regex = Regex(patterns.joinToString("|") { "(?:$it)" })
    }

    private fun rule(iconKey: String, vararg patterns: String, transfers: Boolean = false) = Rule(iconKey, transfers, patterns.toList())

    private val rules = listOf(
        // Billers (also paid by transfer / direct debit)
        rule(
            "insurance", " allianz ", " allsecur ", " avanssur ", " axa ", " maif ", " macif ", " matmut ", " groupama ", " gmf ",
            " luko ", " direct assur", " assurance", " assur ", " mutuelle ", transfers = true,
        ),
        rule(
            "bills", " sfr ", " orange ", " sosh ", " bouygues ", " free mobile ", " free telecom ", " engie ", " edf ",
            " totalenergies electricite", " veolia ", " suez ", " eau de ", " ekwateur ", " octopus energy ", transfers = true,
        ),
        rule(
            "housing", " century 21 ", " agence du cedre ", " foncia ", " nexity ", " orpi ", " laforet ", " loyer ", " immobili",
            " syndic ", " habitat ", transfers = true,
        ),
        rule("transport", " idfm ", " navigo ", " sncf ", " ratp ", " transilien ", transfers = true),
        rule("education", " conduite ", " auto ecole ", " ecole ", " universite ", " udemy ", " coursera ", transfers = true),
        // Subscriptions before shops: "Disney+" vs "Euro Disney", "Amazon Prime" vs Amazon
        rule("entertainment", " euro disney ", " disneyland ", " cinema ", " ugc ", " pathe ", " gaumont ", " chateau de vers", " bateaux par", " musee "),
        rule(
            "subscriptions", " netflix ", " spotify ", " disney ", " deezer ", " canal ", " youtube ", " apple com", " icloud ",
            " claude ", " anthropic ", " chatgpt ", " openai ", " google ", " tiktok ", " activision ", " amazon prime ", " prime video ",
            " plan fee ", " premium ", " adobe ", " microsoft ", " dropbox ",
        ),
        // Card payments
        rule(
            "restaurant", " starbucks ", " sbx", " cafe ", " caffe", "coffee", " selecta ", " nyx ", " boul", " moulin a pain ",
            " millefeuille", " brioche ", " marie blachere ", " paul ", " pret a manger ", " pretamanger ", "pizz", " pasta ",
            "burger", " poulet ", "food", "bouffe", " resto ", "restaurant", " restarea ", " factory and co ", " brasserie ", " bistro", " crepe", " churros ",
            " haagen", "kebab", "tacos", "sushi", " mc do ", " mcdonald", " kfc ", " quick ", " subway ", " vapiano ",
            " cantine ", " table ", " traiteur ", " patisserie ", " leonidas ", " chocolat", " glace", " snack", " uber eats ",
            " deliveroo ", " just eat ", " sc rest", " delices ",
        ),
        rule(
            "groceries", " leclerc ", " carref", " carrefour ", " auchan ", " lidl ", " aldi ", " intermarche ", " monoprix ", " monop ", " franprix ",
            " casino ", " picard ", " grand frais ", " super u ", " hyper u ", " netto ", " leader price ", " biocoop ", " naturalia ",
            " market ", " marche ", " supermar", " supermache ", " alim", " epicerie ", " boucherie ", " fruits ", " primeur ",
            " mavidis ", " halal ",
        ),
        rule(
            "transport", " bolt ", " uber", " total ", " esso ", " shell ", " bp ", " avia ", " station ", " carburant ", " parking ",
            " indigo ", " peage ", " sanef ", " aprr ", " vinci ", " blablacar ", " lavage ", " autodoc ", " ovoko ", " controle ",
            " automobile", " automo", " motorsport ", " norauto ", " feu vert ", " midas ", " speedy ",
        ),
        rule("health", " pharmac", " pharma ", " newpharma ", " selarl ", " dr ", " med ", " healthcare ", " medecin", " dentist", " opticien", " laborat"),
        rule("personal_care", " coiff", " barber ", " hair ", " primor ", " sephora ", " nocibe ", " yves rocher "),
        rule("clothing", " okaidi ", " celio ", " kiabi", " primark ", " zara ", " h m ", " uniqlo ", " jules ", " jd sports ", " adidas ", " nike ", " fashion", " fash "),
        rule(
            "shopping", " amazon ", " fnac ", " darty ", " cdiscount ", " electrodepo", " boulanger ", " ikea ", " castorama ",
            " leroy merlin ", " decathlon ", " intersport ", " action ", " cultura ", " jouet ", " westfield ", " leboncoin ", " alipay ",
            " aliexpress ", " temu ", " shein ", " paiement 4x ", " samaritaine ", " gamer2gamer ",
        ),
        rule("travel", " airbnb ", " booking ", " ryanair ", " easyjet ", " air france ", " transavia ", " hotel ", " accor ", " ibis ", " airport "),
        rule("services", " cordonnerie ", " traduc", " consul", " ofii ", " drfip ", " impots ", " la poste "),
    )

    private val diacritics = Regex("\\p{Mn}+")
    private val separators = Regex("[^a-z0-9]+")

    /** [isTransfer]: a bank transfer or direct debit (only biller rules apply). */
    fun iconKeyFor(name: String?, isTransfer: Boolean = false): String? {
        if (name.isNullOrBlank()) return null
        val text = " " + Normalizer.normalize(name, Normalizer.Form.NFD).replace(diacritics, "").lowercase(Locale.ROOT)
            .replace(separators, " ").trim() + " "
        return rules.firstOrNull { (!isTransfer || it.transfers) && it.regex.containsMatchIn(text) }?.iconKey
    }
}
