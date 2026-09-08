package com.hym.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class CheckoutTotalTest {
    @Test fun acceptsDisplayedBahtAmounts() {
        assertEquals(BigDecimal("19529.00"), CheckoutTotal.parse("฿19,529.00"))
        assertEquals(BigDecimal("175"), CheckoutTotal.parse(" ฿ 175 "))
    }
    @Test fun rejectsAmbiguousOrMalformedAmounts() {
        listOf("฿1,2", "฿1,2345", "฿-5", "175", "฿NaN", "฿1.234", "฿100 ฿200", "").forEach {
            assertNull(CheckoutTotal.parse(it))
        }
    }
}
