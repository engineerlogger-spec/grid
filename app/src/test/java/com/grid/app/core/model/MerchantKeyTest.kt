package com.grid.app.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MerchantKeyTest {
    @Test fun normalizesCaseNumbersAndPunctuation() {
        assertThat(MerchantKey.of("STARBUCKS #1234")).isEqualTo("starbucks")
        assertThat(MerchantKey.of("  Starbucks  ")).isEqualTo("starbucks")
        assertThat(MerchantKey.of("Café de Flore")).isEqualTo("cafe de flore")
        assertThat(MerchantKey.of("UBER *TRIP")).isEqualTo("uber trip")
    }

    @Test fun blankOrNumericOnlyIsNull() {
        assertThat(MerchantKey.of("  ")).isNull()
        assertThat(MerchantKey.of("1234")).isNull()
    }
}
