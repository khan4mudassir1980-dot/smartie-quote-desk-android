package `in`.smartie.quotedesk.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PurchaseBoard
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.ui.purchase.PurchaseCapabilities
import `in`.smartie.quotedesk.ui.purchase.removedHeading
import `in`.smartie.quotedesk.ui.screens.historyHeading

/**
 * Shared by the Purchase tab's screen tests.
 *
 * Several small classes rather than one large one, for the reason recorded in
 * `StockScreenFixtures`: Robolectric's native-object registry is a fixed array
 * per JVM and a Compose composition consumes a great many entries, so
 * `forkEvery(1)` plus small classes is what keeps it inside its bounds.
 */

internal val purchaseOwner = Member(uid = "uid_owner", name = "Omar", role = Role.OWNER)
internal val purchaseAdmin = Member(uid = "uid_admin", name = "Asha", role = Role.ADMIN)
internal val purchaseStaff = Member(uid = "uid_staff", name = "Sam", role = Role.STAFF)
internal val purchaseWorker = Member(uid = "uid_worker", name = "Wes", role = Role.WORKER)

/** A requirement in whatever state a test needs, in the PWA's own vocabulary. */
internal fun requirement(
    id: String,
    name: String = id,
    quantity: Double = 6.0,
    urgency: UrgencyV2 = UrgencyV2.URGENT,
    note: String = "",
    createdAt: Long = 1_700_000_000_000L,
    received: Boolean = false,
    receivedQuantity: Double? = null,
    receivedAt: Long = 0L,
    deleted: Boolean = false
): PurchaseRecord = PurchaseRecord(
    id = id,
    name = name,
    quantity = quantity,
    urgency = urgency,
    note = note,
    status = if (received) "Received" else "Needed",
    by = "Asha",
    createdAt = createdAt,
    received = received,
    receivedQuantity = receivedQuantity,
    receivedAt = receivedAt,
    deleted = deleted
)

/**
 * What [member] may do to each card, asked exactly as the real screen asks
 * it: per record, because who raised a requirement and whether anything has
 * arrived against it are facts about the card and not about the role.
 */
internal fun capabilitiesFor(member: Member): (PurchaseRecord) -> PurchaseCapabilities =
    { record -> PurchaseCapabilities.forRecord(member, record) }

/** Scroll the board until the node carrying [description] is composed. */
internal fun ComposeContentTestRule.scrollToDescription(description: String) {
    onNode(hasScrollAction()).performScrollToNode(hasContentDescription(description))
}

/**
 * Whether the board holds a node with [description] **anywhere**, scrolling
 * to look.
 *
 * A `LazyColumn` does not compose what is off screen, so an absent control
 * and one merely below the fold look identical to `assertExists`. Scrolling
 * first is what makes "a Manager is never offered Reopen" mean it.
 */
internal fun ComposeContentTestRule.boardHas(description: String): Boolean {
    runCatching { scrollToDescription(description) }
    return onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
}

/** The same, for a piece of text rather than a description. */
internal fun ComposeContentTestRule.boardShows(text: String): Boolean {
    runCatching { scrollToText(text) }
    return onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
}

/**
 * Any History heading at all.
 *
 * A substring, deliberately: the heading carries its own count, and a test
 * that asserts the section is absent should not have to know what the count
 * would have been.
 */
internal const val HISTORY_PREFIX: String = "History ("

/** Whether the board is offering History. */
internal fun ComposeContentTestRule.hasHistory(): Boolean = boardShows(HISTORY_PREFIX)

/**
 * Open it, so what is inside can be asserted.
 *
 * History is collapsed on arrival since N4.4, so a test that looks for a
 * received row has to ask for it first — exactly as a person does.
 */
internal fun ComposeContentTestRule.openHistory(count: Int) {
    val heading = historyHeading(count, open = false)
    scrollToDescription(heading)
    onNodeWithContentDescription(heading).performClick()
}

/** And the removals inside it, which fold again. */
internal fun ComposeContentTestRule.openRemoved(count: Int) {
    val heading = removedHeading(count, open = false)
    scrollToDescription(heading)
    onNodeWithContentDescription(heading).performClick()
}

/**
 * Open History when these records put anything in it.
 *
 * For the classes that assert what a *closed* row offers. Without this their
 * `assertFalse`s would pass because the fold hides the control rather than
 * because the role does — which is the same "passing for the wrong reason"
 * that let B1 through.
 */
internal fun ComposeContentTestRule.openHistoryFor(records: List<PurchaseRecord>) {
    val count = PurchaseBoard.closed(records).size
    if (count > 0) openHistory(count)
}
