package com.grid.app.core.bank

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConsentRemindersTest {
    private val day = 86_400_000L
    private val now = 1_790_000_000_000L

    @Test fun aWeekThenADayBeforeTheEnd() {
        assertThat(ConsentReminders.due(now + 8 * day, now)).isNull()
        assertThat(ConsentReminders.due(now + 7 * day, now)).isEqualTo(7)
        assertThat(ConsentReminders.due(now + 2 * day, now)).isEqualTo(7)
        assertThat(ConsentReminders.due(now + day, now)).isEqualTo(1)
        assertThat(ConsentReminders.due(now + 3_600_000, now)).isEqualTo(1)
        assertThat(ConsentReminders.due(now, now)).isNull()
        assertThat(ConsentReminders.due(now - day, now)).isNull()
    }
}
