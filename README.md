# [PointerRunner](https://github.com/u6587051/PointerRunner)

PointerRunner runs a screen-driven purchase flow for the SPTH Android app
(`com.SP.th`). Kotlin instrumentation checks the current screen and taps ready
buttons on the phone. A Python launcher loads device-specific settings and starts
the test through ADB. Appium is not required to run the flow.

The runner supports product verification, optional product options, checkout total
validation, scheduled starts, and coordinate-based SPPay PIN entry.
**Executing stage 3 submits a real order; stage 9 may authorize a real payment.**
Payment success is not automatically verified, and flash-sale availability or
speed is not guaranteed.

## Requirements

- Android Studio with the SDK required by this project's Gradle configuration,
  and a compatible Gradle JDK (Android Studio's bundled JDK is a starting point).
- Android SDK Platform Tools (`adb`) available on your PATH.
- Python 3.8 or newer; the launcher uses only the standard library.
- An Android phone running Android 8.0/API 26 or newer, with USB debugging enabled.
- SPTH installed and signed in on the phone.

Open this repository in Android Studio and let Gradle sync. Configure the Android
SDK location through Android Studio or your local `local.properties` file. Run the
following commands from the repository root, regardless of its folder name.

On macOS, a typical ADB PATH setup is:

```sh
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
adb devices
```

Connect the phone and accept its USB debugging prompt. Its status should be
`device`, not `unauthorized`.

## 1. Configure your phone

Create a private configuration if you do not already have one:

```sh
cp -n config/device.example.json config/device.local.json
chmod 600 config/device.local.json
```

Edit `config/device.local.json`:

| Field | Meaning |
| --- | --- |
| `serial` | Phone serial reported by `adb devices`. |
| `screenWidth`, `screenHeight` | Actual display dimensions used by the runner in portrait orientation. |
| `keyboardBounds` | SPPay numeric keyboard bounds as `[left, top, right, bottom]`. |
| `pinTaps` | Six `[x, y]` points in PIN entry order, configured for this phone. |
| `pinDelayMs` | Pause between PIN taps; defaults to 300 ms, allowed range 50–1000 ms. |

The example intentionally has empty PIN points and invalid placeholder keyboard
bounds. It will not run until you configure it. The Python launcher validates the
whole file even in preview or checkout-only mode.

Use the phone's UI hierarchy to inspect
`com.SP.th:id/keyboard_number_view` and obtain its bounds. Determine the six
points from the actual keyboard displayed on that phone. The keyboard exposes one
view rather than individual digit labels: the runner can verify its bounds but
cannot detect shuffled digits. Do not reuse another phone's coordinates blindly.

Purchase buttons use bounds read from the current UI, so there are no fixed
purchase-button coordinates to configure.

For another phone, create `config/another.local.json` and select it with
`--config config/another.local.json`. The launcher targets the configured serial.
With multiple phones connected, use `adb -s SERIAL ...` for installation as well.

## 2. Build and install

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Both installation commands should report `Success`. Install both APKs initially
and after rebuilding changed code. Editing device JSON does not require rebuilding.

The displayed app/project name is **PointerRunner**. The Android application ID remains
`com.hym.pointer`, and the test package remains `com.hym.pointer.test`.

## 3. Preview without touches

```sh
python3 scripts/run_flow.py
```

Expect `PREVIEW ONLY`. This verifies local configuration and launches the test,
but does not inspect the live purchase flow or send touches. Omitting `--execute`
always selects preview mode.

## 4. Test up to checkout

Before execution:

1. Close active Appium/Inspector sessions.
2. Open the intended SP product page and select the variation and quantity.
3. Verify the address and payment method yourself.
4. Keep the phone unlocked, in portrait, with SP in the foreground.

Replace the product substring and budget with your intended values:

```sh
python3 scripts/run_flow.py \
  --execute \
  --steps 2 \
  --product "YOUR DISTINCTIVE PRODUCT TITLE" \
  --max-total 500 \
  --start-delay-ms 5000
```

The runner checks the product, waits five seconds, taps Buy, handles an options
panel if present, and checks the product text and total at checkout. It should end
with:

```text
Stopped at verified checkout; no order submitted
```

The product match is a case-insensitive substring. Choose a distinctive title
fragment. `--max-total` is the maximum **final checkout total in THB**, not a target
price or a quantity. The total must be positive and no higher than this value.

## 5. Schedule a flash-sale start

Use `--at` instead of `--start-delay-ms`. Replace the timestamp placeholder with an
actual future date and time, including its timezone:

```sh
python3 scripts/run_flow.py \
  --execute \
  --steps 2 \
  --product "YOUR DISTINCTIVE PRODUCT TITLE" \
  --max-total 500 \
  --at "YYYY-MM-DDT12:00:00+07:00"
```

`+07:00` is Thailand time. The target must be in the future and within 24 hours of
the phone's clock after product preparation. Enable automatic date/time on the
phone; inspect its clock with `adb shell date`.

Start the launcher ahead of the sale and wait for `ARMED`. Leave the intended
product page open. At the deadline, the runner checks fresh product/button state
before tapping. The target is when readiness checking starts, not a guarantee of
when SP's server receives an order. `--at` and `--start-delay-ms` cannot be used
together.

## 6. Run the full purchase flow

After verifying checkout behavior and your phone's PIN coordinates, use
`--steps 9`. **This submits an order and enters the configured PIN, which may pay
immediately.** Return to the intended product page before starting.

```sh
python3 scripts/run_flow.py \
  --execute \
  --steps 9 \
  --product "YOUR DISTINCTIVE PRODUCT TITLE" \
  --max-total 500 \
  --start-delay-ms 5000
```

You can replace the delay with `--at` for a scheduled purchase. Check order/payment
status on the phone afterward. A completed test means the configured touches were
sent; it does not mean payment succeeded. If execution stops after ordering or PIN
entry, check order history before running again. Actions are not retried automatically.

## Launcher options

```sh
python3 scripts/run_flow.py --help
```

| Option | Default | Purpose |
| --- | --- | --- |
| `--config` | `config/device.local.json` in the repository | Device settings file. |
| `--execute` | Off | Enable real touches. |
| `--steps` | `2` | Last logical stage to execute; see below. |
| `--product` | Empty | Required for execution; distinctive product-title substring. |
| `--max-total` | Empty | Required for execution; positive decimal THB ceiling. |
| `--at` | Unset | Scheduled ISO timestamp with timezone. |
| `--start-delay-ms` | Runner default: `5000` | Delay after initial product verification. |
| `--poll-ms` | `20` | Fallback readiness wait, 10–500 ms. UI events can wake it earlier. |
| `--pin-delay-ms` | Device file value | Override PIN tap spacing, 50–1000 ms. |

| Stage | Behavior |
| --- | --- |
| `1` | Tap Buy only. |
| `2` | Handle options if present, verify checkout, stop before ordering. |
| `3` | Also submit the order once; no PIN entry. |
| `4`–`8` | Enter only part of the PIN; intended for deliberate debugging. |
| `9` | Submit the order and enter all six PIN taps. |

Stages are logical steps, not a guaranteed number of purchase taps: some products
open checkout directly. The raw instrumentation default is stage 1; the Python
launcher explicitly defaults to stage 2. Prefer the launcher for device settings.

## Performance and logs

Product checks use targeted ID lookups with a full-tree fallback. Checkout reads
visible nodes to validate product text and total. PIN checks fetch three native
nodes. Relevant accessibility events wake readiness checks, with polling as a
fallback. Events never replace screen validation.

Logs include readiness `elapsed` and `checks` times, plus touch duration.
`checks` includes device checks and UI reading; it is not a pure network metric.
The configured poll interval excludes the time spent reading the UI and tapping.

The flow and PIN readiness timeouts default to 10 seconds each. They are maximum
waits, not mandatory delays. Advanced raw instrumentation arguments are documented
in [FLOW.md](FLOW.md); the Python launcher does not expose every argument.

Reducing `--pin-delay-ms` to 100 can reduce the configured pauses, but reliable PIN
acceptance at that speed must be tested on the actual phone. No live flash-sale
benchmark or successful-payment verification is included.

## Troubleshooting

| Message or symptom | What to check |
| --- | --- |
| `adb not found` | Add Android SDK Platform Tools to PATH. |
| `unauthorized` in `adb devices` | Unlock the phone and accept USB debugging authorization. |
| `Cannot load device configuration` | Check JSON syntax, serial, dimensions, keyboard bounds and exactly six valid PIN points. |
| `PIN entry requires per-device pinConfig` | Use `scripts/run_flow.py`; raw ADB commands do not load the JSON automatically. |
| `Start time passed or is more than 24h away` | Check the phone clock and choose a future target within 24 hours, or use a start delay. |
| Display/orientation mismatch | Match the configured dimensions and use portrait orientation. |
| Timeout at a screen stage | Check the current screen and selectors; SP UI changes may require code updates. |
| Checkout total outside budget | Verify the final amount, including delivery, and the intended budget. |
| PIN screen/layout not ready | Check keyboard bounds and that the SPPay PIN screen matches the expected UI. |
| PIN field not provably empty | Inspect the phone manually; the runner will not clear or retry partially entered PINs. |

For multiline shell commands, a continuation backslash must be the last character
on the line, with no trailing spaces. Always inspect instrumentation output for
failures; the Python launcher returns ADB's exit code, which alone does not prove
that the test or payment succeeded.

## Private configuration

`config/*.local.json`, `local.properties`, and build output are ignored by Git.
Only commit the placeholder `config/device.example.json`. PIN coordinates encode
a credential: keep local files private and do not include them in screenshots or
support logs. On macOS/Linux, apply `chmod 600` to new private configuration files.

The launcher does not print the PIN command, but settings are passed through ADB
instrumentation arguments and may be visible in local process or diagnostic data.
This is not encrypted secret storage. `.gitignore` does not remove files already
tracked or committed; review staged files before publishing.

## Development checks

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

These commands run local unit tests and build APKs without executing the purchase
flow on a phone. They do not establish live UI compatibility or payment success.
The runner currently does not validate variation, quantity, address, payment method,
or individual PIN digit labels.
