# mobile-a11y-poc

Spring Boot POC that drives an Android app through **Appium**, captures the
on-screen accessibility (UiAutomator2) tree, and runs a small **ATF-style**
rule engine against it to produce accessibility findings — mirroring the
Principle/Guideline/Issue shape of a typical web a11y scanner, adapted for
mobile.

## What it actually does

1. `POST /api/scan/mobile` starts an Appium session (launches the app from
   an `.apk`, or attaches to an already-installed `appPackage`/`appActivity`).
2. Waits `postLaunchWaitSeconds` for the app to settle.
3. Pulls `driver.getPageSource()` — the raw UiAutomator2 XML dump of every
   view on screen (class, content-desc, text, bounds, clickable, etc).
4. Parses that XML into a flat list of elements with a derived XPath per node.
5. Runs 4 rule checks against the list and returns aggregated JSON results.
6. Tears down the Appium session, always (success or failure).

### Included checks (modeled on Google ATF)

| Check ID | What it flags | WCAG ref |
|---|---|---|
| `MISSING_ACCESSIBLE_NAME` | Clickable/checkable element with no text or content-description | 4.1.2 |
| `SMALL_TOUCH_TARGET` | Clickable element smaller than `minTouchTargetDp` (default 48dp) | 2.5.5 |
| `UNLABELED_INPUT_FIELD` | `EditText` with no content-description | 3.3.2 |
| `DUPLICATE_ACCESSIBLE_LABEL` | Two+ clickable elements at different positions sharing the same label | 4.1.2 |

This is intentionally a **hand-rolled ATF-style engine**, not the real
Google ATF library — ATF (`AccessibilityChecks.enable()`) runs *inside* an
Espresso instrumentation test on-device, not from a plain JVM/Spring
process. See "Swapping in real ATF" below for how to wire that in later.

## Prerequisites

- Java 17+, Maven
- Node.js + `npm i -g appium`
- `appium driver install uiautomator2`
- Android SDK + a running emulator (or a connected real device with USB
  debugging on) — `adb devices` should show it
- An `.apk` to scan, or the package name of an app already installed on the
  device/emulator

## Running

```bash
# terminal 1
appium

# terminal 2 — make sure an emulator/device is up
adb devices

# terminal 3
cd mobile-a11y-poc
mvn spring-boot:run
```

## Triggering a scan

By APK path:

```bash
curl -X POST http://localhost:8080/api/scan/mobile \
  -H "Content-Type: application/json" \
  -d '{
    "appPath": "/absolute/path/to/app-debug.apk",
    "platformName": "Android",
    "automationName": "UiAutomator2",
    "deviceName": "emulator-5554",
    "postLaunchWaitSeconds": 4,
    "minTouchTargetDp": 48,
    "densityScale": 2.75
  }'
```

By already-installed package:

```bash
curl -X POST http://localhost:8080/api/scan/mobile \
  -H "Content-Type: application/json" \
  -d '{
    "appPackage": "com.example.myapp",
    "appActivity": ".MainActivity",
    "deviceName": "emulator-5554"
  }'
```

`densityScale` is your device's px-per-dp (`adb shell wm density` divided by
160). Get it right or the touch-target check's px→dp conversion will be off.

### Example response shape

```json
{
  "scannedAt": "2026-08-27T10:15:00Z",
  "platformName": "Android",
  "deviceName": "emulator-5554",
  "elementsScanned": 42,
  "totalIssues": 3,
  "severityCounts": { "CRITICAL": 1, "SERIOUS": 1, "MODERATE": 1, "MINOR": 0 },
  "issues": [
    {
      "checkId": "MISSING_ACCESSIBLE_NAME",
      "title": "Interactive element has no accessible name",
      "description": "...",
      "severity": "CRITICAL",
      "elementXpath": "/hierarchy[1]/android.widget.FrameLayout[1]/.../android.widget.ImageButton[2]",
      "elementClass": "android.widget.ImageButton",
      "elementLabel": "",
      "wcagCriterion": "4.1.2 Name, Role, Value"
    }
  ]
}
```

## Design notes / how this maps to a web scanner architecture

- `PageSourceParser` is the mobile equivalent of your Axe/HTMLCS JS
  injection — it's the step that turns "what's on screen" into structured
  data.
- `AccessibilityCheck` implementations are equivalent to individual Axe/HTMLCS
  rules; each is a Spring `@Component`, auto-injected as a `List<AccessibilityCheck>`
  into `MobileScanEngine`, so adding a new rule is just adding a new class —
  no wiring changes.
- `Issue` intentionally mirrors a single node in your existing
  Principle→Guideline→Criterion→Issue→Element hierarchy (`wcagCriterion` is
  the hook for mapping into Criterion, `severity` maps the way Axe
  impact levels already do). If you want, the next step is normalizing
  `ScanResult`/`Issue` here into your existing `HierarchyNode` schema and
  Mongo persistence instead of returning ad hoc JSON.
- One scan call = one screen. For a multi-screen walk, drive navigation
  (taps) between calls, or extend `MobileScanEngine` with a traversal loop
  that takes a list of navigation steps.

## Real Google ATF integration (`/api/scan/mobile/atf`)

Beyond the hand-rolled checks above, this repo also wires in the **real**
Google [Accessibility Test Framework](https://github.com/google/Accessibility-Test-Framework-for-Android)
(ATF) — the same `AccessibilityHierarchyCheck` classes used by the
"Accessibility Scanner" app and by Espresso's `AccessibilityChecks.enable()`
— so findings from `POST /api/scan/mobile/atf` are issues *actually
reported by ATF*, not a lookalike.

### Why this doesn't need Espresso or the app's source

ATF's checks operate on an `AccessibilityHierarchy`, which can be built from
a `List<AccessibilityWindowInfo>` obtained from **any** `UiAutomation`
instance (`AccessibilityHierarchyAndroid.newBuilder(windows, context)`).
That's the exact same API Appium's UiAutomator2 driver already uses
internally — so a small on-device instrumentation test can run the real
checks against whatever app is in the foreground, with zero access to that
app's source code.

### Pieces

- **`atf-harness/`** — a *separate* Android/Gradle project (ATF ships as an
  Android AAR, so it can't be added to this Maven/JVM `pom.xml`). It's a
  near-empty app module plus one `androidTest` class, `AtfScanTest`, that:
  1. Grabs `Instrumentation.getUiAutomation()` and its current windows.
  2. Builds the real `AccessibilityHierarchy` and attaches a screenshot
     (via `Parameters.putScreenCapture`) so contrast checks run for real.
  3. Runs every check in `AccessibilityCheckPreset.LATEST` (~30 checks —
     touch target size, contrast, missing speakable text, duplicate
     labels, traversal order, redundant descriptions, etc.)
  4. Writes the results as JSON to `/sdcard/atf-results.json`.
- **`src/main/java/com/poc/a11y/atf/`** — the Spring Boot side:
  - `AtfHarnessRunner` shells out to `adb shell am instrument` to trigger
    the harness, then `adb pull`s and parses its JSON.
  - `AtfResultMapper` converts raw ATF results into this project's
    existing `Issue`/`UiElement`/`Severity` model.
  - `AtfMobileScanService` + `AtfScanController` wire it into a new
    `POST /api/scan/mobile/atf` endpoint (same request shape as
    `/api/scan/mobile`).

### One important constraint

Android allows only **one active instrumentation per device**. Appium's
UiAutomator2 driver *is* an instrumentation process for the life of a
session, so `AtfMobileScanService` **stops the Appium session before**
invoking the ATF harness (a second instrumentation) — you'll see
`INSTRUMENTATION_FAILED` if that ordering isn't respected.

### One-time setup

```bash
cd atf-harness
gradle assembleDebug assembleDebugAndroidTest   # or open in Android Studio
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

### Running it

```bash
curl -X POST http://localhost:8080/api/scan/mobile/atf \
  -H "Content-Type: application/json" \
  -d '{
    "appPackage": "com.example.myapp",
    "appActivity": ".MainActivity",
    "deviceName": "emulator-5554"
  }'
```

The response is a `ScanResult` exactly like `/api/scan/mobile`, except
`checkId` values look like `ATF_TouchTargetSizeCheck` and `description`
is the literal message text ATF itself generates.

## Known POC limitations

- No contrast-ratio check yet (would need screenshot pixel sampling against
  text-element bounds — `ScreenCaptureService.captureScreenshot` is already
  wired up for this).
- Single screen per call; no built-in navigation/traversal.
- `densityScale` is a manual input rather than read from the device.
- No persistence layer — results are returned directly, not stored.
