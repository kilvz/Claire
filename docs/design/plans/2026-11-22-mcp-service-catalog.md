# MCP service catalog and guided setup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users pick a service from the complete built-in MCP catalog and then connect by only authorizing or filling in the necessary fields, while keeping full custom setup.

**Architecture:** Describe connections and dynamic forms with versioned, secret-free preset data; parsing, template resolution and validation live in pure Kotlin core, while credentials and connection orchestration stay in the App's `McpServerStore`. Preset and custom configurations ultimately share the existing MCP client, Agent tool assembly and task resource lifecycle; stdio gains an explicit environment and confirmed package installation.

**Tech Stack:** Kotlin, kotlinx.serialization, OkHttp, Compose, the existing Linux sandbox, JUnit/MockWebServer, a local HTML prototype.

**Spec:** `docs/design/specs/2026-11-22-mcp-service-catalog-design.md`

## Global Constraints

- The first version ships the complete catalog directly, targeting 200–300 usable presets; do not limit the launch by service batches, and do not write dedicated Kotlin branches for a single service.
- Remote presets use only the service's official endpoint; stdio accepts only official packages, or packages whose source is verifiable and which have been reviewed; community-hosted proxies go only through user customization.
- All secrets are stored encrypted; secrets must not enter the catalog, ordinary configuration, URLs, argv or logs.
- Before a stdio package is downloaded, show the package name, source and version, and install and cache it uniformly after the user explicitly confirms; never download silently.
- Support multiple secrets and multiple ordinary required fields; the UI shows only the items declared by the current preset.
- The tool catalog and schema are discovered only from a real MCP connection and are not written into presets.
- Preset and custom services reuse the existing task connection budget, failure isolation and `ResourceScope` cleanup.
- New UI first completes a local HTML prototype and obtains the user's confirmation before any App UI is modified.
- All new user-visible strings cover every UI language the project already has; service-proprietary names need not be translated.
- Do not modify inbound `McpRpc`, `McpTaskTools` or the computer-connection permission policy; do not change the version number (not a release).

## Review Focus

- A malicious or corrupt preset trying to put secrets into the URL/argv, override reserved headers or send to a non-official address: the parser and resolver must reject it, see `rejectsSecretExfiltrationAndUnsupportedTargets` in Task 2.
- Multi-secret configurations must not cross wires or leave residue after editing, backup, restore and deletion: see `fieldSecretsRoundTripMigrateBackupAndDelete` in Task 3.
- The package must not be run when the user cancels installation, the sandbox loses its network, the package version changes or the installation is interrupted: see `installRequiresMatchingConsentAndNeverRunsAfterCancellation` in Task 6.
- Presets after an app upgrade must not silently change where an existing service's secrets go: see `existingPresetConfigKeepsConnectionSnapshotAfterCatalogUpdate` in Task 4.
- A single bad catalog entry, one service timing out or a failed handshake must not affect other entries or tasks: see the isolation/regression tests in Task 2, Task 4 and Task 10.

---

### Task 1: Local UI prototype and confirmation gate

**Files:**
- Create: `docs/design/prototypes/2026-11-22-mcp-service-catalog.html`

**Interfaces:**
- Produces: the layouts of the MCP home, catalog, detail/connect, success, failure, management and custom pages, confirmed by the user; Tasks 8–9 implement only the confirmed prototype.

- [ ] **Step 1: Build a single-file, clickable local HTML prototype**

Cover narrow phone screens and wide desktop screens; use the existing App's light/dark semantics, and include: empty/non-empty home, a searchable and categorized catalog, OAuth, multiple secrets and ordinary fields, no auth, connecting/cancel, success tool count, recoverable failure, stdio package confirmation, management and advanced customization. All data uses clearly fictional examples and makes no network requests.

- [ ] **Step 2: Check the prototype locally**

Run: open the above HTML in a browser, click through every flow one by one and check for no horizontal scrolling on narrow screens, keyboard focus, light/dark mode and long copy.
Expected: every state is reachable, the preset flows never show URL/transport, and the custom entry point is always present.

- [ ] **Step 3: Ask the user to confirm the prototype**

Show the prototype and explicitly wait for confirmation. Do not carry out the App UI changes of Tasks 8–9 before confirmation; later pure-logic code can be reviewed first, but when this plan is executed in order, stop here.

- [ ] **Step 4: Commit**

```bash
git add docs/design/specs/2026-11-22-mcp-service-catalog-design.md docs/design/plans/2026-11-22-mcp-service-catalog.md docs/design/prototypes/2026-11-22-mcp-service-catalog.html
git commit -m "docs: design and prototype guided MCP service connections"
```

### Task 2: Versioned preset model, catalog parsing and safe template resolution

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/cap/mcp/McpPreset.kt`
- Create: `core/src/test/kotlin/com/wangzan101/mika/core/cap/mcp/McpPresetTest.kt`

**Interfaces:**
- Produces:
  - `data class McpCatalog(val formatVersion: Int, val presets: List<McpPreset>)`; `McpCatalog.Empty`.
  - `data class McpPreset(val id: String, val name: String, val summary: McpCopy, val category: String, val transport: String, val auth: String, val endpoint: String, val command: List<String>, val fields: List<McpPresetField>, val packageSpec: McpPackageSpec?, val officialSource: String)`.
  - `data class McpPresetField(val id: String, val label: McpCopy, val secret: Boolean, val required: Boolean, val help: McpCopy?)`.
  - `data class McpCopy(val values: Map<String, String>) { fun resolve(languageTag: String): String }`.
  - `data class McpPackageSpec(val ecosystem: String, val name: String, val version: String, val source: String)`.
  - `data class ResolvedMcpPreset(val url: String, val argv: List<String>, val headers: Map<String, String>, val environment: Map<String, String>, val auth: String)`.
  - `object McpCatalogParser { fun parse(text: String): McpCatalog }`.
  - `class McpPresetResolver { fun resolve(preset: McpPreset, values: Map<String, String>): ResolvedMcpPreset }`.

- [ ] **Step 1: Write failing parser/resolver tests**

Test `parsesLocalizedCatalogAndResolvesOnlyDeclaredFields`: two presets, multilingual copy, multiple secret/ordinary fields, and fixed argv with template values all produce deterministic output.
Test `rejectsSecretExfiltrationAndUnsupportedTargets`: duplicate IDs/field IDs, secrets entering the URL or argv, unknown transport/auth/target, an empty official source and an unpinned package version all fail.
Test `rejectsMalformedCatalogWithoutPartialSuccess`: a catalog-level format error fails as a whole; per-entry isolation during subsequent asset loading is covered by Task 7.
Test `copyResolvesExactLanguageThenBaseThenDefault`: tests language fallback only; Task 7 requires the production assets to have no missing translations.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests 'com.wangzan101.mika.core.cap.mcp.McpPresetTest'`
Expected: FAIL, the type does not exist.

- [ ] **Step 3: Implement the catalog model, parser and resolver**

Use `kotlinx.serialization`; preset JSON stores only declarative templates. The resolver performs target-aware validation/encoding for ordinary fields, and allows secrets only as Bearer, secret headers and env; secrets are forbidden from entering the URL/argv. OAuth and no-auth must not require secret fields.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:test --tests 'com.wangzan101.mika.core.cap.mcp.McpPresetTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/wangzan101/mika/core/cap/mcp/McpPreset.kt core/src/test/kotlin/com/wangzan101/mika/core/cap/mcp/McpPresetTest.kt
git commit -m "feat(mcp): add validated service preset templates"
```

### Task 3: Multi-field configuration, encrypted secrets and old-config compatibility

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/settings/McpFieldSecrets.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/McpServerStore.kt`
- Modify: `app/src/test/java/com/wangzan101/mika/settings/McpServerStoreTest.kt`
- Modify: `app/src/test/java/com/wangzan101/mika/backup/BackupEngineTest.kt`

**Interfaces:**
- Consumes: Task 2's `McpPreset` / `McpPresetField`.
- Produces:
  - `McpServerConfig` gains `presetId: String? = null`, `presetSnapshot: McpPreset? = null` and `fieldValues: Map<String, String> = emptyMap()`; the snapshot contains no secrets, and the latter holds only non-secret values.
  - `class McpFieldSecrets { fun get(configId: String, fieldId: String): String?; fun set(configId: String, fieldId: String, value: String?); fun all(configId: String): Map<String, String>; fun clear(configId: String) }`, reusing `mcp-server-secrets.bin`.
  - The Store keeps the existing `token(id)` / `setToken` / OAuth API and adds `secret(configId, fieldId)`, `setSecret(...)` and `secrets(configId)`.

- [ ] **Step 1: Write failing storage and migration tests**

Test `fieldSecretsRoundTripMigrateBackupAndDelete`: two secrets for the same service and two services that never cross wires; legacy Bearer and OAuth keys remain readable; deletion clears only the target service; after a backup and restore the secrets are usable and the ordinary JSON holds no secrets.
Test `legacyCustomConfigLoadsWithoutPresetFields`: the transport/token/OAuth/allowlist behavior of old JSON is completely unchanged.
Test `presetConfigRejectsSecretInPlainFieldValues`: writing to `fieldValues` is rejected when a preset field is declared secret.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.settings.McpServerStoreTest' --tests 'com.wangzan101.mika.backup.BackupEngineTest'`
Expected: FAIL, the new fields/methods do not exist.

- [ ] **Step 3: Implement scoped field secrets and compatible config**

Use namespaced keys inside the existing encrypted JSON without migrating or breaking the old token/OAuth keys; all writes keep using atomic replacement. The existence of preset fields is validated by the catalog, and secret plaintext goes only through `McpFieldSecrets`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.settings.McpServerStoreTest' --tests 'com.wangzan101.mika.backup.BackupEngineTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/settings/McpFieldSecrets.kt app/src/main/java/com/wangzan101/mika/settings/McpServerStore.kt app/src/test/java/com/wangzan101/mika/settings/McpServerStoreTest.kt app/src/test/java/com/wangzan101/mika/backup/BackupEngineTest.kt
git commit -m "feat(mcp): store multiple preset credentials securely"
```

### Task 4: Unified orchestration from presets to HTTP/OAuth connections

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/settings/McpServerStore.kt`
- Modify: `app/src/test/java/com/wangzan101/mika/settings/McpServerStoreTest.kt`
- Create: `app/src/test/java/com/wangzan101/mika/settings/McpPresetConnectionTest.kt`

**Interfaces:**
- Consumes: Task 2's catalog/resolver; Task 3's configuration and secret APIs.
- Produces:
  - `McpServerStore.catalog: McpCatalog` constructor parameter, defaulting to `McpCatalog.Empty`.
  - `fun presetConfig(presetId: String, values: Map<String, String>): Result<McpServerConfig>`.
  - `suspend fun connectPreset(presetId: String, values: Map<String, String>, secrets: Map<String, String>): Result<McpTestResult>`; after saving the configuration/secrets it goes through the existing `test`, and on failure the configuration is kept disabled.
  - The existing `connectionsFor`, `open` and `authorize` produce the same endpoint for presets and custom configurations.

- [ ] **Step 1: Write failing HTTP/OAuth tests**

Test `presetHttpUsesResolvedUrlHeadersAndBearer`: MockWebServer verifies the URL, multiple secret headers and ordinary headers together with the existing initialize/tools/list.
Test `presetOAuthReusesDiscoveryPkceRefreshAndEncryptedStorage`: uses the existing OAuth test doubles and adds no service-specific branches.
Test `failedPresetTestLeavesDisabledEditableConfig`: a failed connection does not enter the healthy-success state and produces no task connection.
Test `existingPresetConfigKeepsConnectionSnapshotAfterCatalogUpdate`: a saved URL, auth destination and argv snapshot do not change when the catalog object is replaced; only newly created configurations use the new template.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.settings.McpPresetConnectionTest'`
Expected: FAIL, the preset entry point is not wired.

- [ ] **Step 3: Implement preset resolution in the existing store**

`McpServerStore` takes a catalog injected by the caller; tests inject a temporary directory directly, and App asset loading is wired in Task 7. Custom configurations keep using the original fields; preset configurations use the saved secret-free snapshot and the current secrets. All HTTP security checks, the ban on redirects, OAuth endpoint checks and the task lifecycle reuse the existing implementation.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.settings.McpPresetConnectionTest' --tests 'com.wangzan101.mika.settings.McpServerStoreTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/settings/McpServerStore.kt app/src/test/java/com/wangzan101/mika/settings/McpServerStoreTest.kt app/src/test/java/com/wangzan101/mika/settings/McpPresetConnectionTest.kt
git commit -m "feat(mcp): connect presets through existing client paths"
```

### Task 5: The explicit secret environment for stdio

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/cap/mcp/StdioMcpClient.kt`
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/cap/sandbox/SandboxPort.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/cap/sandbox/AndroidSandbox.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/cap/sandbox/ProcRunner.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/MikaApp.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/McpServerStore.kt`
- Modify: `core/src/test/kotlin/com/wangzan101/mika/core/cap/mcp/StdioMcpClientTest.kt`
- Modify: `app/src/test/java/com/wangzan101/mika/cap/sandbox/SandboxUsageConcurrencyTest.kt`

**Interfaces:**
- Produces: `McpStdioLauncher.launch(workspace: String, argv: List<String>, environment: Map<String, String>)`; `SandboxPort.openProcess(..., environment: Map<String, String> = emptyMap())`. Old callers use the default empty environment.

- [ ] **Step 1: Write failing environment tests**

Test `stdioPassesOnlyResolvedEnvironment`: two secrets and one ordinary env reach the launcher, and argv contains no secrets.
Test `sandboxProcessEnvironmentUsesExplicitAllowlist`: append only the resolved environment, inherit neither the model provider's, the App's nor the host's environment; keep base entries such as PATH/HOME/TZ.
Test `legacyStdioConfigStillLaunchesWithNoExtraEnvironment`: old custom configuration behavior is unchanged.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests 'com.wangzan101.mika.core.cap.mcp.StdioMcpClientTest'`
Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.cap.sandbox.SandboxUsageConcurrencyTest'`
Expected: FAIL, the environment parameter does not exist.

- [ ] **Step 3: Thread explicit environment through launcher and sandbox**

Keep argv from going through a shell, and keep the process-tree lease and shutdown semantics; environment keys may only match `[A-Za-z_][A-Za-z0-9_]*`, and values are never logged.

- [ ] **Step 4: Run tests to verify they pass**

Run: same as Step 2.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/wangzan101/mika/core/cap/mcp/StdioMcpClient.kt core/src/main/kotlin/com/wangzan101/mika/core/cap/sandbox/SandboxPort.kt app/src/main/java/com/wangzan101/mika/cap/sandbox/AndroidSandbox.kt app/src/main/java/com/wangzan101/mika/cap/sandbox/ProcRunner.kt app/src/main/java/com/wangzan101/mika/MikaApp.kt app/src/main/java/com/wangzan101/mika/settings/McpServerStore.kt core/src/test/kotlin/com/wangzan101/mika/core/cap/mcp/StdioMcpClientTest.kt app/src/test/java/com/wangzan101/mika/cap/sandbox/SandboxUsageConcurrencyTest.kt
git commit -m "feat(mcp): pass explicit secrets to sandbox stdio"
```

### Task 6: Confirmed stdio package installation and workspace caching

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/mcp/McpPackageManager.kt`
- Create: `app/src/main/java/com/wangzan101/mika/mcp/McpPackageConsent.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/MikaApp.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/McpServerStore.kt`
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/cap/sandbox/SandboxPackages.kt`
- Modify: `tools/sandbox/build-runtime.py`
- Modify: `app/src/main/assets/runtime/rootfs.tar.xz` and `app/src/main/assets/runtime/packages.json` (generated by the existing build script; do not hand-edit binaries)
- Create: `app/src/test/java/com/wangzan101/mika/mcp/McpPackageManagerTest.kt`

**Interfaces:**
- Consumes: `McpPackageSpec`; Task 5's stdio environment.
- Produces:
  - `interface McpPackageProvisioner { suspend fun prepare(workspace: String, spec: McpPackageSpec, confirmed: Boolean): List<String> }`, returning the argv prefix after installation, without running the MCP service itself.
  - `class McpPackageConsent { fun accepted(spec: McpPackageSpec): Boolean; fun accept(spec: McpPackageSpec); fun clear() }`, where consent binds the package name + exact version + source.
  - The Store requires explicit confirmation when a preset has a package that is not cached; during a task, no new version may be downloaded without a new confirmation.

- [ ] **Step 1: Write failing provisioning tests**

Test `installRequiresMatchingConsentAndNeverRunsAfterCancellation`: no confirmation, cancellation, or a change of source or version all mean no install/no launch; after confirming the exact version, installation may proceed.
Test `installIsPerWorkspaceCachedAndFailureIsRetryable`: the same workspace installs only once per transaction; an interruption leaves no success marker; another workspace reuses the existing consent but has its own cache.
Test `networkOffAndUnsupportedPackageFailWithGuidance`: with no network, or when a package needs a native build/install script, return a clear error and never bypass it silently.
Test `nodeAndPythonPackagesResolvePinnedArgvWithoutSecrets`: package name@exact version produces a deterministic argv, and secrets go only through the Task 5 environment.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.mcp.McpPackageManagerTest'`
Expected: FAIL, the provisioner does not exist.

- [ ] **Step 3: Implement pinned package provisioning**

Reuse `SandboxPort.run` and the tree lock; Node uses an exact version with `--ignore-scripts --no-audit --no-fund`, Python uses an exact version with `--only-binary=:all:`. Install into the workspace's controlled cache, with the success marker written atomically; packages that must run an install script or a native build are unsupported. Complete the existing runtime build manifest for the Node package manager, rebuild the assets with the project's existing process, and record the verification output.

- [ ] **Step 4: Run tests and runtime asset checks**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.mcp.McpPackageManagerTest' --tests 'com.wangzan101.mika.cap.sandbox.BundledRuntimeTest'`
Expected: PASS; the generated assets' versions and package manifest are consistent.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/mcp/McpPackageManager.kt app/src/main/java/com/wangzan101/mika/mcp/McpPackageConsent.kt app/src/main/java/com/wangzan101/mika/MikaApp.kt app/src/main/java/com/wangzan101/mika/settings/McpServerStore.kt core/src/main/kotlin/com/wangzan101/mika/core/cap/sandbox/SandboxPackages.kt tools/sandbox/build-runtime.py app/src/main/assets/runtime/rootfs.tar.xz app/src/main/assets/runtime/packages.json app/src/test/java/com/wangzan101/mika/mcp/McpPackageManagerTest.kt
git commit -m "feat(mcp): provision confirmed stdio packages in sandbox"
```

### Task 7: The complete built-in catalog, all languages and the bulk verification tool

**Files:**
- Create: `app/src/main/assets/mcp/catalog.json`
- Create: `app/src/main/java/com/wangzan101/mika/settings/McpCatalogAssets.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/MikaApp.kt`
- Create: `app/src/test/java/com/wangzan101/mika/settings/McpCatalogAssetsTest.kt`
- Create: `tools/mcp/check_catalog.py`
- Create: `tools/mcp/tests/test_check_catalog.py`

**Interfaces:**
- Consumes: Task 2's parser/model; Task 4's `McpServerStore.catalog`.
- Produces: `McpCatalogAssets.load(context: Context): McpCatalog`; a single corrupt entry is logged and skipped without blocking other entries. The catalog verification tool outputs a structured JSON report and never writes back to the catalog or fabricates verification status.

- [ ] **Step 1: Write failing catalog tests**

Test `bundledCatalogHasAtLeast200UniqueUsablePresets`: the production assets hold at least 200 entries, not counting corrupt ones.
Test `everyPresetHasAllLocaleCopyAndValidOfficialSource`: every description/field label covers the existing UI languages; remote entries have evidence of an official source; stdio packages have a source and an exact version.
Test `catalogLoaderSkipsOnlyMalformedEntries`: a bad entry does not affect good entries.
Test `checkerSeparatesVerifiedUnreachableAndUnsupported`: the results of secret-free initialize/tools/list are classified truthfully, and a network failure is never written up as verified.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.settings.McpCatalogAssetsTest' && python3 -I -m unittest discover -s tools/mcp/tests -p 'test_check_catalog.py' -v`
Expected: FAIL, the production catalog and the tool do not exist.

- [ ] **Step 3: Populate and validate the complete catalog**

Assemble 200–300 common services in bulk; accept only official remote endpoints, and verify the publisher/source and exact version of stdio packages; write all the copy yourself and cover every UI language. Use generic categories and generic icons, and do not copy external descriptions. `McpCatalogAssets` loads the assets, and `MikaApp` injects the catalog into `McpServerStore`. The verification tool sends only secret-free protocol requests and outputs the results in a separate report; entries that were not actually verified are marked unverified, never misreported.

- [ ] **Step 4: Run tests and the offline/online checks**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.settings.McpCatalogAssetsTest' && python3 -I -m unittest discover -s tools/mcp/tests -p 'test_check_catalog.py' -v`
Expected: PASS.
Run: `python3 -I tools/mcp/check_catalog.py app/src/main/assets/mcp/catalog.json --output <temporary-report-path>`.
Expected: the report distinguishes verified/unreachable/unsupported item by item; failed items carry a real reason and do not block other items.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/mcp/catalog.json app/src/main/java/com/wangzan101/mika/settings/McpCatalogAssets.kt app/src/main/java/com/wangzan101/mika/MikaApp.kt app/src/test/java/com/wangzan101/mika/settings/McpCatalogAssetsTest.kt tools/mcp/check_catalog.py tools/mcp/tests/test_check_catalog.py
git commit -m "feat(mcp): bundle the complete guided service catalog"
```

### Task 8: Catalog browsing and guided connection UI

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpCatalogScreen.kt`
- Create: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpConnectScreen.kt`
- Create: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpConnectorState.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpServersScreen.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/settings/ui/SettingsScreen.kt`
- Modify: `app/src/main/res/values*/strings_settings.xml` and `strings_mcp.xml` where needed
- Create: `app/src/test/java/com/wangzan101/mika/mcp/ui/McpConnectorStateTest.kt`
- Modify: `app/src/androidTest/java/com/wangzan101/mika/settings/ui/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: the prototype confirmed in Task 1; the connection and confirmation APIs from Tasks 4/6; the catalog from Task 7.
- Produces: the `McpConnectorState` state flow and actions: `openPreset(id)`, `updateField(id,value)`, `authorize(openBrowser)`, `confirmPackage()`, `connect()`, `cancel()`, `retry()`. The UI never assembles URLs/headers/argv itself.

- [ ] **Step 1: Write failing state and UI tests**

State tests cover: selecting a preset loads only declared fields, multiple secret inputs, automatic testing after the OAuth callback, enabling only once the connection succeeds, keeping inputs on failure, no download on cancel, and re-confirmation being required when a stdio package's confirmed version no longer matches.
UI tests cover: entering the catalog from the empty state, search/category, no URL/transport on preset pages, a visible custom entry point, the success tool count, retry on failure, and going back without losing unsubmitted non-secret input.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.mcp.ui.McpConnectorStateTest' :app:compileDebugAndroidTestKotlin`
Expected: FAIL, the state machine and the pages do not exist.

- [ ] **Step 3: Implement the approved prototype with existing Compose components**

The home keeps the list of added servers; the catalog supports search and categories; detail/connect/success/failure follow the prototype. Every asynchronous action is cancellable, and secrets never enter `rememberSaveable`, screenshot state or logs; all added/replaced strings are synced across every language.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.mcp.ui.McpConnectorStateTest' :app:assembleDebugAndroidTest -PdevInstall`
Expected: PASS / build succeeds; the new-string completeness test passes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/ui app/src/main/java/com/wangzan101/mika/settings/ui/SettingsScreen.kt app/src/main/res app/src/test/java/com/wangzan101/mika/mcp/ui/McpConnectorStateTest.kt app/src/androidTest/java/com/wangzan101/mika/settings/ui/SettingsScreenTest.kt
git commit -m "feat(mcp): add guided service catalog connections"
```

### Task 9: Service management and the full custom page

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpServerManageScreen.kt`
- Create: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpCustomServerScreen.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpServersScreen.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/mcp/ui/McpConnectorState.kt`
- Modify: `app/src/main/res/values*/strings_settings.xml`
- Modify: `app/src/test/java/com/wangzan101/mika/mcp/ui/McpConnectorStateTest.kt`
- Modify: `app/src/androidTest/java/com/wangzan101/mika/settings/ui/SettingsScreenTest.kt`

**Interfaces:**
- Produces: the management actions `test`, `setEnabled`, `reauthorize`, `replaceSecrets`, `delete`; customization still produces the original `McpServerConfig` and supports every existing advanced field.

- [ ] **Step 1: Write failing management/custom tests**

Cover: viewing the real tools, enabling/disabling, re-testing, OAuth re-authorization, replacing only the selected secret, and deletion cleaning up all secrets; custom HTTP/SSE/stdio, Bearer/OAuth, secret request headers, the stdio secret environment, multiple request headers, allowlists, local HTTP, trust annotations and advanced numeric values are all configurable; advanced items are collapsed by default.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.mcp.ui.McpConnectorStateTest' :app:compileDebugAndroidTestKotlin`
Expected: FAIL, the management/custom pages are not finished.

- [ ] **Step 3: Implement management and custom flows**

Reuse components following the confirmed prototype; customization and presets share the store. The deletion copy does not falsely claim to revoke third-party authorization; old custom configurations remain editable without losing fields.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.mcp.ui.McpConnectorStateTest' :app:assembleDebugAndroidTest -PdevInstall`
Expected: PASS / build succeeds.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/ui app/src/main/res app/src/test/java/com/wangzan101/mika/mcp/ui/McpConnectorStateTest.kt app/src/androidTest/java/com/wangzan101/mika/settings/ui/SettingsScreenTest.kt
git commit -m "feat(mcp): complete service management and custom setup"
```

### Task 10: Full-chain regression, real services and on-device acceptance

**Files:**
- Modify only if a reproduced defect requires it; otherwise no new files.

**Interfaces:**
- Consumes: all preceding tasks.
- Produces: test evidence, a real-service compatibility report and on-device acceptance results; never describe unverified items as passed.

- [ ] **Step 1: Run JVM and catalog regression**

Run: `./gradlew :core:test :app:testDebugUnitTest`
Run: `python3 -I -m unittest discover -s tools/mcp/tests -v`
Expected: PASS; if a pre-existing, local load-sensitive flake appears, record the raw output and judge it according to the project's existing convention, without concealing it.

- [ ] **Step 2: Run protocol, backup and UI build checks**

Run: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PdevInstall`
Expected: the build succeeds; old custom MCP, backup/restore and inbound tests show no regression.

- [ ] **Step 3: Verify representative real services without private user data**

Use dedicated test accounts/keys to cover OAuth, single/multiple secrets, extra ordinary fields, no auth, Node stdio and Python stdio; also cover wrong keys, cancelled authorization, refresh, offline, pagination and large results. Compare the real results against the Task 7 report, and on failure fix only the generic root cause, never writing service special cases.

- [ ] **Step 4: Run phone smoke tests**

Verify on a real device: catalog search, the OAuth round trip, API Key, multiple fields, stdio confirmation/cancel/caching, a real invocation during a task, process cleanup after the task ends, backup/restore, landscape/portrait and light/dark, RTL and at least one CJK language. Use the project's agreed core smoke subset and do not run the full roughly 6-hour suite.

- [ ] **Step 5: Final review and commit**

Check that the git diff contains no secrets, temporary reports or test accounts; confirm that every plan item and acceptance result is recorded truthfully. If there are cleanup fixes, commit:

```bash
git add <verified-changed-files>
git commit -m "test(mcp): verify guided catalog connections"
```
