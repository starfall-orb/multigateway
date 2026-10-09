# Plan: Native LaTeX rendering in chat messages

Status: PLANNED (not started, no code changed)

## Goal

Render LaTeX formulas in assistant messages as real typeset math instead of raw
LaTeX source text, using the JLatexMath-Android library.

## Current state

- `ui/chat/MarkdownRenderer.kt`: paragraphs classified by `LatexDetector` go to
  `RenderLatexBlock`.
- `ui/chat/MarkdownLatexBlock.kt`: `RenderLatexBlock` shows the raw LaTeX string
  in a serif `Text` inside a card with a copy button. No math layout happens.
- `ui/chat/LatexDetector.kt`: heuristic detection (`$...$`, `$$...$$`, `\(...\)`,
  `\[...\]`), with USD price-range exclusion. No unit tests.
- `app/build.gradle.kts`: no math library. Markdown parsing/rendering uses
  `com.mikepenz:multiplatform-markdown-renderer-m3:0.45.0`.
- Working tree has uncommitted changes in many files. Do not touch files that are
  already modified without first checking with the owner.

## Decision

Use `ru.noties:jlatexmath-android` directly, not through Markwon.

Reasons:
- Markwon `ext-latex` 4.6.2 (latest, last updated 2021) pulls in `commonmark 0.13.0`,
  which conflicts with the app's `commonmark 0.24.0`.
- Markwon is View-based. The app renders Markdown with Compose, so it would need
  an extra View bridge for the whole message.
- JLatexMath renders formulas to a Drawable/Bitmap, so it can be used from Compose
  with a small wrapper and no WebView.

## Open risks to verify before coding

1. Exact JLatexMath-Android API for version 0.2.0 (builder, text size, color,
   and how to get the drawable's intrinsic size). Confirm against the AAR
   classes before writing code.
2. Maven coordinate and version: `ru.noties:jlatexmath-android:0.2.0`. Confirm it
   is resolvable from the repositories used by the project (Google/Maven Central).
3. Rendering cost in long chats (many formulas per message). Plan to cache drawables
   keyed by (formula, text size, color, dark/light theme).
4. Unsupported LaTeX commands fall back to showing the raw source instead of crashing.
5. Build environment: the sandbox has no JDK or Android SDK, so the build cannot
   be verified there. Verification must run on a machine with Android SDK 34/37
   (Gradle 9.3.1 wrapper).

## Implementation steps

1. Add dependency `implementation("ru.noties:jlatexmath-android:0.2.0")` to
   `app/build.gradle.kts`.
2. Add `ui/chat/LatexFormulaRenderer.kt`:
   - A Composable that takes a formula string, text style and display/inline mode.
   - Builds the JLatexMath drawable on a background dispatcher, then shows it via
     a Compose `Image`/`Canvas` (or `AndroidView` if the API needs a View).
   - Shows raw source text while loading or on error.
   - Uses a small in-memory LRU cache keyed by formula, size, color and theme.
3. Update `MarkdownLatexBlock.kt`: replace the raw serif `Text` with the new
   renderer. Keep the card and the copy button (copy the raw formula text).
4. Decide how mixed prose + formula paragraphs are handled. Options:
   a. Split each paragraph into text and formula segments, then lay them out in a
      Compose `Row`/`FlowRow`.
   b. Keep the whole paragraph as Markdown and render only block `$$...$$`
      formulas with the new renderer.
   Preferred: option (b) for the first version, to keep scope small.
5. Tighten `LatexDetector`:
   - In `ON` mode, do not treat any `$` as math. Require a matching pair that
     looks like math.
   - Add unit tests for: `"giá $10 và $20"`, `"giá 10$"`, `"$x^2$"`,
     `"$snake_case$"`, `"$$\nE=mc^2\n$$"`, and prose mixed with formulas.
6. Add a unit test for the cache key logic and for the fallback-to-raw-text path.
7. Manual check on device: light and dark theme, long chat scroll, streaming
   message that ends mid-formula, unsupported command.

## Out of scope for this plan

- Replacing the Markdown renderer.
- KaTeX/MathJax in WebView.
- Full inline math layout inside prose paragraphs (see step 4).
