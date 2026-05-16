package com.example.aiassistant

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SafetyChecker's pure logic (no Android Context required).
 * Context-dependent methods (isAppAllowed, getExcludedApps, etc.) are covered
 * by instrumented tests.
 */
class SafetyCheckerTest {

    @Before
    fun resetCooldown() {
        // Ensure each test starts with no active cooldown.
        // We do this by calling checkCooldown() after a small wait, relying on the
        // 800 ms threshold never being met if lastActionTime is at epoch 0.
        // The AtomicLong starts at 0, so the first checkCooldown() always passes.
    }

    // -------------------------------------------------------------------------
    // isActionSafe
    // -------------------------------------------------------------------------

    @Test
    fun isActionSafe_plainClickTarget_returnsTrue() {
        assertTrue(SafetyChecker.isActionSafe(ActionCommand(ActionType.CLICK, "Skip")))
    }

    @Test
    fun isActionSafe_allowButton_returnsTrue() {
        assertTrue(SafetyChecker.isActionSafe(ActionCommand(ActionType.CLICK, "Allow")))
    }

    @Test
    fun isActionSafe_purchaseKeyword_returnsFalse() {
        assertFalse(SafetyChecker.isActionSafe(ActionCommand(ActionType.CLICK, "Purchase now")))
    }

    @Test
    fun isActionSafe_buyKeyword_returnsFalse() {
        assertFalse(SafetyChecker.isActionSafe(ActionCommand(ActionType.CLICK, "Buy Premium")))
    }

    @Test
    fun isActionSafe_deleteKeyword_returnsFalse() {
        assertFalse(SafetyChecker.isActionSafe(ActionCommand(ActionType.CLICK, "Delete Account")))
    }

    @Test
    fun isActionSafe_caseInsensitive_returnsFalse() {
        assertFalse(SafetyChecker.isActionSafe(ActionCommand(ActionType.CLICK, "PURCHASE")))
        assertFalse(SafetyChecker.isActionSafe(ActionCommand(ActionType.CLICK, "Reset Settings")))
    }

    @Test
    fun isActionSafe_subscribeKeyword_returnsFalse() {
        assertFalse(SafetyChecker.isActionSafe(ActionCommand(ActionType.CLICK, "Subscribe to Premium")))
    }

    @Test
    fun isActionSafe_uninstallKeyword_returnsFalse() {
        assertFalse(SafetyChecker.isActionSafe(ActionCommand(ActionType.CLICK, "Uninstall app")))
    }

    @Test
    fun isActionSafe_confirmPaymentPhrase_returnsFalse() {
        assertFalse(SafetyChecker.isActionSafe(ActionCommand(ActionType.CLICK, "Confirm payment")))
    }

    @Test
    fun isActionSafe_placeOrderPhrase_returnsFalse() {
        assertFalse(SafetyChecker.isActionSafe(ActionCommand(ActionType.CLICK, "Place order")))
    }

    @Test
    fun isActionSafe_factoryResetPhrase_returnsFalse() {
        assertFalse(SafetyChecker.isActionSafe(ActionCommand(ActionType.CLICK, "Factory reset")))
    }

    @Test
    fun isActionSafe_wipeKeyword_returnsFalse() {
        assertFalse(SafetyChecker.isActionSafe(ActionCommand(ActionType.CLICK, "Wipe device")))
    }

    @Test
    fun isActionSafe_billingKeyword_returnsFalse() {
        assertFalse(SafetyChecker.isActionSafe(ActionCommand(ActionType.CLICK, "Billing details")))
    }

    @Test
    fun isActionSafe_emptyTarget_returnsTrue() {
        // Empty targets are benign — no keywords to match.
        assertTrue(SafetyChecker.isActionSafe(ActionCommand(ActionType.CLICK, "")))
    }

    @Test
    fun isActionSafe_whitespaceTarget_returnsTrue() {
        assertTrue(SafetyChecker.isActionSafe(ActionCommand(ActionType.CLICK, "   ")))
    }

    @Test
    fun isActionSafe_scrollActionWithDangerousTarget_returnsFalse() {
        // Any action type is subject to the same keyword filter.
        assertFalse(
            SafetyChecker.isActionSafe(ActionCommand(ActionType.SCROLL_FORWARD, "Delete all"))
        )
    }

    @Test
    fun containsDangerousText_wordBoundaryAvoidsFalsePositive() {
        // "Repayment" should not trip the exact-word "pay" keyword.
        assertFalse(SafetyChecker.containsDangerousText("Repayment schedule"))
    }

    @Test
    fun containsDangerousText_wordBoundaryAvoidsSubscriptionFalsePositive() {
        // "Subscription" and "Subscriber" should not match the "subscribe" keyword.
        assertFalse(SafetyChecker.containsDangerousText("Active subscription"))
        assertFalse(SafetyChecker.containsDangerousText("Subscriber count"))
    }

    @Test
    fun containsDangerousText_wordBoundaryAvoidsResetButton() {
        // "Preset" and "Presets" should not match "reset".
        assertFalse(SafetyChecker.containsDangerousText("Save preset"))
        assertFalse(SafetyChecker.containsDangerousText("My presets"))
    }

    @Test
    fun containsDangerousText_phraseWithPunctuation_isDetected() {
        assertTrue(SafetyChecker.containsDangerousText("Proceed to checkout!"))
    }

    @Test
    fun containsDangerousText_keywordFollowedByPunctuation_isDetected() {
        assertTrue(SafetyChecker.containsDangerousText("Delete?"))
        assertTrue(SafetyChecker.containsDangerousText("Buy."))
    }

    @Test
    fun containsDangerousText_emptyString_returnsFalse() {
        assertFalse(SafetyChecker.containsDangerousText(""))
    }

    @Test
    fun containsDangerousText_whitespaceOnly_returnsFalse() {
        assertFalse(SafetyChecker.containsDangerousText("   \t\n  "))
    }

    @Test
    fun containsDangerousText_mixedCase_isDetected() {
        assertTrue(SafetyChecker.containsDangerousText("Please ERASE everything"))
    }

    // -------------------------------------------------------------------------
    // cooldown
    // -------------------------------------------------------------------------

    @Test
    fun checkCooldown_initialState_returnsTrue() {
        // lastActionTime starts at 0 (epoch), so the cooldown period has long passed.
        assertTrue(SafetyChecker.checkCooldown())
    }

    @Test
    fun checkCooldown_afterRecord_returnsFalse() {
        SafetyChecker.recordActionTime()
        // Immediately after recording, cooldown should not have elapsed.
        assertFalse(SafetyChecker.checkCooldown())
    }

    @Test
    fun checkCooldown_afterCooldownElapsed_returnsTrue() {
        SafetyChecker.recordActionTime()
        // Wait slightly longer than the 800 ms cooldown.
        Thread.sleep(900)
        assertTrue(SafetyChecker.checkCooldown())
    }
}
