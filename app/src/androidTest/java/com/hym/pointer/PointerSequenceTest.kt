package com.hym.pointer

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Test
import org.junit.runner.RunWith
import java.time.OffsetDateTime
import java.math.BigDecimal

/**
 * On-device coordinate sequence with a PIN screen/layout guard.
 * Verifies product and maximum checkout total; does not verify variation, quantity,
 * individual keypad labels, or payment outcome. steps denotes logical stages: 
 * 1=buy, 2=reach checkout, 3=place order, 4..9=PIN entry.
 * execute=false previews only; steps=1 by default. Set steps=9 explicitly for all.
 * PIN coordinates and keyboard bounds are supplied by the per-device configuration.
 * Never retry an interrupted sequence without checking the phone/order status.
 */
@RunWith(AndroidJUnit4::class)
class PointerSequenceTest {
    @Test
    fun runSequence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val device = UiDevice.getInstance(instrumentation)
        val execute = args.getString("execute", "false").toBooleanStrict()
        val steps = args.getString("steps", "1").toInt()
        require(steps in 1..9) { "steps must be 1..9" }
        val screenWidth = args.getString("screenWidth", "1080").toInt()
        val screenHeight = args.getString("screenHeight", "2340").toInt()
        require(screenWidth > 0 && screenHeight > 0) { "Invalid screen dimensions" }
        val pinConfig = args.getString("pinConfig")?.let { PinCoordinates.parse(it, screenWidth, screenHeight) }
        require(!execute || steps <= 3 || pinConfig != null) {
            "PIN entry requires per-device pinConfig; use scripts/run_flow.py"
        }
        val product = args.getString("product", "").trim()
        val maximum = args.getString("maxTotal")?.toBigDecimalOrNull()
        val flowTimeoutMs = args.getString("flowTimeoutMs", "10000").toLong()
        require(flowTimeoutMs in 1..60_000) { "flowTimeoutMs must be 1..60000" }
        require(!execute || (product.isNotEmpty() && maximum != null && maximum > BigDecimal.ZERO)) {
            "Execution requires product and a positive maxTotal"
        }
        val pollMs = args.getString("pollMs", "20").toLong()
        require(pollMs in 10..500) { "pollMs must be 10..500" }
        val pinDelayMs = args.getString("pinDelayMs", "300").toLong()
        require(pinDelayMs in 50..1000) { "pinDelayMs must be 50..1000" }
        val pinTimeoutMs = args.getString("pinTimeoutMs", "10000").toLong()
        require(pinTimeoutMs in 1..60_000) { "pinTimeoutMs must be 1..60000" }
        val initialDelay = args.getString("startDelayMs", "5000").toLong()
        require(initialDelay in 0..86_400_000) { "Invalid startDelayMs" }
        val delayOverride = args.getString("delaysMs")?.split(",")?.map { it.trim().toLong() }
        require(delayOverride == null || delayOverride.size == 9) {
            "delaysMs requires nine comma-separated millisecond values"
        }
        require(delayOverride == null || delayOverride.all { it in 0..60_000 }) {
            "Each delay must be 0..60000 ms"
        }

        fun report(message: String) {
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\n$message\n")
            })
        }

        val screenChanges = Semaphore(0)
        fun awaitChange(left: Long) {
            if (left > 0) screenChanges.tryAcquire(minOf(pollMs, left), TimeUnit.MILLISECONDS)
        }

        fun checkDevice() {
            check(device.isScreenOn) { "Unlock and keep the phone screen on" }
            check(device.displayRotation == 0) { "Use the original portrait orientation" }
            check(device.displayWidth == screenWidth && device.displayHeight == screenHeight) {
                "Expected ${screenWidth}x${screenHeight}; current display is ${device.displayWidth}x${device.displayHeight}"
            }
            check(device.currentPackageName == "com.shopee.th") {
                "Shopee must be the foreground app"
            }
        }

        // Read accessibility nodes locally; do not dump PIN UI or its text to logs.
        fun pinScreenReady(requireEmpty: Boolean): Boolean {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
            val ids = listOf("tvTitle", "payment_password_field", "keyboard_number_view")
            val found = mutableMapOf<String, List<AccessibilityNodeInfo>>()
            val acquired = mutableListOf(root)
            try {
                if (root.packageName?.toString() != "com.shopee.th") return false
                // Native PIN IDs are fully qualified in the supplied hierarchy.
                // Fetch just these nodes instead of traversing the entire keyboard tree.
                ids.forEach { id ->
                    val matches = root.findAccessibilityNodeInfosByViewId("com.shopee.th:id/$id")
                    acquired.addAll(matches)
                    found[id] = matches.filter {
                        it.packageName?.toString() == "com.shopee.th" && it.isVisibleToUser
                    }
                }
                if (ids.any { found[it]?.size != 1 }) return false
                val title = found.getValue("tvTitle").single()
                val field = found.getValue("payment_password_field").single()
                val keyboard = found.getValue("keyboard_number_view").single()
                if (title.text?.toString() != "Enter ShopeePay PIN" ||
                    !field.isEnabled || !field.isPassword || !keyboard.isEnabled) return false
                val bounds = Rect()
                keyboard.getBoundsInScreen(bounds)
                val config = requireNotNull(pinConfig)
                if (bounds != Rect(config.left, config.top, config.right, config.bottom)) return false
                // The supplied empty field exposes a single-space hint, not an empty string.
                if (requireEmpty) {
                    val value = field.text?.toString().orEmpty()
                    check(value.isEmpty() || (field.isShowingHintText && value.isBlank())) {
                        "PIN field is not provably empty; stopped without clearing or retrying"
                    }
                }
                return true
            } finally {
                acquired.forEach { node ->
                    @Suppress("DEPRECATION")
                    node.recycle()
                }
            }
        }

        fun awaitPinScreen() {
            val until = SystemClock.elapsedRealtime() + pinTimeoutMs
            do {
                screenChanges.drainPermits()
                checkDevice()
                if (pinScreenReady(requireEmpty = true)) return
                val left = until - SystemClock.elapsedRealtime()
                awaitChange(left)
            } while (SystemClock.elapsedRealtime() < until)
            error("PIN screen or keyboard layout not ready; no PIN touches sent")
        }

        data class Element(val id: String?, val text: String, val enabled: Boolean, val bounds: Rect)
        fun snapshot(targetIds: List<String> = emptyList()): List<Element> {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
            val result = mutableListOf<Element>()
            fun read(node: AccessibilityNodeInfo) {
                try {
                    if (node.packageName?.toString() == "com.shopee.th" && node.isVisibleToUser) {
                        val bounds = Rect().also { node.getBoundsInScreen(it) }
                        result.add(Element(node.viewIdResourceName, if (node.isPassword) "" else
                            node.text?.toString().orEmpty(), node.isEnabled, bounds))
                    }
                    for (i in 0 until node.childCount) node.getChild(i)?.let { read(it) }
                } finally {
                    @Suppress("DEPRECATION")
                    node.recycle()
                }
            }
            if (targetIds.isNotEmpty()) {
                val matches = targetIds.flatMap { id ->
                    root.findAccessibilityNodeInfosByViewId(id) +
                        root.findAccessibilityNodeInfosByViewId("com.shopee.th:id/$id")
                }
                // Some Shopee views expose nonstandard raw IDs. Fall back to traversal
                // if direct lookups do not cover all requested IDs; do not assume every ID is native.
                if (targetIds.all { id -> matches.any {
                        it.viewIdResourceName == id || it.viewIdResourceName == "com.shopee.th:id/$id"
                    } }) {
                    try {
                        matches.distinct().forEach { node ->
                            if (node.packageName?.toString() == "com.shopee.th" && node.isVisibleToUser) {
                                val bounds = Rect().also { node.getBoundsInScreen(it) }
                                result.add(Element(node.viewIdResourceName,
                                    if (node.isPassword) "" else node.text?.toString().orEmpty(),
                                    node.isEnabled, bounds))
                            }
                        }
                    } finally {
                        (matches + root).distinct().forEach {
                            @Suppress("DEPRECATION")
                            it.recycle()
                        }
                    }
                    return result
                }
                matches.distinct().forEach {
                    @Suppress("DEPRECATION")
                    it.recycle()
                }
            }
            read(root)
            return result
        }
        fun List<Element>.one(id: String): Element? {
            val matches = filter { it.id == id || it.id == "com.shopee.th:id/$id" }
            check(matches.size <= 1) { "Ambiguous UI element: $id; stopped" }
            return matches.singleOrNull()
        }
        fun Element.ready() = enabled && !bounds.isEmpty &&
            Rect(0, 0, screenWidth, screenHeight).contains(bounds)

        fun <T : Any> awaitStage(name: String, targetIds: List<String> = emptyList(), probe: (List<Element>) -> T?): T {
            val start = SystemClock.elapsedRealtime()
            val deadline = start + flowTimeoutMs
            var checkMs = 0L
            do {
                screenChanges.drainPermits()
                val checkStart = SystemClock.elapsedRealtime()
                checkDevice()
                val result = probe(snapshot(targetIds))
                checkMs += SystemClock.elapsedRealtime() - checkStart
                if (result != null) {
                    report("$name ready: elapsed=${SystemClock.elapsedRealtime() - start} ms, checks=$checkMs ms")
                    return result
                }
                val left = deadline - SystemClock.elapsedRealtime()
                awaitChange(left)
            } while (SystemClock.elapsedRealtime() < deadline)
            error("Timed out at $name; no automatic retry of actions")
        }
        fun productButton(nodes: List<Element>): Element? {
            val name = nodes.one("labelProductPageProductName") ?: return null
            if (name.text.isBlank()) return null
            check(name.text.contains(product, ignoreCase = true)) { "Product changed; stopped" }
            return nodes.one("buttonProductBuyNow")?.takeIf { it.ready() }
        }
        fun checkoutButton(nodes: List<Element>): Element? {
            val total = nodes.one("labelTotalPayment") ?: return null
            val button = nodes.one("buttonPlaceOrder")?.takeIf { it.ready() } ?: return null
            if (total.text.isBlank()) return null
            // Both checkout-specific IDs plus product text identify this destination.
            if (nodes.none { it.text.contains(product, ignoreCase = true) }) return null
            val amount = CheckoutTotal.parse(total.text) ?: error("Cannot read checkout total; stopped")
            check(amount > BigDecimal.ZERO && amount <= requireNotNull(maximum)) {
                "Checkout total outside configured budget; stopped"
            }
            return button
        }
        fun press(name: String, button: Element) {
            checkDevice()
            val start = SystemClock.elapsedRealtime()
            check(device.click(button.bounds.centerX(), button.bounds.centerY())) {
                "$name result uncertain; no automatic retry"
            }
            report("$name touch sent: ${SystemClock.elapsedRealtime() - start} ms")
        }

        if (!execute) {
            report("PREVIEW ONLY: logical stages=$steps; no touches sent")
            report("Product -> options (if present) -> verified checkout -> order -> guarded PIN")
            report("flowTimeoutMs=$flowTimeoutMs; PIN timeout=$pinTimeoutMs; pollMs=$pollMs; pinDelayMs=$pinDelayMs; no fixed purchase delays")
            return
        }

        instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                (event.packageName?.toString() == "com.shopee.th" &&
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
                if (screenChanges.availablePermits() == 0) screenChanges.release()
            }
        }
        try {
            // Prepare before the sale deadline, then acquire fresh nodes at the deadline.
            awaitStage("prepare product", listOf("labelProductPageProductName")) { nodes ->
                val name = nodes.one("labelProductPageProductName")
                if (name == null || name.text.isBlank()) null else {
                    check(name.text.contains(product, ignoreCase = true)) { "Unexpected product; stopped" }
                    true // A sale button may legitimately be disabled before the start time.
                }
            }
            checkDevice()
            val at = args.getString("at")
            val remainingMs = if (at == null) initialDelay
                else OffsetDateTime.parse(at).toInstant().toEpochMilli() - System.currentTimeMillis()
            require(remainingMs in 0..86_400_000) { "Start time passed or is more than 24h away" }
            val deadline = SystemClock.elapsedRealtime() + remainingMs
            report("ARMED: $steps touch(es), starting in $remainingMs ms. May purchase/pay.")
            while (true) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                Thread.sleep(minOf(left, 50L))
            }

            val started = SystemClock.elapsedRealtime()
            press("Buy", awaitStage("product", listOf("labelProductPageProductName", "buttonProductBuyNow"), ::productButton))
            if (steps == 1) return

            // Reuse the validated button from this read rather than immediately reading
            // the same destination again. Never carry it across another tap or wait.
            val destination = awaitStage("options or verified checkout") { nodes ->
                val total = nodes.one("labelTotalPayment")
                val options = nodes.one("buttonCartPanelSubmit")?.takeIf { it.ready() }
                check(total == null || options == null) { "Ambiguous destination; stopped" }
                when {
                    total != null -> checkoutButton(nodes)?.let { false to it }
                    options != null -> true to options
                    else -> null
                }
            }
            val checkout = if (destination.first) {
                press("Confirm options", destination.second)
                awaitStage("verified checkout", probe = ::checkoutButton)
            } else destination.second
            if (steps == 2) {
                report("Stopped at verified checkout; no order submitted")
                return
            }
            press("Place order", checkout)
            if (steps == 3) {
                report("Order touch sent once; payment outcome unconfirmed")
                return
            }
            val pinStart = SystemClock.elapsedRealtime()
            awaitPinScreen()
            report("PIN ready: ${SystemClock.elapsedRealtime() - pinStart} ms")
            requireNotNull(pinConfig).taps.take(steps - 3).forEachIndexed { index, tap ->
                checkDevice()
                check(pinScreenReady(requireEmpty = index == 0)) {
                    "PIN screen changed during entry; stopped without retry"
                }
                check(device.click(tap.x, tap.y)) { "PIN touch result uncertain; no retry" }
                if (index < steps - 4) Thread.sleep(delayOverride?.get(index + 3) ?: pinDelayMs)
            }
            report("Sequence finished in ${SystemClock.elapsedRealtime() - started} ms. Payment outcome unconfirmed; check phone before rerunning.")
        } finally {
            instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
        }
    }
}
