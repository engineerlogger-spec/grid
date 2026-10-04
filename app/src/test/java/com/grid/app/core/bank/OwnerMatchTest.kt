package com.grid.app.core.bank

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OwnerMatchTest {
    private val owner = listOf("Abdelhamid Mouloud")

    @Test fun theHoldersNameInAnyOrder() {
        assertThat(OwnerMatch.isOwner("MOULOUD ABDELHAMID", owner)).isTrue()
        assertThat(OwnerMatch.isOwner("M ABDELHAMID MOULOUD", owner)).isTrue()
        assertThat(OwnerMatch.isOwner("Abdelhamid Mouloud", owner)).isTrue()
    }

    @Test fun savedPayeesForTheHoldersOtherBanks() {
        assertThat(OwnerMatch.isOwner("Abdelhamid N26", owner)).isTrue()
        assertThat(OwnerMatch.isOwner("Abdelhamid BNP", owner)).isTrue()
    }

    @Test fun otherPeopleAreNot() {
        assertThat(OwnerMatch.isOwner("Magdi Hamdaoui", owner)).isFalse()
        assertThat(OwnerMatch.isOwner("Abdelhamid Benali", owner)).isFalse() // same first name, different person
        assertThat(OwnerMatch.isOwner("ENGIE S.A.", owner)).isFalse()
        assertThat(OwnerMatch.isOwner(null, owner)).isFalse()
        assertThat(OwnerMatch.isOwner("Abdelhamid Mouloud", emptyList())).isFalse()
    }
}
