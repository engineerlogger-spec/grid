package com.grid.app.core.bank

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BankAuthInboxTest {

    @Test fun appLink() {
        assertThat(BankAuthInbox.parse("grid://bank-callback?code=abc&state=s%201"))
            .isEqualTo(BankAuthInbox.Callback("abc", "s 1", null))
    }

    @Test fun pastedBouncePageAddress() {
        assertThat(BankAuthInbox.parse("  https://engineerlogger-spec.github.io/grid/bank-callback/?state=st&code=c-9  "))
            .isEqualTo(BankAuthInbox.Callback("c-9", "st", null))
    }

    @Test fun refusal() {
        assertThat(BankAuthInbox.parse("grid://bank-callback?error=access_denied&state=st"))
            .isEqualTo(BankAuthInbox.Callback(null, "st", "access_denied"))
    }

    @Test fun anythingElseIsIgnored() {
        assertThat(BankAuthInbox.parse("hello")).isNull()
        assertThat(BankAuthInbox.parse("https://example.com/?code=x")).isNull()
        assertThat(BankAuthInbox.parse("grid://bank-callback")).isNull()
    }

    @Test fun postAndConsume() {
        val inbox = BankAuthInbox()
        assertThat(inbox.post("nope")).isFalse()
        assertThat(inbox.post("grid://bank-callback?code=a&state=b")).isTrue()
        assertThat(inbox.pending.value?.code).isEqualTo("a")
        inbox.consume()
        assertThat(inbox.pending.value).isNull()
    }
}
