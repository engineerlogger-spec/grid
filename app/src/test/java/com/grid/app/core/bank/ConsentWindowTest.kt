package com.grid.app.core.bank

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.TimeUnit

class ConsentWindowTest {
    private val now = 1_791_147_100_000L
    private val day = TimeUnit.DAYS.toMillis(1)

    @Test fun staysADayInsideTheBanksMaximum() {
        // Asking for exactly the maximum fails with HTTP 422 when the phone clock is a hair ahead of the server.
        assertThat(ConsentWindow.validUntil(now, maxSeconds = 15_552_000)).isEqualTo(now + 179 * day)
    }

    @Test fun shortMaximumsKeepAMinuteOfMargin() {
        assertThat(ConsentWindow.validUntil(now, maxSeconds = 3_600)).isEqualTo(now + 3_540_000)
    }

    @Test fun unknownMaximumUses90Days() {
        assertThat(ConsentWindow.validUntil(now, maxSeconds = null)).isEqualTo(now + 89 * day)
    }

    @Test fun readsTheBanksMessage() {
        val body = """{"code":422,"message":"ASPSP does not support consent validity more than 15552000 seconds in the future","error":"WRONG_REQUEST_PARAMETERS"}"""
        assertThat(ConsentWindow.serverMessage(body)).isEqualTo("ASPSP does not support consent validity more than 15552000 seconds in the future")
        assertThat(ConsentWindow.serverMessage("<html>oops</html>")).isNull()
    }
}
