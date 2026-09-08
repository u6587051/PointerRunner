package com.hym.pointer

import java.math.BigDecimal

/** Strict THB display parsing; reject malformed grouping rather than guessing a price. */
internal object CheckoutTotal {
    fun parse(text: String): BigDecimal? {
        val match = Regex("฿\\s*((?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?)")
            .matchEntire(text.trim()) ?: return null
        return match.groupValues[1].replace(",", "").toBigDecimalOrNull()
    }
}
