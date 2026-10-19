package com.galaxyrio.gracelauncher

import com.galaxyrio.gracelauncher.ui.components.PopupSwipeIntent
import org.junit.Assert.*
import org.junit.Test

class PopupSwipeIntentTest {
    @Test fun firstRightMovementAfterTouchSlopTriggersOpening() {
        val swipe = PopupSwipeIntent(8f)
        assertNull(swipe.drag(-20f))
        assertEquals(true, swipe.drag(1f))
        assertTrue(swipe.expanded)
        assertNull(swipe.drag(0f))
        assertTrue(swipe.expanded)
    }

    @Test fun directionReversalDoesNotNeedToUndoALongDrag() {
        val swipe = PopupSwipeIntent(8f)
        swipe.drag(200f)
        assertNull(swipe.drag(-3f))
        assertEquals(false, swipe.drag(-5f))
        assertNull(swipe.drag(3f))
        assertEquals(true, swipe.drag(5f))
    }

    @Test fun leftOriginCanCommitDismissalWithoutChangingDrawerSwipeBehavior() {
        val homeSwipe = PopupSwipeIntent(8f, dismissOnLeft = true)
        assertNull(homeSwipe.drag(-20f))
        assertTrue(homeSwipe.dismissOnCommit)
        assertFalse(homeSwipe.revealed)
        assertNull(homeSwipe.drag(30f))
        assertFalse(homeSwipe.revealed)
        homeSwipe.reset()
        assertFalse(homeSwipe.dismissOnCommit)

        val regularSwipe = PopupSwipeIntent(8f)
        assertNull(regularSwipe.drag(-20f))
        assertFalse(regularSwipe.dismissOnCommit)
        assertEquals(true, regularSwipe.drag(1f))
    }

    @Test fun leftReversalOfRightSwipeOnlyCollapsesThePopup() {
        val swipe = PopupSwipeIntent(8f, dismissOnLeft = true)
        assertEquals(true, swipe.drag(200f))
        assertNull(swipe.drag(-3f))
        assertFalse(swipe.dismissOnCommit)
        assertEquals(false, swipe.drag(-5f))
        assertFalse(swipe.dismissOnCommit)
    }

    @Test fun smallJitterDoesNotRetargetAndNewGestureResetsIntent() {
        val swipe = PopupSwipeIntent(8f)
        swipe.drag(1f)
        repeat(20) { assertNull(swipe.drag(-2f)); assertNull(swipe.drag(2f)) }
        assertTrue(swipe.expanded)
        swipe.reset()
        assertFalse(swipe.revealed)
        assertFalse(swipe.expanded)
    }
}
