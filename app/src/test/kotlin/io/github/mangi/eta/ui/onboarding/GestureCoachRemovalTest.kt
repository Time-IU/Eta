package io.github.mangi.eta.ui.onboarding

import org.junit.Assert.*
import org.junit.Test

class GestureCoachRemovalTest {
    @Test fun onlyExistingHistoryAndWorkspaceGesturesAreTaught() {
        assertEquals(listOf(OnboardingKeys.SwipeRight), GestureCoachScript.steps.map { it.key })
        assertTrue(GestureCoachScript.steps.none { it.direction == GestureDirection.SwipeUp })
    }
    @Test fun completingRemainingGesturesDoesNotLeaveAnImpossibleWhiteBarLesson() {
        assertNull(GestureCoachScript.next(mapOf(OnboardingKeys.SwipeRight to 1, OnboardingKeys.SwipeLeft to 1)))
    }
    @Test fun oldSavedSwipeUpProgressIsHarmlessAndDoesNotEraseOtherProgress() {
        assertNull(GestureCoachScript.next(mapOf(OnboardingKeys.SwipeRight to 1, OnboardingKeys.SwipeUp to 1)))
        assertTrue(OnboardingKeys.All.contains(OnboardingKeys.SwipeUp))
    }
}
