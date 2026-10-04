package com.grid.app.core.bank

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MccCategoriesTest {
    @Test fun mapsKnownCodesToSeedIcons() {
        assertThat(MccCategories.iconKeyFor("5411")).isEqualTo("groceries")
        assertThat(MccCategories.iconKeyFor("5814")).isEqualTo("restaurant")
        assertThat(MccCategories.iconKeyFor("4121")).isEqualTo("transport")
        assertThat(MccCategories.iconKeyFor("3256")).isEqualTo("travel") // airline range
        assertThat(MccCategories.iconKeyFor("5691")).isEqualTo("clothing")
    }

    @Test fun unknownOrMissingIsNull() {
        assertThat(MccCategories.iconKeyFor("9999")).isNull()
        assertThat(MccCategories.iconKeyFor(null)).isNull()
        assertThat(MccCategories.iconKeyFor("abc")).isNull()
    }
}
