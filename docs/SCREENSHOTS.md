# Screenshots and the demo video

Everything in `docs/screenshots/` and `docs/media/` is made in code, without a phone or an emulator.

## Screenshots

The screenshot tests start the real app on the JVM with [Robolectric](https://robolectric.org)
(native graphics) and save each screen with [Roborazzi](https://github.com/takahirom/roborazzi):

```sh
cd android
./gradlew :app:testDebugUnitTest -Pscreenshots
```

Use a JDK 21, for example Android Studio's:
`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. The run takes a few
minutes. PNGs are written to `docs/screenshots/` at 1080 x 2400 (412 x 915 dp at 2.625), in the light
theme, plus a dark Today.

Without `-Pscreenshots`, `testDebugUnitTest` skips them and runs only the fast unit tests.

How it works:

- `ScreenshotApp` is the real `ShokuApplication`, started with a test WorkManager.
- `DemoData` seeds made-up data through the app's own DAOs and repositories: about 40 foods, 6 dishes,
  2 batches, two weeks of meals, weigh-ins, stock, 3 supplements, meal templates and a goal of 1,800 kcal
  and 110 g protein. The brands and the barcode are invented.
- `ScreenshotTest` renders `ShokuApp()` inside `ShokuTheme`, opens each screen the way a `shoku://`
  link would, and types into search fields like a user. A plain status bar and gesture handle are drawn
  around the app, because Robolectric draws no system bars.

The code is in `android/app/src/test/java/app/shoku/screenshots/`.

### No network

The tests can't send anything anywhere, even when `local.properties` points at a real sync server:

- `app/build.gradle.kts` runs every JVM test with `-Dshoku.network=off`, which turns on
  `Network.blocked` (`data/net/Network.kt`). With it on, `Backend.configured` is false, so sync is never
  scheduled and `Backend` throws before opening a connection. `AiTransports` stays in "not set up" and
  never builds an Anthropic client. Open Food Facts and the Health Connect export do nothing.
- `ScreenshotApp` refuses to start unless the switch is on and sync is off.
- `NoNetwork` replaces the JVM's `ProxySelector` with one that refuses and records every connection
  attempt (HttpURLConnection, OkHttp and plain sockets all ask it first). Each screenshot, and the
  whole class, fails if anything was recorded. `NetworkSwitchTest` checks the same in the normal run.

Robolectric itself downloads its Android framework jar from Maven Central on the first run, before
any test starts.

### Screens

| File | Screen |
|---|---|
| `01-today.png` | Today, with breakfast and lunch eaten and the rest planned |
| `02-today-dark.png` | Today in the dark theme |
| `03-add.png` | Add: AI tiles, usual items, copy a meal |
| `04-describe.png` | Add, with the Describe sheet open |
| `05-scan.png` | The amount sheet, as a scanned pack opens it |
| `06-add-search.png` | Add, searching "paneer" |
| `07-search-everything.png` | Global search for "dal" |
| `08-library-foods.png` | Library, Foods |
| `09-library-stock.png` | Library, Stock |
| `10-library-stock-search.png` | Library, Stock, searching "masala" |
| `11-batch.png` | A batch that is still in the fridge |
| `12-plan-meals.png` | Plan, Meals |
| `13-plan-supplements.png` | Plan, Supplements |
| `14-insights.png` | Insights |
| `15-settings-ai.png` | Settings: AI spending and the Claude API key |
| `16-whats-new.png` | Settings, What's new |

The camera screens themselves (barcode scanner, label and plate photos) need a camera and aren't
rendered; `05-scan.png` shows what a scan opens.

## Video

`promo/` is a [Remotion](https://www.remotion.dev) project (TypeScript, Node 22) that builds the showcase
video from the screenshots. Its public folder is `docs/screenshots/`, so render the screenshots first.

```sh
cd promo
npm install
npm run render          # docs/media/shoku-demo.mp4, 1080 x 1920, about 29 s
npm run gif             # docs/media/shoku-demo.gif, 480 px wide (needs ffmpeg)
npm run studio          # preview and edit in the browser
```

The first render downloads a headless Chrome for Remotion.

Where that browser can't run, `npm run render:ffmpeg` (`scripts/fallback_render.py`, needs Python with
Pillow, and ffmpeg) draws the same timeline frame by frame: the same scenes, captions, fonts and spring
curves, without the titles' letter-spacing and the phone's slight scale. The committed MP4 was made this
way, because the headless Chrome crashed in the sandbox it was built in. If you change the video, change
`src/Showcase.tsx` and keep the scene list in `scripts/fallback_render.py` in step.
