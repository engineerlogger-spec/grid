package com.grid.app.core.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class SentLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun remembersAndPrunes() = runTest(UnconfinedTestDispatcher()) {
        val log = SentLog(PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("sent.preferences_pb").also { it.delete() } })
        val day = LocalDate.parse("2026-10-04")
        log.markSent(listOf("sub:1:100", "pend:2:overdue"), day)
        assertThat(log.sentKeys()).containsExactly("sub:1:100", "pend:2:overdue")
        // 61 days later the old entries are pruned on the next write.
        log.markSent(listOf("sub:3:200"), day.plusDays(61))
        assertThat(log.sentKeys()).containsExactly("sub:3:200")
    }
}
