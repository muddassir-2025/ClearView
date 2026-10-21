# Browser script regression tests

From the repository root:

```sh
npm ci --prefix scripts/browser-tests
npm test --prefix scripts/browser-tests
```

Local runs use installed Google Chrome. To use Playwright Chromium instead:

```sh
cd scripts/browser-tests
npx playwright install chromium
CI=1 npm test
```

These tests render controlled mobile YouTube/Instagram fixtures in a real
Chromium engine. The Android bridge, network responses, and ad media state are
mocked; no credentials, real video playback, Android SDK, or emulator are used.
They test script behavior, layout bounds, navigation, and policy changes, not
YouTube/Instagram server compatibility or native persistence. Android acceptance
steps and remaining limitations are in `docs/BROWSER_FIXES.md`.

To run selected tests against another script revision, set `CLEARVIEW_SCRIPT`
to its absolute file path. The early CSS asset remains the current revision.
