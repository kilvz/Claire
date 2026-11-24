# MCP service brand logo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the generic category icons on the MCP catalog/list/management pages with real brand outlines + official brand colors.

**Architecture:** A Python generation script fetches each preset's `path d` + brand color from a pinned version of a CC0 brand icon set and produces `assets/mcp/mcp-logos.json` (`presetId → {d, color}`, kept separate from catalog.json). At the app layer, `McpLogos` parses that JSON and uses Compose `PathParser` to lazily turn paths into `ImageVector`s (cached); the three pages use the brand logo when `logo(id)` hits (light tile + self-coloring), and otherwise fall back to the existing icon.

**Tech Stack:** Python 3 (generation script), kotlinx.serialization (parsing), Compose `PathParser`/`ImageVector` (rendering).

**Spec:** `docs/design/specs/2026-11-22-mcp-service-logos-design.md`

## Global Constraints

- Brands own their own marks; the icon set's source and license attribution go only into `licenses/THIRD_PARTY_NOTICES.md`, not into code/comments/commit messages.
- Logos are bundled into assets and never fetched over the network at runtime (usable offline, no browsing-history leak).
- Do not introduce an image-loading library; the icon slot stays an `ImageVector`.
- Add no user-visible copy (no 12-language translation burden); do not touch connection logic, secrets or `McpPreset` model fields.
- Use official brand colors as they are; put logos on a fixed light tile (light in both modes), so dark brands stay readable in dark mode.
- Fallback: when `logo(id)` is null (stdio packages with no slug, custom servers), fall back to the existing category icon / `MikaIcons.Link`.

## Review Focus

1. **Near-white brand colors wash out on the light tile** → the generator clamps over-bright hex values down to a lower bound to guarantee contrast (pinned by a test).
2. **A presetId with no matching slug** → `logo(id)` returns null, the page falls back to the category icon, and nothing crashes (the test pins `logo(unknown) == null`).
3. **mcp-logos.json corrupt / missing fields / illegal hex** → lenient parsing skips the bad entries and returns the usable subset without crashing (pinned by a test).
4. **A custom server (presetId = null) or a saved server whose presetId has no logo** → the list/management page falls back to `MikaIcons.Link` (the test pins the selection logic).
5. **Readability in dark mode** → the tile is always light and icons self-color (a design decision, no runtime branch needed).

---

### Task 1: The logo generation script and `mcp-logos.json`

**Files:**
- Create: `tools/mcp/build_logos.py`
- Create: `tools/mcp/tests/test_build_logos.py`
- Create (generated, committed): `app/src/main/assets/mcp/mcp-logos.json`

**Interfaces:**
- Consumes: `app/src/main/assets/mcp/catalog.json` (reads `presets[].id` and `presets[].name`).
- Produces: `mcp-logos.json` with the shape `{"formatVersion":1,"logos":{"<presetId>":{"d":"<svg path d>","color":"<6-digit hex>"}}}`.

- [ ] **Step 1: Write the failing tests** — `tools/mcp/tests/test_build_logos.py` (`python3 -I -m unittest discover -s tools/mcp/tests -p 'test_build_logos.py'`)

Assert with fixed fixtures:
- `slug_for("openai") == "openai"`, `slug_for("google-gemini") == "googlegemini"` (the default drops hyphens and lowercases), `slug_for("brave-search") == "brave"` (covered by the override table).
- `parse_svg('<svg><path d="M0 0"/></svg>') == "M0 0"`; with multiple `<path>`s take the first `d`.
- `clamp_color("FFFFFF")` returns a darker value (luminance clamped to the lower bound), `clamp_color("181717")` is returned unchanged.
- End to end: given a fake catalog with only `github`/`filesystem`, `generate(...)` produces `{"github": {...}}` and contains no `filesystem` (skipped for having no slug).

- [ ] **Step 2: Run and confirm failure**

Run: `python3 -I -m unittest discover -s tools/mcp/tests -p 'test_build_logos.py' -v`
Expected: FAIL (`build_logos` undefined / ImportError).

- [ ] **Step 3: Implement `tools/mcp/build_logos.py`**

Contents (in the style of `build_catalog.py`):
- An `ICON_SET_VERSION` constant (pinned to a recent version; when implementing, confirm it exists at `unpkg.com/simple-icons@<ver>/` and write it into the script).
- `SLUG_OVERRIDES: dict[str, str]` (`presetId → icon-set slug`) curated table; the default rule is `presetId.replace("-", "").lowercase()`.
- Download `_data/simple-icons.json` (pinned version) and build `slug → hex` (the slug comes from the title via "drop non-alphanumerics, lowercase", including the `old` entries of aliases); resolve the slug for each preset, and on a hit download `icons/<slug>.svg` and take its `d`.
- `clamp_color(hex)`: if the luminance (e.g. `0.2126 R + 0.7152 G + 0.0722 B`) exceeds the threshold, darken it proportionally.
- `generate()` writes `app/src/main/assets/mcp/mcp-logos.json`; `--check` compares against the committed file and exits 1 on a mismatch.

- [ ] **Step 4: Run and confirm it passes + generate the artifact**

Run: `python3 -I -m unittest discover -s tools/mcp/tests -p 'test_build_logos.py' -v` → PASS; then `python3 -I tools/mcp/build_logos.py` to generate `mcp-logos.json`; `python3 -I tools/mcp/build_logos.py --check` → `ok`. Spot-check that `github`'s `color` in `mcp-logos.json` is `181717` and that its `d` starts with `M`.

- [ ] **Step 5: Commit**

```bash
git add tools/mcp/build_logos.py tools/mcp/tests/test_build_logos.py app/src/main/assets/mcp/mcp-logos.json
git commit -m "feat(mcp): generate the service brand logo asset (mcp-logos.json)"
```

---

### Task 2: The `McpLogos` model + parsing + ImageVector building

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpLogos.kt`
- Test: `app/src/test/java/com/wangzan101/mika/mcp/ui/McpLogosTest.kt`

**Interfaces:**
- Consumes: the `mcp/mcp-logos.json` asset (shape from Task 1).
- Produces:
  - `data class McpLogoData(val d: String, val color: String)`
  - `class McpLogos(val formatVersion: Int, val logos: Map<String, McpLogoData>)`, with the companion `parse(text: String): McpLogos` (lenient, skips bad entries) and `load(context: Context): McpLogos`.
  - `fun McpLogos.logo(id: String): ImageVector?` (`PathParser` builds a 24×24 `ImageVector`, `fill = SolidColor(resolved color)`, cached internally in a `mutableMapOf`; unknown id / illegal color returns null).

- [ ] **Step 1: Write the failing tests** — `McpLogosTest.kt`

- Parse round trip: `McpLogos.parse("""{"formatVersion":1,"logos":{"github":{"d":"M0 0","color":"181717"}}}""").logos["github"] == McpLogoData("M0 0","181717")`.
- Lenient parsing: given an input containing one `{"d":"","color":"181717"}` and one `{"d":"M0 0","color":"zzz"}`, both are skipped after parsing and the rest are kept.
- `logo("github")` is non-null and `width == 24.dp && height == 24.dp && viewportWidth == 24f`; `logo("filesystem") == null` (unknown id).
- Color parsing: `color("181717") == Color(0xFF181717)`, and `color("zzzzzz")` either throws or is treated as null (so that `logo` returns null).

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests com.wangzan101.mika.mcp.ui.McpLogosTest`
Expected: FAIL (`McpLogos` / `McpLogoData` undefined).

- [ ] **Step 3: Implement `McpLogos.kt`**

`logo(id)` uses `ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply { addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(color)) }.build()`; `color(hex)` parses `#RRGGBB`/`RRGGBB` by hand → `Color(0xFF000000L or hex.toLong(16))`, throwing `IllegalArgumentException` for a hex that is not 6 digits. `load(context)` reads `context.assets.open("mcp/mcp-logos.json")`, and on an exception (file missing/corrupt) returns an empty `McpLogos`.

- [ ] **Step 4: Run and confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.wangzan101.mika.mcp.ui.McpLogosTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/mcp/ui/McpLogos.kt app/src/test/java/com/wangzan101/mika/mcp/ui/McpLogosTest.kt
git commit -m "feat(mcp): McpLogos parses and lazily loads brand logos as ImageVectors"
```

---

### Task 3: `IconTile` / `ListRow` brand self-coloring support

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/ui/components/Rows.kt` (`IconTile`, `ListRow`)

**Interfaces:**
- Produces:
  - `IconTile(icon: ImageVector, tone: TileTone = TileTone.Neutral, brand: Boolean = false)`: with `brand=true`, `bg = BrandTileBg` (fixed light `Color(0xFFF4F4F5)`) and `fg = Color.Unspecified` (render the vector's own colors); otherwise keep `tone.colors(...)`.
  - `ListRow(icon: ImageVector?, ..., brand: Boolean = false, ...)`: passes `brand` through to `IconTile`.

- [ ] **Step 1: Modify `IconTile`** to add the `brand` parameter and the `BrandTileBg` constant (semantics above).

- [ ] **Step 2: Modify `ListRow`** to add the `brand: Boolean = false` parameter and pass it through (`if (icon != null) IconTile(icon, tone, brand)`).

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (this task has no standalone JVM test; the behavior is verified on the pages in Task 4/5 and on a real device).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/ui/components/Rows.kt
git commit -m "feat(ui): IconTile/ListRow support self-coloring brand logos on a light tile"
```

---

### Task 4: Wire the logo into the three pages

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpCatalogScreen.kt` (the preset row around line 170)
- Modify: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpServersScreen.kt` (the server row at line 111)
- Modify: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpServerManageScreen.kt` (the status row at line 94)

**Interfaces:**
- Consumes: `McpLogos.load(context)`, `McpLogos.logo(id)`, `ListRow(..., brand = ...)`.
- Rule (the same in all three places): `val logo = presetIdOrNull?.let { logos.logo(it) }`; `ListRow(icon = logo ?: fallbackIcon, brand = logo != null, ...)`.
  - Catalog: `fallbackIcon = McpCategory.icon(McpCategory.of(p))`, with key `p.id`.
  - List: `presetIdOrNull = server.presetId`, `fallbackIcon = MikaIcons.Link`.
  - Management page: `presetIdOrNull = config.presetId`, `fallbackIcon = MikaIcons.Link`.
- Each screen has `val logos = remember(context) { McpLogos.load(context) }` (`context = LocalContext.current`).

- [ ] **Step 1: Wire up the catalog page** (`McpCatalogScreen`).

- [ ] **Step 2: Wire up the list page** (`McpServerList`).

- [ ] **Step 3: Wire up the management page** (`McpServerManageScreen`).

- [ ] **Step 4: Compile + existing tests**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests com.wangzan101.mika.settings.McpCatalogAssetsTest`
Expected: BUILD SUCCESSFUL (the existing catalog tests do not regress).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/mcp/ui/McpCatalogScreen.kt app/src/main/java/com/wangzan101/mika/mcp/ui/McpServersScreen.kt app/src/main/java/com/wangzan101/mika/mcp/ui/McpServerManageScreen.kt
git commit -m "feat(mcp): show service brand logos on the catalog/list/management pages"
```

---

### Task 5: Attribution notices + on-device verification

**Files:**
- Modify: `licenses/THIRD_PARTY_NOTICES.md`

- [ ] **Step 1: Append the attribution entry** (Source / License: CC0 / Usage: the brand outline glyphs in `mcp-logos.json`; note that brand trademarks belong to their respective owners).

- [ ] **Step 2: Build the APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify on a real device**

Install on the test device and go to Settings › MCP servers › Service catalog, then confirm: ① GitHub/Slack/Notion and others show their brand outline + brand color (light tile); ② a stdio preset (such as Filesystem) has no logo and falls back to the Terminal/category icon; ③ switch to dark mode and a dark brand (GitHub) is still readable on the light tile; ④ in the server list, a connected GitHub (if any) shows the brand logo and a custom server shows Link.

- [ ] **Step 4: Commit**

```bash
git add licenses/THIRD_PARTY_NOTICES.md
git commit -m "docs: third-party attribution for brand logos"
```

---

## Self-Review

- **Spec coverage**: brand outline + brand color (Task 1/2), light tile with self-coloring (Task 3), wiring into the three pages (Task 4), attribution (Task 5), fallback rules (the three places in Task 4 + the `logo==null` test in Task 2), no network access / no image library / no model-field changes (Global Constraints).
- **Type consistency**: `McpLogoData(d, color)` and `McpLogos.parse/load/logo(id): ImageVector?` are defined in Task 2 and consumed in Task 4 as `logo(it)`, with consistent naming.
- **Review Focus**: each of the 5 items is pinned to Task 1 (clamp_color), Task 2 (unknown/corrupt/illegal hex), Task 4 (presetId null falls back to Link) and the Task 3 design (light tile).
