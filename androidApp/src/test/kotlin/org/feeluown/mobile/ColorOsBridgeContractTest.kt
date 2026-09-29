package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bridge wires these values by plain string equality, so a typo never throws: the module just
 * logs an identity mismatch and drops the broadcast. These expectations are written as literals on
 * purpose, independent of the production constants, so an accidental edit is caught here.
 */
class ColorOsBridgeContractTest {
    @Test
    fun bindingsWireFormatMatchesTheColorOsModule() {
        assertEquals("io.github.andrealtb.lockscreenlyrics", ColorOsBridgeContract.BRIDGE_PACKAGE_NAME)
        assertEquals(
            "io.github.andrealtb.lockscreenlyrics.UniversalPlayerBindingsReceiver",
            ColorOsBridgeContract.BINDINGS_RECEIVER_CLASS_NAME,
        )
        assertEquals(
            "io.github.andrealtb.universallyrics.action.PLAYER_BINDINGS_CHANGED",
            ColorOsBridgeContract.ACTION_PLAYER_BINDINGS_CHANGED,
        )
        assertEquals("extra_bound_packages", ColorOsBridgeContract.EXTRA_BOUND_PACKAGES)
        assertEquals("extra_provider_identity", ColorOsBridgeContract.EXTRA_PROVIDER_IDENTITY)
        assertEquals("universal-player-provider", ColorOsBridgeContract.PROVIDER_IDENTITY)
    }

    @Test
    fun explicitReceiverIsAddressedInsideTheModulePackage() {
        assertTrue(
            ColorOsBridgeContract.BINDINGS_RECEIVER_CLASS_NAME
                .startsWith(ColorOsBridgeContract.BRIDGE_PACKAGE_NAME + "."),
        )
    }

    @Test
    fun thisAppAdmitsItselfAsTheBoundHostPlayer() {
        assertEquals(listOf("org.feeluown.mobile"), ColorOsBridgeContract.boundPackages("org.feeluown.mobile"))
    }

    @Test
    fun blankPackageNamesAreNotAdmitted() {
        assertEquals(emptyList(), ColorOsBridgeContract.boundPackages(""))
        assertEquals(emptyList(), ColorOsBridgeContract.boundPackages("   "))
    }
}
