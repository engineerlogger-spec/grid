package com.grid.app.core.ai

import com.google.common.truth.Truth.assertThat
import com.grid.app.core.model.Cycle
import com.grid.app.core.model.CycleUnit
import org.junit.Assert.assertThrows
import org.junit.Test

class AiPromptsTest {

    @Test fun readsTheTextOfGeminisAnswerAndTheJsonInsideIt() {
        val response = """{"candidates":[{"content":{"parts":[{"text":"```json\n[{\"key\":\"sfr\"}]\n```"}]}}]}"""
        val text = AiJson.answerText(response)!!
        assertThat(AiJson.parse(text).toString()).isEqualTo("""[{"key":"sfr"}]""")
        assertThrows(AiError.BadAnswer::class.java) { AiJson.parse("Sorry, I can't help with that.") }
    }

    @Test fun recognitionAnswersAreReadEvenWhenWrapped() {
        val answer = AiJson.parse("""{"payees":[{"key":"berfin","name":"Berfin Kebab","about":"Turkish fast food","category":"Restaurant","confidence":"0.8"}]}""")
        val r = AiPrompts.parseRecognition(answer).single()
        assertThat(r.name).isEqualTo("Berfin Kebab")
        assertThat(r.category).isEqualTo("restaurant")
        assertThat(r.confidence).isWithin(0.001).of(0.8)
    }

    @Test fun billsBecomeCyclesAndMinorUnits() {
        val answer = AiJson.parse(
            """[{"key":"sfr","cadence":"monthly","amount":14.99,"varies":true,"active":true,"confidence":0.95},
                {"key":"allianz","cadence":"quarterly","amount":35.34,"confidence":0.9},
                {"key":"odd","cadence":"fortnightly","amount":5}]""",
        )
        val bills = AiPrompts.parseBills(answer, "EUR")
        assertThat(bills.map { it.key }).containsExactly("sfr", "allianz").inOrder()
        assertThat(bills[0].amountMinor).isEqualTo(1499)
        assertThat(bills[0].varies).isTrue()
        assertThat(bills[1].cycle).isEqualTo(Cycle(CycleUnit.MONTH, 3))
        assertThat(bills[1].active).isTrue()
    }

    @Test fun answersAndNotesAreRead() {
        assertThat(AiPrompts.parseAnswer(AiJson.parse("""{"answer":"You spent €412 on food."}"""))).isEqualTo("You spent €412 on food.")
        val notes = AiPrompts.parseDigest(AiJson.parse("""[{"title":"Netflix went up","detail":"€7.99 → €8.99","tone":"warning"},{"title":"","detail":"x"}]"""))
        assertThat(notes.single().tone).isEqualTo(NoteTone.WARNING)
    }

    @Test fun promptsCarryTheDataAndTheRules() {
        val prompt = AiPrompts.recognition(listOf(PayeeFacts("sfr", "SFR", "transfer", 4, 14.99)), listOf("bills" to "Bills & Utilities"))
        assertThat(prompt).contains("\"raw\":\"SFR\"")
        assertThat(prompt).contains("bills: Bills & Utilities")
        assertThat(AiPrompts.bills(java.time.LocalDate.parse("2026-10-05"), "EUR", emptyList())).contains("Do NOT list everyday shopping")
    }
}
