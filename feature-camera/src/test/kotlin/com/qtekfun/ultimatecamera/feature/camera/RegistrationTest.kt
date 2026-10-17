package com.qtekfun.ultimatecamera.feature.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression for the crash "Thermal status listener was not added" when the monitor stopped without starting. */
class RegistrationTest {
    /** Behaves like PowerManager: removing a listener it does not hold throws IllegalArgumentException. */
    private class StrictRegistry {
        var held = 0

        fun add() {
            held++
        }

        fun remove() {
            require(held > 0) { "Thermal status listener was not added" }
            held--
        }
    }

    @Test
    fun unregisterWithoutRegisterDoesNotTouchTheRegistry() {
        val registry = StrictRegistry()
        val r = Registration(registry::add, registry::remove)
        r.unregister()
        assertEquals(0, registry.held)
        assertFalse(r.registered)
    }

    @Test
    fun registerTwiceAddsOnceAndUnregisterTwiceRemovesOnce() {
        val registry = StrictRegistry()
        val r = Registration(registry::add, registry::remove)
        r.register()
        r.register()
        assertEquals(1, registry.held)
        assertTrue(r.registered)
        r.unregister()
        r.unregister()
        assertEquals(0, registry.held)
    }

    @Test
    fun canBeRegisteredAgainAfterUnregister() {
        val registry = StrictRegistry()
        val r = Registration(registry::add, registry::remove)
        r.register()
        r.unregister()
        r.register()
        assertEquals(1, registry.held)
    }
}
