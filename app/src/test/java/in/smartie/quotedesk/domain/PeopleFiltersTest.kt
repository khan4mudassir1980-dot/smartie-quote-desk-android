package `in`.smartie.quotedesk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleFiltersTest {

    private val viewer = Member(
        uid = "uid_admin", email = "admin@example.invalid", name = "Administrator",
        role = Role.ADMIN, createdAt = 100
    )
    private val staff = Member(
        uid = "uid_staff", email = "staff@example.invalid", name = "Bina Staff",
        role = Role.STAFF, createdAt = 200
    )
    private val worker = Member(
        uid = "uid_worker", email = "worker@example.invalid", name = "Ajay Worker",
        role = Role.WORKER, createdAt = 300
    )
    private val disabled = Member(
        uid = "uid_off", email = "off@example.invalid", name = "Chandra Off",
        role = Role.WORKER, active = false, createdAt = 400
    )

    private val everyone = listOf(viewer, staff, worker, disabled)

    @Test
    fun `the signed-in person never appears in their own list`() {
        val groups = PeopleFilters.group(everyone, viewer)
        assertTrue(groups.active.none { it.uid == viewer.uid })
        assertEquals(3, groups.total)
    }

    @Test
    fun `a second profile with the same email but another uid is still the caller`() {
        val duplicateIdentity = viewer.copy(
            uid = "uid_admin_google",
            email = "ADMIN@example.invalid",
            createdAt = 500
        )
        val groups = PeopleFilters.group(everyone + duplicateIdentity, viewer)
        assertTrue(groups.active.none { it.normalisedEmail == viewer.normalisedEmail })
        assertEquals(3, groups.total)
    }

    @Test
    fun `duplicate profiles for one email collapse to the newest`() {
        val older = worker.copy(uid = "uid_worker_old", name = "Ajay (old)", createdAt = 50)
        val deduplicated = PeopleFilters.deduplicate(listOf(older, worker))
        assertEquals(1, deduplicated.size)
        assertEquals("uid_worker", deduplicated.single().uid)
    }

    @Test
    fun `people without an email are kept apart by uid`() {
        val first = Member(uid = "uid_1", name = "No email one", createdAt = 1)
        val second = Member(uid = "uid_2", name = "No email two", createdAt = 2)
        assertEquals(2, PeopleFilters.deduplicate(listOf(first, second)).size)
    }

    @Test
    fun `active people come before switched-off ones and sort by name`() {
        val groups = PeopleFilters.group(everyone, viewer)
        assertEquals(listOf("Ajay Worker", "Bina Staff"), groups.active.map { it.name })
        assertEquals(listOf("Chandra Off"), groups.switchedOff.map { it.name })
    }

    @Test
    fun `search matches a name or an email`() {
        val byName = PeopleFilters.group(everyone, viewer, PeopleFilter(query = "bina"))
        assertEquals(listOf("Bina Staff"), byName.active.map { it.name })

        val byEmail = PeopleFilters.group(everyone, viewer, PeopleFilter(query = "worker@"))
        assertEquals(listOf("Ajay Worker"), byEmail.active.map { it.name })

        val noMatch = PeopleFilters.group(everyone, viewer, PeopleFilter(query = "nobody"))
        assertTrue(noMatch.isEmpty)
    }

    @Test
    fun `the status filter splits active from switched off`() {
        val onlyActive = PeopleFilters.group(
            everyone, viewer, PeopleFilter(status = PeopleStatusFilter.ACTIVE)
        )
        assertEquals(2, onlyActive.active.size)
        assertTrue(onlyActive.switchedOff.isEmpty())

        val onlyOff = PeopleFilters.group(
            everyone, viewer, PeopleFilter(status = PeopleStatusFilter.SWITCHED_OFF)
        )
        assertTrue(onlyOff.active.isEmpty())
        assertEquals(listOf("Chandra Off"), onlyOff.switchedOff.map { it.name })
    }

    @Test
    fun `duplicate emails are reported for the migration`() {
        val duplicate = worker.copy(uid = "uid_worker_2", email = "WORKER@example.invalid")
        val duplicates = PeopleFilters.duplicateEmails(everyone + duplicate)
        assertEquals(listOf("worker@example.invalid"), duplicates)
        assertFalse(duplicates.contains("staff@example.invalid"))
    }
}
