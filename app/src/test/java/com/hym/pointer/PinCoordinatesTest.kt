package com.hym.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PinCoordinatesTest {
    private val valid = "0,0,100,200;1,1;2,2;3,3;4,4;5,5;6,6"
    @Test fun parsesDeviceConfiguration() {
        val config = PinCoordinates.parse(valid, 100, 200)
        assertEquals(6, config.taps.size)
        assertEquals(100, config.right)
    }
    @Test fun rejectsIncompleteMalformedAndOutOfBoundsCoordinates() {
        listOf("", valid + ";7,7", valid.replace("6,6", "100,6"),
            valid.replace("1,1", "x,1"), valid.replace("0,0,100,200", "0,0,101,200")
        ).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { PinCoordinates.parse(raw, 100, 200) }
        }
    }
}
