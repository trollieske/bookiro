package com.bookrio.reader.pageturn

/** Which direction a page is being turned. */
enum class TurnDirection { FORWARD, BACKWARD }

/** Lifecycle state of the drag/curl gesture. */
enum class CurlGestureState { IDLE, DRAGGING, RELEASING }

/**
 * Full state for the page-turn engine.
 *
 * @param currentPage      Global page index (0-based, across all chapters).
 * @param totalPages       Total page count for the current book (filled in after render).
 * @param dragFraction     How far the current drag has progressed: 0 = no drag, 1 = fully turned.
 * @param direction        FORWARD (left drag) or BACKWARD (right drag).
 * @param gestureState     Current gesture phase.
 * @param doublePage       True when two pages should be shown side-by-side (tablet landscape).
 */
data class PageTurnState(
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val dragFraction: Float = 0f,
    val direction: TurnDirection = TurnDirection.FORWARD,
    val gestureState: CurlGestureState = CurlGestureState.IDLE,
    val doublePage: Boolean = false,
)
