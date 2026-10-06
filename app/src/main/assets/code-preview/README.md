# Preview bootstrap

Only the app-specific `runtime.js` bootstrap is packaged here. React 18.3.1,
ReactDOM 18.3.1 and Babel standalone 7.26.9 load from version-pinned HTTPS CDN
URLs through HTML `<script src="...">` tags. The third-party libraries and
transpiler are not bundled in the APK. Opening React/JSX/TSX/JavaScript preview
requires internet access unless the WebView has cached the scripts; a loading
failure displays a clear error and the toolbar allows reloading.

The bootstrap supports React and ReactDOM imports, default exports, an App
component, inferred named components, standalone JSX expressions, explicit
createRoot calls, DOM scripts and console output. Other npm imports display an
unsupported-import error. Generated code runs only after an explicit preview
tap in a private WebView without a native JavaScript bridge or app file access.
