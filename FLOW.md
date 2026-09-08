# Per-device configuration

Use `config/device.local.json` for the existing phone. Its original PIN coordinates
have been moved out of Kotlin. For another device copy `config/device.example.json`
to `config/another.local.json` and fill in the serial (`adb devices`), actual display
size, keyboard bounds and six PIN tap points in entry order. The example contains
no real PIN coordinates and will fail validation until configured.

All `config/*.local.json` files are ignored by Git. The migrated local file has
owner-only permissions. These files encode the PIN through coordinates: do not
share them. JSON is configuration only and is not executed as shell code. The
launcher sends settings as instrumentation arguments; these are not encrypted
secret storage and may be visible to local process/ADB diagnostics. It never
prints the command or the coordinates.

Build/install **both** APKs after this change:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Run from the repository directory (no Appium needed):

```sh
# Default: preview only, no touches
python3 scripts/run_flow.py

# Real taps up to checkout, no order submission
python3 scripts/run_flow.py --execute --steps 2 --product "OVA" --max-total 500 --start-delay-ms 5000
```

Use `--config config/another.local.json` to choose a device. The launcher targets
its configured serial. Use `--at "YYYY-MM-DDTHH:MM:SS+07:00"` instead of
`--start-delay-ms` for a real future sale time. `--steps 9 --execute` submits an
order and enters PIN, which may pay immediately. `--pin-delay-ms` overrides the
file value. Kotlin no longer contains fallback PIN taps: direct ADB runs for
stages 4–9 require pinConfig; use the launcher to avoid placing coordinates in
shell history. Stages 1–3 use current UI button bounds, not stored coordinates.

# Screen-driven purchase flow

`PointerSequenceTest` runs locally on Android. It polls visible accessibility nodes
with an event-assisted wait capped at 20 ms by default between unsuccessful checks; actual checks and taps can take
longer. No Appium connection or XML dump is used on the purchase path.

Instrumentation arguments:

- `execute=false` (default): preview without touching the phone.
- `execute=true`: perform the requested stages. Requires `product` (distinctive
  product-title substring) and `maxTotal` (positive decimal THB budget).
- `steps=1` (default): tap Buy only.
- `steps=2`: handle options if present, validate checkout, stop before ordering.
- `steps=3`: also submit the order once.
- `steps=9`: also enter all six configured PIN coordinates; this may pay.
- `steps=4..8`: partial PIN entry, for deliberate debugging only. Check/clear the
  phone manually before another run; the runner will not clear or retry a PIN.
- `at`: future ISO timestamp with timezone, or `startDelayMs=5000` by default
  measured after product preparation. Product identity is checked before arming;
  identity and Buy readiness are checked again at the deadline.
- `pollMs=20`: pause between unsuccessful screen checks (10–500 ms).
- `pinDelayMs=300`: pause between PIN taps (50–1000 ms). Can be reduced for
  device testing, e.g. 100 ms; acceptance at that speed is not verified. Explicit
  `delaysMs` entries take precedence.
- `flowTimeoutMs=10000`, `pinTimeoutMs=10000`: maximum readiness waits.
- `delaysMs`: optional nine comma-separated delays; only entries 4–8 affect
  spacing between PIN taps. Entries 1–3 no longer delay purchase transitions.

Example: stop at checkout (no order or PIN submission):

```sh
adb shell am instrument -w \
  -e class com.hym.pointer.PointerSequenceTest \
  -e execute true -e steps 2 \
  -e product 'YOUR DISTINCTIVE PRODUCT TITLE' -e maxTotal 175 \
  -e startDelayMs 0 \
  com.hym.pointer.test/androidx.test.runner.AndroidJUnitRunner
```

Install the app and test APK beforehand. The phone must be unlocked, SP in
foreground, on the intended product page, with the configured display size in portrait. Select the intended
variation and quantity in advance. The runner verifies product text and maximum
checkout total, not variation, quantity, address or payment method. Selectors come
from the supplied Python scripts and still need verification against the current
phone UI. The PIN keyboard bounds come from the supplied XML; individual number
labels are not exposed, so changed/randomized keys cannot be detected.

Logs report readiness elapsed time, time spent checking, and tap duration without
PIN values or coordinates. Timeouts/ambiguous elements/invalid totals halt the run;
action taps are never automatically retried. No successful-payment selector has
been supplied: completion means touches sent, not verified payment. Check order
status manually before rerunning. This implementation has not been benchmarked on
a live sale. Readiness waits wake on relevant accessibility window/content changes, with polling as a fallback. The listener is removed on completion or failure.

Destination detection now returns the checked button from the same snapshot,
avoiding an extra options read and an extra checkout read on the direct path.
No measured speedup is claimed; compare readiness and tap logs on the same phone.

Product preparation and Buy readiness use targeted ID lookups with a full-tree
fallback when requested IDs cannot all be resolved. PIN checks fetch only the
three native PIN nodes. Checkout still traverses visible nodes to validate product
text and total together. Events only wake checks; they never authorize a tap.
