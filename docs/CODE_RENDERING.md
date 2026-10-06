# Code rendering

Preferences → Rendering provides Word Wrap and HTML / React / JS Preview.
Word Wrap defaults to Off and uses the VS Code modes:

- Off: horizontal scrolling; individual blocks expose a RAM-only wrap toggle.
- Wrap by viewport: wrap at the available text width.
- Wrap by column: wrap at the configured number of monospace columns (default 80),
  with horizontal scrolling when that width exceeds the viewport.
- Bounded: wrap at the smaller of the viewport and the column width.

The column field is visible for column and bounded modes. Positive integer
values are persisted; invalid input does not replace the last valid setting.
These settings currently apply to Markdown code blocks, including Model JSON.
Syntax highlighting uses native annotated text, recognizes common language
aliases and preserves the exact source for selection and copying.

The preview toggle controls the toolbar button for HTML, JavaScript, JSX/React,
TypeScript and TSX. Tapping it opens a private WebView Activity. React, ReactDOM
and Babel load from pinned HTTPS CDN URLs via HTML script tags; they are not
bundled in the app. React/JSX/TSX/JavaScript previews require internet access
unless the scripts are already cached by WebView. Loading failures display an
error and can be retried with Reload. HTML resources and browser scripts can
also require a network connection. Arbitrary
npm project imports are not bundled and display an explicit unsupported-import
error. JavaScript console output and compilation/runtime errors appear in the
preview. Generated documents use temporary cache files to avoid Intent size
limits; they are deleted when the preview closes.

Validation:

```sh
./gradlew :app:testDebugUnitTest \
  --tests org.starfall.multigateway.CodeRenderingTest \
  --tests org.starfall.multigateway.CodeRenderingPreferencesTest
./gradlew :app:compileDebugAndroidTestKotlin
node scripts/check-code-preview.cjs
```

The browser check requires `playwright-core` available to Node, Chromium,
`curl`, and internet access to download the CDN scripts.
`PLAYWRIGHT_MODULE` can point to the module directory and
`PLAYWRIGHT_CHROMIUM_EXECUTABLE` can point to an installed Chromium binary.
Android instrumentation coverage is in `CodeRenderingBehaviorTest`; executing
it requires a connected emulator or device.
