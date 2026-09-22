package `in`.smartie.quotedesk.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.semantics.SemanticsNode
import org.junit.Assert.assertTrue

/**
 * Whether a card actually **painted** its controls.
 *
 * `assertIsDisplayed()` cannot answer that. It asks a node about its own
 * bounds; it never asks whether an ancestor clipped it away. A control sitting
 * outside a clipping parent is still "displayed" by that definition, which is
 * how N4.4's B1 shipped with the suite green: `SmartieCard` fixed its height
 * from an intrinsic measurement, the footer's `FlowRow` wrapped to a second
 * line the fixed height had no room for, and the rounded-corner clip erased
 * it. Every affected control was the last one in the row — Remove, and Close
 * short — on every card and for every role.
 *
 * So these compare two things that `assertIsDisplayed` never puts side by
 * side: what a node **would** occupy, and what survived its ancestors.
 *
 * Wrap the card under test in a `Box` tagged [CARD_HOST_TAG] and nothing else,
 * so the host's bounds **are** the card's.
 */

internal const val CARD_HOST_TAG: String = "card-under-test"

/** Half a pixel, the same tolerance the other geometry tests use. */
private const val EPSILON = 0.5f

/** The bounds this node would occupy if no ancestor clipped it. */
internal fun SemanticsNode.unclippedBounds(): Rect = Rect(positionInRoot, size.toSize())

/** The card's own bounds, which is what the tagged host wraps. */
internal fun ComposeContentTestRule.cardBounds(cardTag: String = CARD_HOST_TAG): Rect =
    onNodeWithTag(cardTag).fetchSemanticsNode().boundsInRoot

/**
 * The same, for a card that names itself.
 *
 * The stock card carries a `contentDescription` on the `SmartieCard` — put
 * there so a test could prove the controls belong to that card and not a
 * second one — so it needs no host of its own.
 */
internal fun ComposeContentTestRule.cardBoundsNamed(description: String): Rect =
    onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot

/**
 * One control, painted whole, inside its card, at a real size, and tappable.
 *
 * Four separate failures, each named, because they have four different causes:
 * a control with no size was never laid out; a clipped one was laid out and
 * erased; one outside the card's bounds means the card is measuring wrongly;
 * and one below 48dp is not a touch target whatever else is true of it.
 */
internal fun ComposeContentTestRule.assertPaintedIn(
    description: String,
    card: Rect,
    minimumTarget: Dp = 48.dp
) {
    val control = onNodeWithContentDescription(description)
    control.assertHasClickAction()

    val node = control.fetchSemanticsNode()
    val whole = node.unclippedBounds()
    val painted = node.boundsInRoot

    assertTrue("$description was never laid out: $whole", whole.width > 0f && whole.height > 0f)
    assertTrue(
        "$description is clipped — $painted painted of $whole",
        painted.width >= whole.width - EPSILON && painted.height >= whole.height - EPSILON
    )
    assertTrue(
        "$description falls outside its card — $whole against $card",
        whole.left >= card.left - EPSILON &&
            whole.right <= card.right + EPSILON &&
            whole.top >= card.top - EPSILON &&
            whole.bottom <= card.bottom + EPSILON
    )

    control.assertWidthIsAtLeast(minimumTarget).assertHeightIsAtLeast(minimumTarget)
}

/** One control against the tagged host, which is the common case. */
internal fun ComposeContentTestRule.assertPaintedInsideCard(
    description: String,
    cardTag: String = CARD_HOST_TAG
) = assertPaintedIn(description, cardBounds(cardTag))

/** All of them, so a test names the whole footer rather than one control. */
internal fun ComposeContentTestRule.assertFooterPaintedInsideCard(
    descriptions: List<String>,
    cardTag: String = CARD_HOST_TAG
) {
    val card = cardBounds(cardTag)
    descriptions.forEach { assertPaintedIn(it, card) }
}

/** The same, for a card found by its own description rather than a host. */
internal fun ComposeContentTestRule.assertFooterPaintedInsideNamedCard(
    descriptions: List<String>,
    cardDescription: String
) {
    val card = cardBoundsNamed(cardDescription)
    descriptions.forEach { assertPaintedIn(it, card) }
}

/**
 * Nothing below the last control but the card's own padding.
 *
 * The other half of B1: where the intrinsic height over-reported rather than
 * under-reporting, the card reserved room for a wrapped line it then drew on
 * one, and the surplus showed as the "large blank area under the buttons" the
 * phone pass reported. [allowance] is deliberately loose — this is looking for
 * a visible hole, not pinning a padding value that a compaction pass will
 * change.
 */
internal fun ComposeContentTestRule.assertNoDeadSpaceBelow(
    description: String,
    card: Rect,
    allowance: Dp = 24.dp
) = assertNoDeadSpaceUnder(
    onNodeWithContentDescription(description).fetchSemanticsNode(),
    description,
    card,
    allowance
)

/** The same, for the last thing on a card that has no control to measure. */
internal fun ComposeContentTestRule.assertNoDeadSpaceBelowText(
    text: String,
    card: Rect,
    allowance: Dp = 40.dp
) = assertNoDeadSpaceUnder(onNodeWithText(text).fetchSemanticsNode(), text, card, allowance)

private fun ComposeContentTestRule.assertNoDeadSpaceUnder(
    node: SemanticsNode,
    named: String,
    card: Rect,
    allowance: Dp
) {
    val gap = card.bottom - node.unclippedBounds().bottom
    val limit = with(density) { allowance.toPx() }

    assertTrue(
        "${gap}px of dead space under $named, more than the $allowance allowed",
        gap <= limit + EPSILON
    )
}
