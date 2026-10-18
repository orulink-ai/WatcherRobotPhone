<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 59 — Privacy Mode Effectiveness Benchmark

Measure the detection effectiveness of the whole Privacy Mode pipeline (deterministic + model + merge/filter, via the REAL production code path) with per-layer attribution, and — subject to explicit user approval after the user has reviewed the measured numbers — publish them in the README. Three corpora: (A) ai4privacy open-pii-masking-500k validation split (download-on-demand, pinned commit), (B) seeded UI-shaped synthetic corpus, (C) hand-curated adversarial suite. NOT a CI job — a locally-run benchmark tool.

## Prerequisites & branch

- **PR #138 (feat/privacy-mode-pii-redaction) MUST be merged to `main` before implementation starts.** This plan is anchored to that branch's code (file contents quoted below are from commit `bd1477a`). If other PRs merge first, re-anchor before implementing.
- Branch: `git checkout main && git pull origin main && git checkout -b feat/privacy-effectiveness-benchmark`.

## Verified facts (pinned; do not re-derive)

- Dataset: `ai4privacy/open-pii-masking-500k-ai4privacy`, license CC-BY-4.0 (card `license_name: cc-by-4.0`), DOI `10.57967/hf/4852`. It is the exact training dataset of the shipped model, so Corpus A results are in-domain (flattering) and directly comparable to the model card (F1 0.915 micro / 0.849 macro on the held-out split).
- Validation split = single JSONL file, pinned:
  - URL: `https://huggingface.co/datasets/ai4privacy/open-pii-masking-500k-ai4privacy/resolve/506996d625ed970a0063432daf6007cf4a3a48e3/data/validation/test.jsonl`
  - sha256 `4e908e60d8d88f90015301e1ab4a8b7899ec713f1fc903860e1d9e0b91677ebf`, size `141_836_910` bytes, ~116k rows.
- Row schema: `source_text`, `privacy_mask: [{label, start, end, value, label_index}]`, `language`, `region`, `uid` (+ other fields, ignored). Offsets are char-based into `source_text`. `uid` verified integral (sampled rows 2026-08-03: `5706814`, `5760565`) → `Long` in the loader DTO.
- Dataset labels → `PiiCategory` mapping (labels mapped to `null` are OUT OF SCOPE for this app and excluded from scoring):
  - `GIVENNAME`, `SURNAME` → `NAMES`
  - `EMAIL` → `EMAILS`
  - `TELEPHONENUM` → `PHONE_NUMBERS`
  - `STREET`, `CITY`, `ZIPCODE`, `BUILDINGNUM` → `ADDRESSES`
  - `SOCIALNUM`, `TAXNUM`, `PASSPORTNUM`, `DRIVERLICENSENUM`, `IDCARDNUM` → `NATIONAL_IDS`
  - `DATE`, `TIME`, `AGE`, `SEX`, `GENDER`, `TITLE`, `ORGANISATIONPLACEHOLDER` → `null` (excluded)
  - Unknown labels → `null` + counted in load stats (never crash).
- The dataset contains NO cards, IBANs, or credentials → `CARDS_AND_IBAN` and `CREDENTIALS` are measured ONLY by Corpora B and C.
- Model + tokenizer assets: reuse `PrivacyModelAssets.MODEL` / `.TOKENIZER` (already pinned by sha256 to HF commit `83ef30d5…`).
- `PhoneDetector` uses `Locale.getDefault().country` — the benchmark MUST call `Locale.setDefault(Locale.US)` at startup for reproducibility.
- `onnxruntime` JVM artifact (`com.microsoft.onnxruntime:onnxruntime`) is API-identical (`ai.onnxruntime.*`) to `onnxruntime-android`; `libphonenumber` is used ONLY by the privacy package in `:app`.

## Scoring rules (single source of truth)

- Per sample: gold spans partition into in-scope (`category != null`) and excluded (`category == null`).
- A prediction is **ignored** (neither TP nor FP) iff it overlaps ≥1 excluded gold span AND overlaps no same-category in-scope gold span.
- **PARTIAL** match: prediction and gold overlap (`p.start < g.end && g.start < p.end`) with equal category. **STRICT**: identical boundaries and equal category.
- TP = matched in-scope gold spans; FN = unmatched in-scope gold spans; FP = non-ignored predictions matching no gold span (per mode).
- **Char-leak rate** (headline safety number): over in-scope gold spans, fraction of gold characters NOT covered by ANY prediction of ANY category (a miscategorized-but-redacted char is not leaked).
- **Residual value leaks** (full layer only): count of samples whose redacted output still contains a gold span's raw value (values of length ≥ 4).
- Metrics: precision, recall, F1, Fβ=2 (`5·P·R / (4·P + R)`, recall-weighted per Presidio guidance). Micro = summed counts; macro = mean over categories with ≥1 gold span.
- Layers (all use production classes): `deterministic` = `DeterministicEngine.detectAll` → `mergeOverlaps`; `model` = `NerEngine.detect` → `mergeOverlaps`; `full` = `RedactionEngine.detect` with `PrivacyModeConfig(enabled = true)` (all categories on).

---

## US1 — Extract the `:privacy` pure-JVM module

Why: the benchmark needs in-process access to the production pipeline and a sibling module cannot depend on `com.android.application`. All moved files KEEP their package names → zero import churn in `:app`.

Acceptance criteria:
- [x] `:privacy` compiles as a pure Kotlin/JVM module; `:app` depends on it and builds for all flavors.
- [x] Moved classes/tests keep their packages; `:app` sources need no import changes (only the listed file edits).
- [x] `RedactionEngine` (in `:privacy`) contains the exact production merge/filter/redact + tree-walk logic; `PrivacyPipelineImpl` (in `:app`) is only gating + fail-closed mapping + delegation.
- [x] ktlint, detekt, and jacoco (0.50 verification) apply to `:privacy`; CI and Makefile run its tests.

### T1.1 — Module skeleton

- [x] A1.1.1 — Modify `gradle/libs.versions.toml`: add to `[libraries]`:

```toml
javax-inject = { group = "javax.inject", name = "javax.inject", version = "1" }
```

- [x] A1.1.2 — Modify `settings.gradle.kts`: add `include(":privacy")` after `include(":app")`.

- [x] A1.1.3 — Create `privacy/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    jacoco
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ktlint {
    version.set("1.8.0")
}

// Same rationale as :app — ktlint-cli 1.8.0 bundles a CVE-flagged logback used only at build time.
configurations.matching { it.name.startsWith("ktlint") }.configureEach {
    resolutionStrategy {
        force(
            "ch.qos.logback:logback-core:1.5.34",
            "ch.qos.logback:logback-classic:1.5.34",
        )
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.javax.inject)
    implementation(libs.libphonenumber)
    // API-identical to onnxruntime-android; :app supplies the Android AAR at runtime and
    // :privacy-benchmark / tests supply the JVM artifact.
    compileOnly(libs.onnxruntime.jvm)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.onnxruntime.jvm)
}

tasks.test {
    useJUnitPlatform()
}

jacoco {
    toolVersion = "0.8.14"
}

// OrtPiiModelRunner is exercised only by the PRIVACY_MODEL_DIR-gated real-model test (CI has no
// 151 MB model), so it is excluded from the coverage calculation — the same pattern :app uses for
// device-only classes. User-approved (review finding P59-004).
val privacyJacocoClassDirs =
    fileTree(layout.buildDirectory.dir("classes/kotlin/main")) {
        exclude("**/OrtPiiModelRunner*")
    }

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(privacyJacocoClassDirs)
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    classDirectories.setFrom(privacyJacocoClassDirs)
    violationRules {
        rule {
            limit {
                minimum = "0.50".toBigDecimal()
            }
        }
    }
}
```

DoD: `./gradlew :privacy:build` succeeds (module empty at this point).

### T1.2 — Move accessibility data classes

- [x] A1.2.1 — Create `privacy/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityData.kt`: move the four `@Serializable` data classes `BoundsData`, `AccessibilityNodeData`, `WindowData`, `MultiWindowResult` VERBATIM (including KDoc) out of `app/src/main/kotlin/.../services/accessibility/AccessibilityTreeParser.kt`. Package stays `com.danielealbano.androidremotecontrolmcp.services.accessibility`; only import needed is `kotlinx.serialization.Serializable`.
- [x] A1.2.2 — Modify `AccessibilityTreeParser.kt`: delete the four moved class definitions (and the now-unused `Serializable` import if no other use remains). Everything else unchanged.

DoD: no duplicate class definitions; `:app` still compiles once A1.5.1 adds the module dependency (compile check deferred to end of US1 per plan workflow).

### T1.3 — Move the detection core

- [x] A1.3.1 — Move VERBATIM (git mv; identical content, identical package) from `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/` to `privacy/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/`:

| File |
|---|
| `privacy/PiiCategory.kt` |
| `privacy/PiiDetection.kt` (includes `DetectionContext`) |
| `privacy/DeterministicEngine.kt` |
| `privacy/PseudonymStore.kt` |
| `privacy/PlaceholderSubstitutor.kt` |
| `privacy/Redactor.kt` |
| `privacy/ContextExtractor.kt` |
| `privacy/detectors/CardDetector.kt` |
| `privacy/detectors/ContextKeywords.kt` |
| `privacy/detectors/CredentialDetector.kt` |
| `privacy/detectors/DeterministicDetector.kt` |
| `privacy/detectors/EmailDetector.kt` |
| `privacy/detectors/IbanDetector.kt` |
| `privacy/detectors/NationalIdDetector.kt` |
| `privacy/detectors/PhoneDetector.kt` |
| `privacy/ner/BioDecoder.kt` |
| `privacy/ner/NerCache.kt` |
| `privacy/ner/NerEngine.kt` |
| `privacy/ner/OrtPiiModelRunner.kt` |
| `privacy/ner/PiiModelInference.kt` |
| `privacy/ner/WindowPacker.kt` |
| `privacy/tokenizer/AddedTokenSplitter.kt` |
| `privacy/tokenizer/BpePreTokenizer.kt` |
| `privacy/tokenizer/ByteLevelMapping.kt` |
| `privacy/tokenizer/ModernBertTokenizer.kt` |
| `privacy/tokenizer/TokenizerData.kt` |
| `privacy/model/PrivacyModelAssets.kt` |
| `data/model/PrivacyModeConfig.kt` (includes `RedactionMode`, `PlaceholderFormat`) |

- [x] A1.3.2 — Create `privacy/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/DispatcherQualifiers.kt`: move the `IoDispatcher` and `DefaultDispatcher` qualifier annotations VERBATIM (including `@Qualifier`, `@Retention`, KDoc) out of `app/src/main/kotlin/.../di/AppModule.kt`. Delete them from `AppModule.kt` (same package `…di` → no other change). The `OAuthClientsDataStore` qualifier and all providers stay in `AppModule.kt`.

- [x] A1.3.3 — Move + refactor `privacy/model/PrivacyModelStore.kt` to `privacy/src/main/kotlin/.../privacy/model/PrivacyModelStore.kt`: replace the Android `Context` with a plain base directory. Class loses `@Singleton`/`@Inject` (scoping moves to the `:app` provider in A1.3.4). Only the header changes; every method body stays verbatim:

```kotlin
package com.danielealbano.androidremotecontrolmcp.privacy.model

import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelAssets.ModelAsset
import java.io.File

/**
 * On-disk store for the downloaded Privacy Mode model assets. Readiness is reported from a small
 * `.verified` marker (written only after a successful checksum verification) plus file existence and
 * exact byte-size checks — so it never re-hashes the 151 MB model on every server start.
 * [baseDir] is the app's files directory in production (provided by the DI module) and a local cache
 * directory in the effectiveness benchmark.
 */
class PrivacyModelStore(
    private val baseDir: File,
) {
    private fun dir(): File = File(baseDir, PrivacyModelAssets.MODELS_DIR).apply { mkdirs() }
    // fileFor / modelFile / tokenizerFile / markerFile / isReady / writeVerifiedMarker /
    // clearPartialFiles / companion object: UNCHANGED from the current file.
}
```

- [x] A1.3.4 — Modify `app/src/main/kotlin/.../di/AppModule.kt`: add (next to the other providers):

```kotlin
@Provides
@Singleton
fun providePrivacyModelStore(
    @ApplicationContext context: Context,
): PrivacyModelStore = PrivacyModelStore(context.filesDir)
```

(`Context`/`@ApplicationContext` imports already present; add the `PrivacyModelStore` import.)

DoD: all listed files exist only under `privacy/`; `AppModule` provides the store; qualifiers resolve from `:privacy`.

### T1.4 — Extract `RedactionEngine`; slim `PrivacyPipelineImpl`

- [x] A1.4.1 — Create `privacy/src/main/kotlin/.../privacy/RedactionTypes.kt`: move `TextItem` and `ProcessedTree` VERBATIM from `app/src/main/kotlin/.../privacy/PrivacyPipeline.kt` (package `…privacy`; imports `BoundsData`, `MultiWindowResult`).

- [x] A1.4.2 — Create `privacy/src/main/kotlin/.../privacy/RedactionEngine.kt`. The bodies of `detect`/`runModel`/`redactTree`/`collectFields`/`rebuild`/`FieldRef` are MOVED from the current `PrivacyPipelineImpl` — the ONLY behavioral difference is that `runModel` no longer catches `PrivacyModelException` (it propagates; `PrivacyPipelineImpl` maps it):

```kotlin
package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.privacy.ner.NerEngine
import com.danielealbano.androidremotecontrolmcp.privacy.ner.NerSegment
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure detection + rendering core: structural/deterministic detection plus (when the config requires
 * it) one batched model pass, category filter BEFORE merge, rendering via [Redactor], and the
 * accessibility-tree walk. Contains NO enable/readiness gating and NO MCP error mapping —
 * [PrivacyPipelineImpl] gates and maps
 * [com.danielealbano.androidremotecontrolmcp.privacy.ner.PrivacyModelException]; the effectiveness
 * benchmark drives this class directly so published numbers measure the production code path.
 */
@Singleton
class RedactionEngine
    @Inject
    constructor(
        private val deterministicEngine: DeterministicEngine,
        private val nerEngine: NerEngine,
        private val contextExtractor: ContextExtractor,
        private val redactor: Redactor,
    ) {
        /** Merged, category-filtered detections per item. Model errors propagate as PrivacyModelException. */
        suspend fun detect(
            items: List<TextItem>,
            config: PrivacyModeConfig,
        ): List<List<PiiDetection>> {
            val deterministic = items.map { deterministicEngine.detectAll(it.text, it.context) }
            val modelByIndex = if (config.modelRequired()) runModel(items) else emptyMap()
            return items.mapIndexed { index, _ ->
                // Filter by enabled category BEFORE merging so a disabled category can never suppress an
                // overlapping still-enabled detection (which would then leave that span redacted by nobody).
                val enabled =
                    (deterministic[index] + modelByIndex[index].orEmpty())
                        .filter { config.isCategoryEnabled(it.category) }
                DeterministicEngine.mergeOverlaps(enabled)
            }
        }

        suspend fun redactTexts(
            items: List<TextItem>,
            config: PrivacyModeConfig,
        ): List<String> {
            val detections = detect(items, config)
            return items.mapIndexed { index, item -> redactor.apply(item.text, detections[index], config) }
        }

        private suspend fun runModel(items: List<TextItem>): Map<Int, List<PiiDetection>> {
            val segments =
                items.mapIndexedNotNull { index, item ->
                    if (item.text.isBlank()) {
                        null
                    } else {
                        NerSegment(index.toString(), item.context.contextText(), item.text)
                    }
                }
            if (segments.isEmpty()) return emptyMap()
            val results = nerEngine.detect(segments)
            return segments.associate { it.key.toInt() to results[it.key].orEmpty() }
        }

        suspend fun redactTree(
            result: MultiWindowResult,
            config: PrivacyModeConfig,
        ): ProcessedTree {
            val items = mutableListOf<TextItem>()
            val refs = mutableListOf<FieldRef>()
            for (window in result.windows) {
                val nearestLabels = contextExtractor.computeNearestLabels(window.tree)
                collectFields(window.tree, nearestLabels, items, refs)
            }
            val redacted = redactTexts(items, config)
            val textByNode = HashMap<String, String>()
            val descByNode = HashMap<String, String>()
            redacted.forEachIndexed { index, value ->
                val ref = refs[index]
                if (ref.isText) textByNode[ref.nodeId] = value else descByNode[ref.nodeId] = value
            }
            val flaggedBounds = mutableListOf<BoundsData>()
            val newWindows =
                result.windows.map { it.copy(tree = rebuild(it.tree, textByNode, descByNode, flaggedBounds)) }
            return ProcessedTree(result.copy(windows = newWindows), flaggedBounds)
        }

        // collectFields(...), rebuild(...), private data class FieldRef: MOVED VERBATIM from the
        // current PrivacyPipelineImpl.
    }
```

- [x] A1.4.3 — Replace `app/src/main/kotlin/.../privacy/PrivacyPipelineImpl.kt` in full:

```kotlin
package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.ner.PrivacyModelException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [PrivacyPipeline]: gates on the Privacy Mode config + model readiness and delegates the
 * mechanical work to [RedactionEngine]. Fails closed (throws
 * [McpToolException.PrivacyModeUnavailable]) when the model is required but unavailable or inference
 * fails; identity passthrough when Privacy Mode is disabled.
 */
@Singleton
class PrivacyPipelineImpl
    @Inject
    constructor(
        private val manager: PrivacyModeManager,
        private val engine: RedactionEngine,
    ) : PrivacyPipeline {
        override suspend fun processText(
            text: String,
            context: DetectionContext,
        ): String = processTexts(listOf(TextItem(text, context))).first()

        override suspend fun processTexts(items: List<TextItem>): List<String> {
            val config = gatedConfigOrNull() ?: return items.map { it.text }
            return failClosed { engine.redactTexts(items, config) }
        }

        override suspend fun processTree(result: MultiWindowResult): ProcessedTree {
            val config = gatedConfigOrNull() ?: return ProcessedTree(result, emptyList())
            return failClosed { engine.redactTree(result, config) }
        }

        /** Null when Privacy Mode is disabled. Throws when a model-backed category is on but not Ready. */
        private suspend fun gatedConfigOrNull(): PrivacyModeConfig? {
            val config = manager.currentConfig()
            if (!config.enabled) return null
            if (config.modelRequired() && manager.status.value !is PrivacyModeStatus.Ready) {
                throw McpToolException.PrivacyModeUnavailable(
                    "Privacy mode is enabled but the on-device detection model is not available",
                )
            }
            return config
        }

        private suspend fun <T> failClosed(block: suspend () -> T): T =
            try {
                block()
            } catch (e: PrivacyModelException) {
                throw McpToolException.PrivacyModeUnavailable(e.message ?: "model inference failed", e)
            }
    }
```

- [x] A1.4.4 — Modify `app/src/main/kotlin/.../privacy/PrivacyPipeline.kt`: delete the moved `TextItem`/`ProcessedTree` definitions (interface + KDoc unchanged; drop now-unused imports).

Intended, output-neutral behavioral notes of this split (review finding P59-006): the old `processTree` called `manager.currentConfig()` twice (top-level + inside `processTexts`) and the readiness gate fired after `collectFields`; the new `gatedConfigOrNull()` reads the config ONCE and gates BEFORE any tree work. Outputs and thrown errors are identical for every input; only mock-observable interaction counts and throw ordering change. The slimmed `PrivacyPipelineImplTest` MUST assert the single-call behavior.

DoD: `PrivacyPipelineImpl` contains no detection mechanics; `RedactionEngine` contains no gating and no MCP types.

### T1.5 — `:app` build file + move tests

- [x] A1.5.1 — Modify `app/build.gradle.kts` dependencies: add `implementation(project(":privacy"))`; remove `implementation(libs.libphonenumber)` (privacy-only, now inside `:privacy`); remove `testImplementation(libs.onnxruntime.jvm)` (its consumers move to `:privacy`). KEEP `implementation(libs.onnxruntime.android)` — it supplies `ai.onnxruntime.*` at app runtime for the `compileOnly` classes in `:privacy`, and its AAR classes are also on the `:app` unit-test classpath, which is what lets the staying `PrivacyModeManagerTest` keep `mockk<OrtPiiModelRunner>(relaxed = true)` compiling and running (verify this at the end-of-plan test gate — review finding P59-011).

- [x] A1.5.2 — Move test files VERBATIM from `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/` to `privacy/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/`:

| File |
|---|
| `privacy/ContextExtractorTest.kt` |
| `privacy/DeterministicEngineTest.kt` |
| `privacy/PlaceholderSubstitutorTest.kt` |
| `privacy/PseudonymStoreTest.kt` |
| `privacy/RedactorTest.kt` |
| `privacy/detectors/CardDetectorTest.kt` |
| `privacy/detectors/CredentialDetectorTest.kt` |
| `privacy/detectors/EmailDetectorTest.kt` |
| `privacy/detectors/IbanDetectorTest.kt` |
| `privacy/detectors/NationalIdDetectorTest.kt` |
| `privacy/detectors/PhoneDetectorTest.kt` |
| `privacy/ner/BioDecoderTest.kt` |
| `privacy/ner/NerCacheTest.kt` |
| `privacy/ner/NerEngineTest.kt` |
| `privacy/ner/OrtPiiModelRunnerRealModelTest.kt` |
| `privacy/ner/WindowPackerTest.kt` |
| `privacy/tokenizer/ModernBertTokenizerParityTest.kt` |
| `privacy/tokenizer/TokenizerDataTest.kt` |
| `privacy/tokenizer/TokenizerPerformanceTest.kt` |

- [x] A1.5.3 — Move test resources: `app/src/test/resources/privacy/**` (tokenizer.json + `tokenizer_fixtures/`) → `privacy/src/test/resources/privacy/**`.

- [x] A1.5.4 — Move + adapt `privacy/model/PrivacyModelStoreTest.kt` → `privacy/src/test/.../privacy/model/PrivacyModelStoreTest.kt`: replace the mocked `Context.filesDir` setup with a JUnit `@TempDir` `File` passed straight to `PrivacyModelStore(baseDir)`. Assertions unchanged.

- [x] A1.5.5 — Split `app/src/test/.../privacy/PrivacyPipelineImplTest.kt`:
  - Mechanics tests move to a new `privacy/src/test/.../privacy/RedactionEngineTest.kt`, driving `RedactionEngine` with an explicit `PrivacyModeConfig` (no manager mock).
  - Gating tests stay in a slimmed `PrivacyPipelineImplTest.kt` (`:app`), mocking `PrivacyModeManager` + `RedactionEngine`.

  **File**: `privacy/src/test/kotlin/.../privacy/RedactionEngineTest.kt` — **Setup**: real `DeterministicEngine`/`Redactor(PseudonymStore())`/`ContextExtractor`; MockK `NerEngine`

  | Test | Verifies |
  |------|----------|
  | `detect merges deterministic and model spans by priority` | moved behavior (was in PrivacyPipelineImplTest) |
  | `disabled category does not suppress overlapping enabled category` | filter-before-merge regression test (moved) |
  | `redactTexts renders detections via redactor` | moved |
  | `runModel skips blank items` | moved |
  | `redactTree redacts node text and returns flagged bounds` | moved |
  | `redactTree uses nearest label context for editable fields` | moved |
  | `model detections propagate PrivacyModelException` | engine does NOT map to MCP errors |

  **File**: `app/src/test/kotlin/.../privacy/PrivacyPipelineImplTest.kt` (slimmed) — **Setup**: MockK `PrivacyModeManager`, MockK `RedactionEngine`

  | Test | Verifies |
  |------|----------|
  | `processTexts passthrough when disabled` | identity, engine never called |
  | `processTexts throws PrivacyModeUnavailable when model required and not ready` | gating |
  | `processTexts delegates to engine when ready` | delegation with current config |
  | `PrivacyModelException maps to PrivacyModeUnavailable` | fail-closed mapping |
  | `processTree passthrough when disabled` | identity `ProcessedTree` |

- [x] A1.5.6 — Modify `app/src/test/.../privacy/model/PrivacyModelDownloaderTest.kt` (STAYS in `:app`): its setup constructs `PrivacyModelStore(context)` from a mocked `Context` — after A1.3.3 that is a type error. Replace the mocked-`Context` setup with a JUnit `@TempDir` `File` passed straight to `PrivacyModelStore(tempDir)` (drop the `Context`/MockK-context lines). Assertions unchanged.

- [x] A1.5.7 — Tests that STAY in `:app` unchanged: `PrivacyModeManagerTest.kt`, `PrivacyToolTestDoubles.kt`, all integration tests (they compile against `:privacy` via the project dependency).

DoD: no test file exists in both modules; `privacy/src/test` has no Android/Hilt imports.

### T1.6 — Wire CI, Makefile, coverage

- [x] A1.6.1 — Modify `Makefile` `test-unit` target:

```make
test-unit: ## Run unit tests (includes integration tests since both are JVM-based)
	$(if $(wildcard .env),set -a && . ./.env && set +a &&,) $(GRADLE) :app:test :privacy:test
```

(`:privacy-benchmark:test` is appended in US2 once that module exists.)

- [x] A1.6.2 — Modify `.github/workflows/ci.yml` unit-test job step (currently `./gradlew :app:test jacocoTestReport jacocoTestCoverageVerification`):

```yaml
run: ./gradlew :app:test :privacy:test jacocoTestReport jacocoTestCoverageVerification
```

(The unqualified `jacocoTestReport`/`jacocoTestCoverageVerification` run in EVERY module applying the jacoco plugin — `:app` and `:privacy` — so no `:privacy:`-qualified duplicates; review finding P59-009.)

DoD: US1 complete; per plan workflow, NO lint/test run yet (quality gates only at the end of the plan).

---

## US2 — `:privacy-benchmark` module: skeleton, downloads, corpus model, Corpus A

Why: on-demand, pinned, checksum-verified acquisition of dataset + model (nothing checked into the repo), and the shared sample/gold representation every corpus and the scorer use.

Acceptance criteria:
- [x] `make privacy-benchmark`-runnable JVM application module (run target wired in US6).
- [x] First run downloads dataset (135 MB) + model (151 MB) + tokenizer into `privacy-benchmark/.cache/` (gitignored) with sha256 verification; later runs skip.
- [x] Corpus A loads the full validation split (or an evenly-spaced `--sample=N` subset), maps labels per the pinned table, drops+counts rows whose gold offsets fail the `value == substring` check.

### T2.1 — Module skeleton

- [x] A2.1.1 — Modify `settings.gradle.kts`: add `include(":privacy-benchmark")`.
- [x] A2.1.2 — Modify `.gitignore`: add `privacy-benchmark/.cache/`.
- [x] A2.1.3 — Create `privacy-benchmark/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.danielealbano.androidremotecontrolmcp.benchmark.BenchmarkMainKt")
}

tasks.named<JavaExec>("run") {
    // Corpus A holds ~116k samples in memory plus ONNX Runtime buffers.
    maxHeapSize = "4g"
}

ktlint {
    version.set("1.8.0")
}

configurations.matching { it.name.startsWith("ktlint") }.configureEach {
    resolutionStrategy {
        force(
            "ch.qos.logback:logback-core:1.5.34",
            "ch.qos.logback:logback-classic:1.5.34",
        )
    }
}

dependencies {
    implementation(project(":privacy"))
    implementation(libs.onnxruntime.jvm)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.libphonenumber)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
```

No jacoco coverage gate on this module — user-approved decision (review finding P59-005): benchmark tooling carries mandatory unit tests but no coverage floor, like `:e2e-tests`.

- [x] A2.1.4 — Modify `Makefile` `test-unit` to append `:privacy-benchmark:test`, and modify the ci.yml unit-test step from A1.6.2 to append `:privacy-benchmark:test`.

### T2.2 — Assets + downloader

- [x] A2.2.1 — Create `privacy-benchmark/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/benchmark/BenchmarkAssets.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.benchmark

import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelAssets
import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelAssets.ModelAsset

/** Benchmark inputs, pinned to immutable revisions so published numbers stay reproducible. */
object BenchmarkAssets {
    const val DATASET_COMMIT = "506996d625ed970a0063432daf6007cf4a3a48e3"

    /** ai4privacy open-pii-masking-500k validation split (CC-BY-4.0, DOI 10.57967/hf/4852). */
    val DATASET =
        ModelAsset(
            fileName = "open-pii-masking-500k-validation.jsonl",
            url =
                "https://huggingface.co/datasets/ai4privacy/open-pii-masking-500k-ai4privacy/" +
                    "resolve/$DATASET_COMMIT/data/validation/test.jsonl",
            sha256 = "4e908e60d8d88f90015301e1ab4a8b7899ec713f1fc903860e1d9e0b91677ebf",
            sizeBytes = 141_836_910L,
        )

    val MODEL = PrivacyModelAssets.MODEL
    val TOKENIZER = PrivacyModelAssets.TOKENIZER
}
```

- [x] A2.2.2 — Create `.../benchmark/BenchmarkDownloader.kt`: `java.net.http` streaming downloader with `.part` + sha256 verify, no extra dependencies:

```kotlin
package com.danielealbano.androidremotecontrolmcp.benchmark

import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelAssets.ModelAsset
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest

/** Downloads a pinned asset into [targetDir] with sha256 verification; skips verified existing files. */
class BenchmarkDownloader(
    private val client: HttpClient =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
) {
    fun ensure(
        asset: ModelAsset,
        targetDir: File,
    ): File {
        targetDir.mkdirs()
        val target = File(targetDir, asset.fileName)
        if (target.exists() && target.length() == asset.sizeBytes && sha256Hex(target) == asset.sha256) {
            println("[cache] ${asset.fileName} already verified")
            return target
        }
        println("[download] ${asset.fileName} (${asset.sizeBytes / BYTES_PER_MIB} MiB) from ${asset.url}")
        val part = File(targetDir, asset.fileName + ".part")
        val request = HttpRequest.newBuilder(URI.create(asset.url)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == HTTP_OK) { "HTTP ${response.statusCode()} for ${asset.url}" }
        val digest = MessageDigest.getInstance("SHA-256")
        response.body().use { input ->
            part.outputStream().use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                var lastLogged = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    total += read
                    if (total - lastLogged >= LOG_EVERY_BYTES) {
                        lastLogged = total
                        println("[download] ${asset.fileName}: ${total / BYTES_PER_MIB} MiB")
                    }
                }
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
        if (part.length() != asset.sizeBytes || hex != asset.sha256) {
            part.delete()
            error("checksum/size mismatch for ${asset.fileName} (got $hex, ${part.length()} bytes)")
        }
        check(part.renameTo(target)) { "rename failed for ${asset.fileName}" }
        return target
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
    }

    private companion object {
        const val BUFFER_SIZE = 256 * 1024
        const val BYTE_MASK = 0xFF
        const val HTTP_OK = 200
        const val BYTES_PER_MIB = 1024L * 1024L
        const val LOG_EVERY_BYTES = 25L * 1024L * 1024L
    }
}
```

Model + tokenizer are ensured into `cacheDir/privacy_model/` (i.e. `PrivacyModelStore(cacheDir)`'s dir); after both verify, call `store.writeVerifiedMarker()` (done in `BenchmarkMain`, US5). The dataset is ensured into `cacheDir/dataset/`.

### T2.3 — Corpus model + Corpus A loader

- [x] A2.3.1 — Create `.../benchmark/corpus/BenchmarkSample.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.benchmark.corpus

import com.danielealbano.androidremotecontrolmcp.privacy.DetectionContext
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory

/** A gold PII span; [category] null = out-of-scope label (excluded from scoring per the plan rules). */
data class GoldSpan(
    val start: Int,
    val end: Int,
    val category: PiiCategory?,
)

data class BenchmarkSample(
    val id: String,
    val text: String,
    val context: DetectionContext,
    val gold: List<GoldSpan>,
    val language: String,
)

/** A loaded corpus plus load transparency stats (nothing is dropped silently). */
data class LoadedCorpus(
    val name: String,
    val samples: List<BenchmarkSample>,
    val droppedRows: Int,
    val unknownLabels: Map<String, Int>,
)
```

- [x] A2.3.2 — Create `.../benchmark/corpus/Ai4PrivacyCorpusLoader.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.benchmark.corpus

import com.danielealbano.androidremotecontrolmcp.privacy.DetectionContext
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class Ai4PrivacyMask(
    val label: String,
    val start: Int,
    val end: Int,
    val value: String,
)

@Serializable
private data class Ai4PrivacyRow(
    @SerialName("source_text") val sourceText: String,
    @SerialName("privacy_mask") val privacyMask: List<Ai4PrivacyMask>,
    val language: String,
    val uid: Long,
)

/**
 * Streams the pinned validation JSONL into [BenchmarkSample]s. Rows whose gold offsets fail the
 * `value == substring(start, end)` integrity check are dropped and counted; unknown labels map to an
 * excluded span and are counted.
 */
class Ai4PrivacyCorpusLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(
        file: File,
        sample: Int = 0,
    ): LoadedCorpus {
        val samples = mutableListOf<BenchmarkSample>()
        var dropped = 0
        val unknownLabels = mutableMapOf<String, Int>()
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val row = json.decodeFromString(Ai4PrivacyRow.serializer(), line)
                val gold = toGoldSpans(row, unknownLabels)
                if (gold == null) {
                    dropped++
                    continue
                }
                samples +=
                    BenchmarkSample("a-${row.uid}", row.sourceText, DetectionContext.EMPTY, gold, row.language)
            }
        }
        return LoadedCorpus("ai4privacy-500k-validation", subsample(samples, sample), dropped, unknownLabels)
    }

    private fun toGoldSpans(
        row: Ai4PrivacyRow,
        unknownLabels: MutableMap<String, Int>,
    ): List<GoldSpan>? {
        val gold = mutableListOf<GoldSpan>()
        for (mask in row.privacyMask) {
            val inBounds = mask.start in 0..mask.end && mask.end <= row.sourceText.length
            if (!inBounds || row.sourceText.substring(mask.start, mask.end) != mask.value) return null
            if (mask.label !in LABEL_TO_CATEGORY) unknownLabels.merge(mask.label, 1, Int::plus)
            gold += GoldSpan(mask.start, mask.end, LABEL_TO_CATEGORY[mask.label])
        }
        return gold
    }

    private fun subsample(
        samples: List<BenchmarkSample>,
        sample: Int,
    ): List<BenchmarkSample> {
        if (sample !in 1 until samples.size) return samples
        val step = samples.size.toDouble() / sample
        return (0 until sample).map { samples[(it * step).toInt()] }
    }

    companion object {
        /** Pinned dataset-label mapping (see plan header); null = out of scope. */
        val LABEL_TO_CATEGORY: Map<String, PiiCategory?> =
            mapOf(
                "GIVENNAME" to PiiCategory.NAMES,
                "SURNAME" to PiiCategory.NAMES,
                "EMAIL" to PiiCategory.EMAILS,
                "TELEPHONENUM" to PiiCategory.PHONE_NUMBERS,
                "STREET" to PiiCategory.ADDRESSES,
                "CITY" to PiiCategory.ADDRESSES,
                "ZIPCODE" to PiiCategory.ADDRESSES,
                "BUILDINGNUM" to PiiCategory.ADDRESSES,
                "SOCIALNUM" to PiiCategory.NATIONAL_IDS,
                "TAXNUM" to PiiCategory.NATIONAL_IDS,
                "PASSPORTNUM" to PiiCategory.NATIONAL_IDS,
                "DRIVERLICENSENUM" to PiiCategory.NATIONAL_IDS,
                "IDCARDNUM" to PiiCategory.NATIONAL_IDS,
                "DATE" to null,
                "TIME" to null,
                "AGE" to null,
                "SEX" to null,
                "GENDER" to null,
                "TITLE" to null,
                "ORGANISATIONPLACEHOLDER" to null,
            )
    }
}
```

- [x] T2.3 tests — **File**: `privacy-benchmark/src/test/kotlin/.../benchmark/corpus/Ai4PrivacyCorpusLoaderTest.kt` — **Setup**: write small JSONL fixtures to `@TempDir`

  | Test | Verifies |
  |------|----------|
  | `loads rows and maps labels` | GIVENNAME→NAMES etc.; excluded DATE span has null category |
  | `drops row with mismatched span value and counts it` | integrity check; `droppedRows == 1` |
  | `unknown label becomes excluded span and is counted` | `unknownLabels["NEWLABEL"] == 1` |
  | `subsample returns evenly spaced N` | `sample=2` of 4 rows → rows 0 and 2 |

  **File**: `.../benchmark/BenchmarkDownloaderTest.kt` — **Setup**: `com.sun.net.httpserver.HttpServer` on an ephemeral port serving fixture bytes; assets with locally computed sha256

  | Test | Verifies |
  |------|----------|
  | `downloads and verifies asset` | file exists, `.part` gone |
  | `rejects checksum mismatch` | throws; no target file left |
  | `skips existing verified file` | server hit count stays 0 |

---

## US3 — Corpus B: seeded UI-shaped synthetic generator

Why: the app's real inputs are short accessibility-node strings with UI context, which Corpus A (prose) does not represent; cards/IBANs/credentials are only measurable here and in Corpus C. The generator builds real `AccessibilityNodeData` screens and derives each sample's `DetectionContext` through the REAL `ContextExtractor` (including geometric nearest-label), so context construction is part of what is measured. Deterministic seed → reproducible published numbers.

Acceptance criteria:
- [x] `UiCorpusGenerator(seed).generate()` is deterministic (identical output for identical seed).
- [x] All 8 languages × 40 screens; every screen mixes positive fields (6), negatives (4), and label nodes; context styles rotate LABELED_BY / GEOMETRIC / RESOURCE_ID / HINT / NONE.
- [x] Generated cards pass Luhn; generated IBANs pass mod-97; gold spans exactly cover injected values.

### T3.1 — Generator

Actions A3.1.1–A3.1.5 create FIVE files in strict dependency order. The split exists because detekt's `TooManyFunctions` caps functions at 11 per class/file (rule verified active via `ServiceModule`'s pre-existing suppression — review finding P59-027) and suppressions are forbidden: kinds/styles (0 functions), random-text primitives (4), checksum-value generators (10), the value factory (10), and the screen generator (4).

Non-derivable constraints baked into the code below (do not change them):
- **Geometry**: `ContextExtractor.nearestLabelFor` measures Euclidean distance between bounds CENTERS with a 300 px cap, and ANY non-editable node with text is a label candidate. Row spacing 320 px + equal label/value widths (centers at x=220) guarantee: own label 70 px (accepted); previous row's value node 320 px (rejected); previous row's label 390 px (rejected). Widening the value node or shrinking the spacing breaks this.
- **Keyword alignment**: the fr NATIONAL_ID label "Numéro de sécurité sociale" deliberately does NOT match `ContextKeywords.NATIONAL_ID` (measures the keyword gap honestly); every other NATIONAL_ID label and all PASSWORD labels DO match their keyword sets; API_KEY's "Access token" matches the "token" credential keyword. hi/te use English UI labels (realistic for Indian-market apps).
- **Negatives**: every maximal digit run stays < 12 chars (below `CardDetector.MIN_CARD_DIGITS`); the UUID forces the first char of every group to `a`–`f` because an all-digit group would let the dash-tolerant `DIGIT_RUN` see a ≥12-digit Luhn candidate.

- [x] A3.1.1 — Create `.../benchmark/corpus/FieldKind.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.benchmark.corpus

import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory

enum class ContextStyle { LABELED_BY, GEOMETRIC, RESOURCE_ID, HINT, NONE }

enum class FieldKind(
    val category: PiiCategory?,
    val editableField: Boolean,
    val resourceWords: String,
) {
    NAME(PiiCategory.NAMES, true, "full_name"),
    EMAIL(PiiCategory.EMAILS, true, "email_address"),
    PHONE(PiiCategory.PHONE_NUMBERS, true, "phone_number"),
    CARD(PiiCategory.CARDS_AND_IBAN, true, "card_number"),
    IBAN(PiiCategory.CARDS_AND_IBAN, true, "iban"),
    NATIONAL_ID(PiiCategory.NATIONAL_IDS, true, "national_id_number"),
    PASSWORD(PiiCategory.CREDENTIALS, true, "password"),
    API_KEY(PiiCategory.CREDENTIALS, true, "access_token"),
    ADDRESS(PiiCategory.ADDRESSES, true, "home_address"),
    SENTENCE_NAME(PiiCategory.NAMES, false, "message_body"),
    ORDER(null, false, "order_number"),
    TRACKING(null, false, "tracking_number"),
    REFERENCE(null, false, "reference_id"),
    INVOICE(null, false, "invoice_number"),
    PLAIN(null, false, "status_text"),
}
```

- [x] A3.1.2 — Create `.../benchmark/corpus/RandomText.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.benchmark.corpus

import kotlin.random.Random

/** Seeded random-text primitives shared by the corpus value generators. */
object RandomText {
    /** `d` → random digit, anything else → random uppercase letter. */
    fun fromPattern(
        rng: Random,
        pattern: String,
    ): String =
        pattern
            .map { symbol -> if (symbol == 'd') '0' + rng.nextInt(DECIMAL) else 'A' + rng.nextInt(ALPHABET) }
            .joinToString("")

    fun fromAlphabet(
        rng: Random,
        alphabet: String,
        count: Int,
    ): String = buildString { repeat(count) { append(alphabet[rng.nextInt(alphabet.length)]) } }

    fun digits(
        rng: Random,
        count: Int,
    ): String = buildString { repeat(count) { append(rng.nextInt(DECIMAL)) } }

    // First char forced to a-f: an all-digit group would let CardDetector's dash-tolerant digit-run
    // regex see a >= 12-digit Luhn candidate across the dashes.
    fun hexGroup(
        rng: Random,
        size: Int,
    ): String =
        buildString {
            append('a' + rng.nextInt(HEX_LETTERS))
            repeat(size - 1) { append(HEX_CHARS[rng.nextInt(HEX_CHARS.length)]) }
        }

    const val DECIMAL = 10
    const val ALPHABET = 26
    const val ALNUM_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    const val UPPER_ALNUM_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val HEX_LETTERS = 6
    private const val HEX_CHARS = "0123456789abcdef"
}
```

- [x] A3.1.3 — Create `.../benchmark/corpus/IdValueGenerators.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.benchmark.corpus

import java.util.Locale
import kotlin.random.Random

/** Generators for checksum-valid financial/identity values (cards, IBANs, national IDs, UUIDs). */
object IdValueGenerators {
    fun card(rng: Random): String {
        val (prefix, length) = CARD_SPECS[rng.nextInt(CARD_SPECS.size)]
        val body = StringBuilder(prefix)
        while (body.length < length - 1) {
            body.append(rng.nextInt(RandomText.DECIMAL))
        }
        body.append(luhnCheckDigit(body.toString()))
        return formatCard(body.toString(), rng)
    }

    /** Check digit for [payload] (PAN without its last digit): double from the rightmost payload digit. */
    private fun luhnCheckDigit(payload: String): Int {
        var sum = 0
        payload.reversed().forEachIndexed { index, char ->
            var digit = char - '0'
            if (index % 2 == 0) {
                digit *= 2
                if (digit > LUHN_NINE) digit -= LUHN_NINE
            }
            sum += digit
        }
        return (RandomText.DECIMAL - sum % RandomText.DECIMAL) % RandomText.DECIMAL
    }

    private fun formatCard(
        digits: String,
        rng: Random,
    ): String {
        val groups = if (digits.length == AMEX_LENGTH) AMEX_GROUPS else FOUR_GROUPS
        return when (rng.nextInt(CARD_FORMAT_COUNT)) {
            0 -> digits
            1 -> groupDigits(digits, groups, " ")
            else -> groupDigits(digits, groups, "-")
        }
    }

    private fun groupDigits(
        digits: String,
        groups: List<Int>,
        separator: String,
    ): String {
        val parts = mutableListOf<String>()
        var cursor = 0
        for (size in groups) {
            if (cursor >= digits.length) break
            val end = minOf(cursor + size, digits.length)
            parts += digits.substring(cursor, end)
            cursor = end
        }
        if (cursor < digits.length) parts += digits.substring(cursor)
        return parts.joinToString(separator)
    }

    fun iban(
        language: String,
        rng: Random,
    ): String {
        val (country, template) = IBAN_SPECS[language] ?: IBAN_SPECS.getValue("en")
        val bban = RandomText.fromPattern(rng, template)
        val check = IBAN_CHECK_BASE - mod97("$bban${country}00")
        val plain = "%s%02d%s".format(Locale.ROOT, country, check, bban)
        return if (rng.nextBoolean()) plain else plain.chunked(IBAN_GROUP).joinToString(" ")
    }

    private fun mod97(input: String): Int {
        var remainder = 0
        for (char in input) {
            val piece = if (char.isDigit()) (char - '0').toString() else ((char - 'A') + LETTER_BASE).toString()
            for (digit in piece) {
                remainder = (remainder * RandomText.DECIMAL + (digit - '0')) % IBAN_MOD_DIVISOR
            }
        }
        return remainder
    }

    fun nationalId(
        language: String,
        rng: Random,
    ): String =
        when (language) {
            "en" -> ssn(rng)
            "fr" -> RandomText.digits(rng, INSEE_DIGITS)
            "de" -> RandomText.digits(rng, STEUER_DIGITS)
            "es" -> dni(rng)
            "it" -> RandomText.fromPattern(rng, CF_PATTERN)
            "nl" -> RandomText.digits(rng, BSN_DIGITS)
            else -> RandomText.fromPattern(rng, PAN_ID_PATTERN)
        }

    private fun ssn(rng: Random): String {
        val area = SSN_AREA_MIN + rng.nextInt(SSN_AREA_RANGE)
        val group = 1 + rng.nextInt(SSN_GROUP_MAX)
        val serial = 1 + rng.nextInt(SSN_SERIAL_MAX)
        return "%03d-%02d-%04d".format(Locale.ROOT, area, group, serial)
    }

    private fun dni(rng: Random): String {
        val number = rng.nextInt(DNI_MAX)
        return "%08d%c".format(Locale.ROOT, number, DNI_LETTERS[number % DNI_LETTERS.length])
    }

    fun uuidLike(rng: Random): String = UUID_GROUP_SIZES.joinToString("-") { RandomText.hexGroup(rng, it) }

    private const val LUHN_NINE = 9
    private const val AMEX_LENGTH = 15
    private const val CARD_FORMAT_COUNT = 3
    private const val LETTER_BASE = 10
    private const val IBAN_MOD_DIVISOR = 97
    private const val IBAN_CHECK_BASE = 98
    private const val IBAN_GROUP = 4
    private const val SSN_AREA_MIN = 100
    private const val SSN_AREA_RANGE = 566
    private const val SSN_GROUP_MAX = 99
    private const val SSN_SERIAL_MAX = 9999
    private const val INSEE_DIGITS = 15
    private const val STEUER_DIGITS = 11
    private const val BSN_DIGITS = 9
    private const val DNI_MAX = 100_000_000
    private const val DNI_LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE"
    private const val CF_PATTERN = "LLLLLLddLddLdddL"
    private const val PAN_ID_PATTERN = "LLLLLddddL"

    // MagicNumber: a plain object gets NO companion/const exemption for list literals, so every
    // element below is a named const (review finding P59-028).
    private const val GROUP_OF_FOUR = 4
    private const val AMEX_GROUP_MIDDLE = 6
    private const val AMEX_GROUP_TAIL = 5
    private const val UUID_GROUP_HEAD = 8
    private const val UUID_GROUP_TAIL = 12
    private const val PAN_LENGTH_STANDARD = 16
    private val UUID_GROUP_SIZES =
        listOf(UUID_GROUP_HEAD, GROUP_OF_FOUR, GROUP_OF_FOUR, GROUP_OF_FOUR, UUID_GROUP_TAIL)
    private val FOUR_GROUPS = listOf(GROUP_OF_FOUR, GROUP_OF_FOUR, GROUP_OF_FOUR, GROUP_OF_FOUR)
    private val AMEX_GROUPS = listOf(GROUP_OF_FOUR, AMEX_GROUP_MIDDLE, AMEX_GROUP_TAIL)
    private val CARD_SPECS =
        listOf(
            "4" to PAN_LENGTH_STANDARD,
            "51" to PAN_LENGTH_STANDARD,
            "34" to AMEX_LENGTH,
            "6011" to PAN_LENGTH_STANDARD,
            "5019" to PAN_LENGTH_STANDARD,
        )
    private val IBAN_SPECS =
        mapOf(
            "en" to ("GB" to "aaaadddddddddddddd"),
            "fr" to ("FR" to "ddddddddddaaaaaaaaaaadd"),
            "de" to ("DE" to "dddddddddddddddddd"),
            "es" to ("ES" to "dddddddddddddddddddd"),
            "it" to ("IT" to "addddddddddaaaaaaaaaaaa"),
            "nl" to ("NL" to "aaaadddddddddd"),
        )
}
```

- [x] A3.1.4 — Create `.../benchmark/corpus/UiValueFactory.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.benchmark.corpus

import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale
import kotlin.random.Random

/** One (value, gold spans) pair per [FieldKind]; checksum values delegate to [IdValueGenerators]. */
class UiValueFactory {
    private val phoneUtil = PhoneNumberUtil.getInstance()

    fun valueFor(
        kind: FieldKind,
        language: String,
        rng: Random,
    ): Pair<String, List<GoldSpan>> =
        if (kind == FieldKind.SENTENCE_NAME) {
            sentenceWithName(language, rng)
        } else if (kind.category != null) {
            positiveValue(kind, language, rng)
        } else {
            negativeValue(kind, rng) to emptyList()
        }

    private fun positiveValue(
        kind: FieldKind,
        language: String,
        rng: Random,
    ): Pair<String, List<GoldSpan>> {
        val value =
            when (kind) {
                FieldKind.NAME -> fullName(language, rng)
                FieldKind.EMAIL -> email(rng)
                FieldKind.PHONE -> phone(language, rng)
                FieldKind.CARD -> IdValueGenerators.card(rng)
                FieldKind.IBAN -> IdValueGenerators.iban(language, rng)
                FieldKind.NATIONAL_ID -> IdValueGenerators.nationalId(language, rng)
                FieldKind.PASSWORD ->
                    PASSWORD_POOL[rng.nextInt(PASSWORD_POOL.size)] + RandomText.digits(rng, PASSWORD_SUFFIX)
                FieldKind.API_KEY -> apiKey(rng)
                FieldKind.ADDRESS -> address(language, rng)
                else -> error("not a positive field kind: $kind")
            }
        return value to listOf(GoldSpan(0, value.length, requireNotNull(kind.category)))
    }

    private fun negativeValue(
        kind: FieldKind,
        rng: Random,
    ): String =
        when (kind) {
            FieldKind.ORDER -> "ORD-" + RandomText.digits(rng, ORDER_DIGITS)
            FieldKind.TRACKING -> "1Z999AA1" + RandomText.digits(rng, TRACKING_DIGITS)
            FieldKind.REFERENCE -> IdValueGenerators.uuidLike(rng)
            FieldKind.INVOICE ->
                "INV-" + RandomText.digits(rng, INVOICE_GROUP_1) + "-" + RandomText.digits(rng, INVOICE_GROUP_2)
            FieldKind.PLAIN -> PLAIN_SENTENCES[rng.nextInt(PLAIN_SENTENCES.size)]
            else -> error("not a negative field kind: $kind")
        }

    private fun sentenceWithName(
        language: String,
        rng: Random,
    ): Pair<String, List<GoldSpan>> {
        val template = SENTENCE_TEMPLATES.getValue(language)
        val name = fullName(language, rng)
        val start = template.indexOf(NAME_PLACEHOLDER)
        val text = template.replace(NAME_PLACEHOLDER, name)
        return text to listOf(GoldSpan(start, start + name.length, PiiCategory.NAMES))
    }

    private fun fullName(
        language: String,
        rng: Random,
    ): String {
        val pool = NAME_POOLS.getValue(language)
        return "${pool.given[rng.nextInt(pool.given.size)]} ${pool.surnames[rng.nextInt(pool.surnames.size)]}"
    }

    private fun email(rng: Random): String {
        val given = ASCII_GIVEN[rng.nextInt(ASCII_GIVEN.size)].lowercase(Locale.ROOT)
        val surname = ASCII_SURNAMES[rng.nextInt(ASCII_SURNAMES.size)].lowercase(Locale.ROOT)
        return "$given.$surname@${DOMAINS[rng.nextInt(DOMAINS.size)]}"
    }

    private fun phone(
        language: String,
        rng: Random,
    ): String {
        val regions = PHONE_REGIONS.getValue(language)
        val region = regions[rng.nextInt(regions.size)]
        val number =
            phoneUtil.getExampleNumberForType(region, PhoneNumberUtil.PhoneNumberType.MOBILE)
                ?: checkNotNull(phoneUtil.getExampleNumber(region)) { "no example number for $region" }
        return phoneUtil.format(number, PHONE_FORMATS[rng.nextInt(PHONE_FORMATS.size)])
    }

    private fun apiKey(rng: Random): String =
        when (rng.nextInt(API_KEY_SHAPES)) {
            0 -> "sk-live-" + RandomText.fromAlphabet(rng, RandomText.ALNUM_CHARS, SK_KEY_LENGTH)
            1 -> "ghp_" + RandomText.fromAlphabet(rng, RandomText.ALNUM_CHARS, GHP_KEY_LENGTH)
            else -> "AKIA" + RandomText.fromAlphabet(rng, RandomText.UPPER_ALNUM_CHARS, AKIA_KEY_LENGTH)
        }

    private fun address(
        language: String,
        rng: Random,
    ): String {
        val pool = ADDRESS_POOLS.getValue(language)
        val street = pool.streets[rng.nextInt(pool.streets.size)]
        val city = pool.cities[rng.nextInt(pool.cities.size)]
        return "${1 + rng.nextInt(MAX_BUILDING_NUM)} $street, ${zip(language, rng)} $city"
    }

    private fun zip(
        language: String,
        rng: Random,
    ): String =
        when (language) {
            "nl" -> RandomText.digits(rng, NL_ZIP_DIGITS) + " " + RandomText.fromPattern(rng, "LL")
            "hi", "te" -> RandomText.digits(rng, IN_ZIP_DIGITS)
            else -> RandomText.digits(rng, DEFAULT_ZIP_DIGITS)
        }

    private data class NamePool(
        val given: List<String>,
        val surnames: List<String>,
    )

    private data class AddressPool(
        val streets: List<String>,
        val cities: List<String>,
    )

    companion object {
        private const val API_KEY_SHAPES = 3
        private const val SK_KEY_LENGTH = 24
        private const val GHP_KEY_LENGTH = 36
        private const val AKIA_KEY_LENGTH = 16
        private const val PASSWORD_SUFFIX = 2
        private const val ORDER_DIGITS = 9
        private const val TRACKING_DIGITS = 8
        private const val INVOICE_GROUP_1 = 4
        private const val INVOICE_GROUP_2 = 5
        private const val NL_ZIP_DIGITS = 4
        private const val IN_ZIP_DIGITS = 6
        private const val DEFAULT_ZIP_DIGITS = 5
        private const val MAX_BUILDING_NUM = 200
        private const val NAME_PLACEHOLDER = "%NAME%"

        private val PHONE_FORMATS =
            listOf(
                PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL,
                PhoneNumberUtil.PhoneNumberFormat.NATIONAL,
                PhoneNumberUtil.PhoneNumberFormat.E164,
            )
        private val PHONE_REGIONS =
            mapOf(
                "en" to listOf("US", "GB"),
                "fr" to listOf("FR"),
                "de" to listOf("DE"),
                "es" to listOf("ES"),
                "it" to listOf("IT"),
                "nl" to listOf("NL"),
                "hi" to listOf("IN"),
                "te" to listOf("IN"),
            )
        private val DOMAINS = listOf("example.com", "mail.example.org", "corp.example.net")
        private val ASCII_GIVEN = listOf("James", "Emma", "Lucas", "Sofia", "Arjun", "Priya", "Marco", "Nina")
        private val ASCII_SURNAMES =
            listOf("Miller", "Rossi", "Silva", "Novak", "Sharma", "Reddy", "Weber", "Janssen")
        private val PASSWORD_POOL = listOf("Tr0ub4dor&", "S3cure!pass", "correct-horse-B1", "Xk9#mQpL")
        private val PLAIN_SENTENCES =
            listOf(
                "Settings saved successfully",
                "Your download has finished",
                "Sync completed without errors",
                "Update available for two apps",
            )
        private val SENTENCE_TEMPLATES =
            mapOf(
                "en" to "Please call %NAME% when the meeting ends",
                "fr" to "Merci d'appeler %NAME% après la réunion",
                "de" to "Bitte rufen Sie %NAME% nach dem Termin an",
                "es" to "Por favor llama a %NAME% después de la reunión",
                "it" to "Chiama %NAME% dopo la riunione",
                "nl" to "Bel %NAME% na de vergadering",
                "hi" to "कृपया बैठक के बाद %NAME% को फोन करें",
                "te" to "సమావేశం తర్వాత %NAME% కి కాల్ చేయండి",
            )
        private val NAME_POOLS =
            mapOf(
                "en" to
                    NamePool(
                        listOf("Oliver", "Amelia", "Henry", "Isla", "George", "Freya"),
                        listOf("Walker", "Hughes", "Bennett", "Foster", "Dawson", "Pearce"),
                    ),
                "fr" to
                    NamePool(
                        listOf("Léa", "Hugo", "Chloé", "Louis", "Manon", "Jules"),
                        listOf("Moreau", "Lefèvre", "Garnier", "Chevalier", "Perrot", "Blanchard"),
                    ),
                "de" to
                    NamePool(
                        listOf("Lena", "Finn", "Marie", "Jonas", "Clara", "Felix"),
                        listOf("Schneider", "Hoffmann", "Wagner", "Becker", "Krüger", "Vogel"),
                    ),
                "es" to
                    NamePool(
                        listOf("Lucía", "Mateo", "Valeria", "Diego", "Carmen", "Álvaro"),
                        listOf("García", "Fernández", "Navarro", "Iglesias", "Molina", "Serrano"),
                    ),
                "it" to
                    NamePool(
                        listOf("Giulia", "Lorenzo", "Aurora", "Matteo", "Elisa", "Davide"),
                        listOf("Ricci", "Marino", "Greco", "Gallo", "Ferrara", "Rinaldi"),
                    ),
                "nl" to
                    NamePool(
                        listOf("Sanne", "Daan", "Fleur", "Bram", "Lotte", "Thijs"),
                        listOf("de Vries", "van Dijk", "Bakker", "Visser", "Smit", "Mulder"),
                    ),
                "hi" to
                    NamePool(
                        listOf("आरव", "अनन्या", "विहान", "दिया", "कबीर", "मीरा"),
                        listOf("शर्मा", "वर्मा", "गुप्ता", "सिंह", "मेहता", "जोशी"),
                    ),
                "te" to
                    NamePool(
                        listOf("ఆరవ్", "సాన్వి", "విహాన్", "ఆద్య", "రేయాన్", "ఇషా"),
                        listOf("రెడ్డి", "రావు", "నాయుడు", "శర్మ", "చౌదరి", "వర్మ"),
                    ),
            )
        private val ADDRESS_POOLS =
            mapOf(
                "en" to
                    AddressPool(
                        listOf("Maple Avenue", "Church Lane", "High Street"),
                        listOf("Springfield", "Riverton", "Oakdale"),
                    ),
                "fr" to
                    AddressPool(
                        listOf("Rue de la Paix", "Avenue Victor Hugo", "Boulevard Saint-Michel"),
                        listOf("Lyon", "Nantes", "Lille"),
                    ),
                "de" to
                    AddressPool(
                        listOf("Hauptstraße", "Gartenweg", "Bahnhofstraße"),
                        listOf("Freiburg", "Kassel", "Augsburg"),
                    ),
                "es" to
                    AddressPool(
                        listOf("Calle Mayor", "Avenida del Sol", "Paseo de Gracia"),
                        listOf("Sevilla", "Valencia", "Zaragoza"),
                    ),
                "it" to
                    AddressPool(
                        listOf("Via Roma", "Corso Italia", "Via Garibaldi"),
                        listOf("Torino", "Bologna", "Verona"),
                    ),
                "nl" to
                    AddressPool(
                        listOf("Kerkstraat", "Dorpsstraat", "Molenweg"),
                        listOf("Utrecht", "Haarlem", "Leiden"),
                    ),
                "hi" to
                    AddressPool(
                        listOf("MG Road", "Nehru Street", "Station Road"),
                        listOf("Pune", "Jaipur", "Lucknow"),
                    ),
                "te" to
                    AddressPool(
                        listOf("Tank Bund Road", "Jubilee Hills Road", "NTR Marg"),
                        listOf("Hyderabad", "Vijayawada", "Warangal"),
                    ),
            )
    }
}
```

- [x] A3.1.5 — Create `.../benchmark/corpus/UiCorpusGenerator.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.benchmark.corpus

import com.danielealbano.androidremotecontrolmcp.privacy.ContextExtractor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import kotlin.random.Random

/**
 * Seeded UI-shaped corpus: builds real [AccessibilityNodeData] screens (label/value rows across five
 * context styles) and derives each sample's context through the REAL [ContextExtractor], so context
 * construction — including geometric nearest-label — is part of what the benchmark measures.
 */
class UiCorpusGenerator(
    private val seed: Long = DEFAULT_SEED,
) {
    private val contextExtractor = ContextExtractor()
    private val values = UiValueFactory()

    fun generate(): LoadedCorpus {
        val rng = Random(seed)
        val samples = mutableListOf<BenchmarkSample>()
        for (language in LANGUAGES) {
            repeat(SCREENS_PER_LANGUAGE) { screen ->
                samples += generateScreen(language, screen, rng)
            }
        }
        return LoadedCorpus("ui-synthetic", samples, droppedRows = 0, unknownLabels = emptyMap())
    }

    private fun generateScreen(
        language: String,
        screen: Int,
        rng: Random,
    ): List<BenchmarkSample> {
        val kinds =
            (POSITIVE_KINDS.shuffled(rng).take(POSITIVE_ROWS) + NEGATIVE_KINDS.shuffled(rng).take(NEGATIVE_ROWS))
                .shuffled(rng)
        val rows =
            kinds.mapIndexed { index, kind ->
                val style = ContextStyle.entries[index % ContextStyle.entries.size]
                buildRow(RowSpec(language, screen, index, kind, style), rng)
            }
        val root =
            AccessibilityNodeData(
                id = "s$screen-root",
                bounds = BoundsData(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT),
                visible = true,
                children = rows.flatMap { it.nodes },
            )
        val goldByNode = rows.flatMap { it.goldByNode.entries }.associate { it.key to it.value }
        val nearest = contextExtractor.computeNearestLabels(root)
        val samples = mutableListOf<BenchmarkSample>()

        fun visit(node: AccessibilityNodeData) {
            val text = node.text
            if (!text.isNullOrBlank()) {
                samples +=
                    BenchmarkSample(
                        id = "b-$language-${node.id}",
                        text = text,
                        context = contextExtractor.extract(node, nearest[node.id]),
                        gold = goldByNode[node.id].orEmpty(),
                        language = language,
                    )
            }
            node.children.forEach(::visit)
        }
        root.children.forEach(::visit)
        return samples
    }

    private fun buildRow(
        spec: RowSpec,
        rng: Random,
    ): Row {
        val y = TOP_MARGIN + spec.index * ROW_SPACING
        val idBase = "s${spec.screen}-r${spec.index}"
        val label = labelFor(spec.kind, spec.language)
        val (value, gold) = values.valueFor(spec.kind, spec.language, rng)
        val nodes = mutableListOf<AccessibilityNodeData>()
        if (spec.style == ContextStyle.GEOMETRIC || spec.style == ContextStyle.LABELED_BY) {
            nodes +=
                AccessibilityNodeData(
                    id = "$idBase-label",
                    text = label,
                    bounds = BoundsData(LEFT, y, NODE_RIGHT, y + LABEL_HEIGHT),
                    visible = true,
                )
        }
        nodes +=
            AccessibilityNodeData(
                id = "$idBase-value",
                text = value,
                bounds = BoundsData(LEFT, y + VALUE_TOP_OFFSET, NODE_RIGHT, y + VALUE_BOTTOM_OFFSET),
                editable = spec.kind.editableField && spec.style != ContextStyle.NONE,
                isPassword = spec.kind == FieldKind.PASSWORD,
                labeledByText = label.takeIf { spec.style == ContextStyle.LABELED_BY },
                hintText = label.takeIf { spec.style == ContextStyle.HINT },
                resourceId =
                    ("com.example.app:id/" + spec.kind.resourceWords)
                        .takeIf { spec.style == ContextStyle.RESOURCE_ID },
                visible = true,
            )
        return Row(nodes, mapOf("$idBase-value" to gold))
    }

    private fun labelFor(
        kind: FieldKind,
        language: String,
    ): String {
        val labels = LABELS.getValue(kind)
        return labels[language] ?: labels.getValue("en")
    }

    private data class RowSpec(
        val language: String,
        val screen: Int,
        val index: Int,
        val kind: FieldKind,
        val style: ContextStyle,
    )

    private data class Row(
        val nodes: List<AccessibilityNodeData>,
        val goldByNode: Map<String, List<GoldSpan>>,
    )

    companion object {
        const val DEFAULT_SEED = 20260803L
        const val SCREENS_PER_LANGUAGE = 40
        val LANGUAGES = listOf("en", "fr", "de", "es", "it", "nl", "hi", "te")

        private const val POSITIVE_ROWS = 6
        private const val NEGATIVE_ROWS = 4
        private const val TOP_MARGIN = 100
        private const val ROW_SPACING = 320
        private const val SCREEN_WIDTH = 1080
        private const val SCREEN_HEIGHT = 3500
        private const val LEFT = 40
        private const val NODE_RIGHT = 400
        private const val LABEL_HEIGHT = 40
        private const val VALUE_TOP_OFFSET = 50
        private const val VALUE_BOTTOM_OFFSET = 130

        private val LABELS: Map<FieldKind, Map<String, String>> =
            mapOf(
                FieldKind.NAME to
                    mapOf(
                        "en" to "Full name",
                        "fr" to "Nom complet",
                        "de" to "Vollständiger Name",
                        "es" to "Nombre completo",
                        "it" to "Nome completo",
                        "nl" to "Volledige naam",
                    ),
                FieldKind.EMAIL to
                    mapOf(
                        "en" to "Email",
                        "fr" to "E-mail",
                        "de" to "E-Mail",
                        "es" to "Correo electrónico",
                        "it" to "Email",
                        "nl" to "E-mail",
                    ),
                FieldKind.PHONE to
                    mapOf(
                        "en" to "Phone number",
                        "fr" to "Téléphone",
                        "de" to "Telefonnummer",
                        "es" to "Teléfono",
                        "it" to "Telefono",
                        "nl" to "Telefoonnummer",
                    ),
                FieldKind.CARD to
                    mapOf(
                        "en" to "Card number",
                        "fr" to "Numéro de carte",
                        "de" to "Kartennummer",
                        "es" to "Número de tarjeta",
                        "it" to "Numero carta",
                        "nl" to "Kaartnummer",
                    ),
                FieldKind.IBAN to mapOf("en" to "IBAN"),
                FieldKind.NATIONAL_ID to
                    mapOf(
                        "en" to "Social security number",
                        "fr" to "Numéro de sécurité sociale",
                        "de" to "Steuernummer",
                        "es" to "DNI",
                        "it" to "Codice fiscale",
                        "nl" to "BSN",
                        "hi" to "Tax ID",
                        "te" to "Tax ID",
                    ),
                FieldKind.PASSWORD to
                    mapOf(
                        "en" to "Password",
                        "fr" to "Mot de passe",
                        "de" to "Passwort",
                        "es" to "Contraseña",
                        "it" to "Password",
                        "nl" to "Wachtwoord",
                    ),
                FieldKind.API_KEY to mapOf("en" to "Access token"),
                FieldKind.ADDRESS to
                    mapOf(
                        "en" to "Address",
                        "fr" to "Adresse",
                        "de" to "Adresse",
                        "es" to "Dirección",
                        "it" to "Indirizzo",
                        "nl" to "Adres",
                    ),
                FieldKind.SENTENCE_NAME to mapOf("en" to "Message"),
                FieldKind.ORDER to mapOf("en" to "Order number"),
                FieldKind.TRACKING to mapOf("en" to "Tracking number"),
                FieldKind.REFERENCE to mapOf("en" to "Reference"),
                FieldKind.INVOICE to mapOf("en" to "Invoice number"),
                FieldKind.PLAIN to mapOf("en" to "Status"),
            )
        private val POSITIVE_KINDS = FieldKind.entries.filter { it.category != null }
        private val NEGATIVE_KINDS = FieldKind.entries.filter { it.category == null }
    }
}
```

- [x] T3.1 tests — **File**: `.../benchmark/corpus/UiCorpusGeneratorTest.kt`

  | Test | Verifies |
  |------|----------|
  | `same seed produces identical corpus` | two `generate()` runs deep-equal |
  | `different seed produces different corpus` | inequality sanity |
  | `gold spans are in bounds and non-blank` | every span within text, substring non-blank |
  | `generated cards pass luhn` | independent Luhn check over CARD samples (find via category+16/15-digit shape) |
  | `generated ibans pass mod97` | independent mod-97 == 1 check |
  | `geometric style resolves nearest label` | a GEOMETRIC row sample has `context.labelText` equal to its label |
  | `labeled-by style sets label text directly` | a LABELED_BY sample's `context.labelText` equals the label without geometry |
  | `resource id style yields context words` | RESOURCE_ID sample's `context.resourceIdWords` are the split kind words; no labelText |
  | `hint style sets hint text` | HINT sample's `context.hintText` equals the label; no labelText |
  | `none style has empty context` | NONE sample's `context.contextText()` is empty and node not editable |
  | `every screen has exactly six gold-bearing samples` | group samples by id prefix `b-<lang>-s<screen>-`; exactly 6 samples with non-empty gold per screen (the 6 positive rows; negatives and label nodes are gold-free) |
  | `password samples are structural` | `context.isPassword` true; gold CREDENTIALS full span |
  | `negative digit runs stay below card minimum` | max digit-run length (dash/space-joined) of every negative sample < 12 |
  | `corpus covers all languages and categories` | 8 languages; every `PiiCategory` has ≥1 gold span |

---

## US4 — Corpus C: adversarial suite

Why: quantify known-hard cases (obfuscation, partial values, lookalikes) that neither A (in-domain prose) nor B (well-formed values) stress.

Acceptance criteria:
- [x] `corpus_c.jsonl` resource with EXACTLY the group counts below (126 cases), loaded into `BenchmarkSample`s; validation test enforces schema, bounds, unique ids, and per-group counts.

### T4.1 — Schema, loader, cases

- [x] A4.1.1 — Create `.../benchmark/corpus/AdversarialCorpusLoader.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.benchmark.corpus

import com.danielealbano.androidremotecontrolmcp.privacy.DetectionContext
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CorpusCSpan(
    val start: Int,
    val end: Int,
    val category: String,
)

@Serializable
data class CorpusCCase(
    val id: String,
    val group: String,
    val text: String,
    val language: String = "en",
    val labelText: String? = null,
    val hintText: String? = null,
    val resourceIdWords: List<String> = emptyList(),
    val isPassword: Boolean = false,
    val isEditable: Boolean = false,
    val gold: List<CorpusCSpan> = emptyList(),
)

/** Loads the checked-in adversarial suite from the module resources. */
class AdversarialCorpusLoader {
    private val json = Json { ignoreUnknownKeys = false }

    fun load(): LoadedCorpus {
        val resource =
            checkNotNull(javaClass.classLoader.getResourceAsStream(RESOURCE)) { "$RESOURCE missing" }
        val samples =
            resource.bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.map { toSample(it) }.toList()
            }
        return LoadedCorpus("adversarial", samples, droppedRows = 0, unknownLabels = emptyMap())
    }

    private fun toSample(line: String): BenchmarkSample {
        val case = json.decodeFromString(CorpusCCase.serializer(), line)
        val context =
            DetectionContext(
                resourceIdWords = case.resourceIdWords,
                hintText = case.hintText,
                labelText = case.labelText,
                isPassword = case.isPassword,
                isEditable = case.isEditable,
            )
        val gold = case.gold.map { GoldSpan(it.start, it.end, PiiCategory.valueOf(it.category)) }
        return BenchmarkSample("c-${case.id}", case.text, context, gold, case.language)
    }

    companion object {
        const val RESOURCE = "corpus_c.jsonl"
    }
}
```

- [x] A4.1.2 — Create `privacy-benchmark/src/main/resources/corpus_c.jsonl` with EXACTLY these groups/counts (author each case following the group spec; ids `"<group>-<nn>"`; gold offsets computed against the final text):

| group | count | spec |
|---|---|---|
| `email-obfuscated` | 10 | `j.doe [at] example [dot] com`, `(at)`/`(dot)`, ` AT `, spaced `@`; gold EMAILS over the full obfuscated address |
| `email-exotic` | 8 | plus-addressing, quoted local part, IDN domain, long TLD, uppercase; gold EMAILS |
| `card-formats` | 12 | Luhn-valid PANs: 4-4-4-4 spaced, dashed, unspaced, Amex 4-6-5, 19-digit, embedded mid-sentence, with `labelText="Card number"` on half of them; gold CARDS_AND_IBAN over digits incl. separators |
| `card-partial` | 6 | "card ending 1111", "last four 6789"; gold CARDS_AND_IBAN over the digit run (documents expected misses) |
| `iban-variants` | 8 | valid IBANs lowercase, space-grouped, embedded in sentence, with/without label; gold CARDS_AND_IBAN |
| `phone-variants` | 12 | international, national w/o context, dotted, with extension, hi/te-region numbers, `labelText="Phone"` on half; gold PHONE_NUMBERS |
| `national-id-context` | 8 | SSN/DNI/BSN/codice fiscale WITH matching labelText; gold NATIONAL_IDS |
| `national-id-bare` | 6 | same value shapes, NO context; gold NATIONAL_IDS (measures model-only recall) |
| `credential-plaintext` | 10 | API keys/JWT/bearer tokens in plain non-editable text, "my password is hunter2" in chat text; gold CREDENTIALS (measures the structural-only gap) |
| `name-unicode` | 10 | diacritics (José, Müller, Çelik), Devanagari, Telugu, CJK, hyphenated/apostrophe names; gold NAMES |
| `name-hard` | 6 | lowercase names, names that are common words ("Bill Gates" vs "bill"), initials; gold NAMES |
| `address-freetext` | 8 | full addresses in prose across languages; gold ADDRESSES |
| `mixed-language` | 6 | two languages in one text with PII in each; gold per span |
| `lookalike-negatives` | 16 | order/tracking/invoice numbers, UUIDs, MAC addresses, ISBNs, version strings, timestamps, prices, coordinates; gold `[]` |

- [x] T4.1 tests — **File**: `.../benchmark/corpus/AdversarialCorpusTest.kt`

  | Test | Verifies |
  |------|----------|
  | `loads all cases with exact group counts` | 126 total AND each of the 14 groups has exactly its specified count |
  | `ids are unique and spans are valid` | unique ids; every span in bounds with a non-blank substring; categories parse; every `group` value is one of the 14 defined groups |
  | `negative group has no gold` | `lookalike-negatives` all gold-free; every non-negative group has ≥1 gold span per case |

  (These load-time assertions are the ONLY guard on the hand-authored resource — review finding P59-026 — so they MUST stay exhaustive.)

---

## US5 — Layer runners, scorer, report, CLI main

Acceptance criteria:
- [x] `deterministic` / `model` / `full` layers all run PRODUCTION classes (no reimplemented pipeline logic).
- [x] Scorer implements exactly the plan's scoring rules; report written as `report.json` + `report.md`.
- [x] `BenchmarkMain` downloads assets, warms up, runs selected corpora×layers, prints a summary, writes reports, and exits non-zero on failure.

### T5.1 — Pipeline factory + layer runner

- [x] A5.1.1 — Create `.../benchmark/BenchmarkPipeline.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.benchmark

import com.danielealbano.androidremotecontrolmcp.benchmark.corpus.BenchmarkSample
import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.privacy.ContextExtractor
import com.danielealbano.androidremotecontrolmcp.privacy.DeterministicEngine
import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection
import com.danielealbano.androidremotecontrolmcp.privacy.PseudonymStore
import com.danielealbano.androidremotecontrolmcp.privacy.Redactor
import com.danielealbano.androidremotecontrolmcp.privacy.RedactionEngine
import com.danielealbano.androidremotecontrolmcp.privacy.TextItem
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.CardDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.CredentialDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.EmailDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.IbanDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.NationalIdDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.PhoneDetector
import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelStore
import com.danielealbano.androidremotecontrolmcp.privacy.ner.NerCache
import com.danielealbano.androidremotecontrolmcp.privacy.ner.NerEngine
import com.danielealbano.androidremotecontrolmcp.privacy.ner.NerSegment
import com.danielealbano.androidremotecontrolmcp.privacy.ner.OrtPiiModelRunner
import java.io.File

enum class Layer { DETERMINISTIC, MODEL, FULL }

/**
 * Production pipeline components wired manually (no DI) against the benchmark cache dir. A FRESH
 * NerEngine + NerCache is built per layer run so per-layer durations stay comparable — no
 * cross-layer cache warm-up (review finding P59-012). Tests inject a fake [PiiModelInference] to
 * exercise the layer plumbing without the real model (review finding P59-007).
 */
class BenchmarkPipeline(
    cacheDir: File,
    private val inferenceOverride: PiiModelInference? = null,
) {
    val store = PrivacyModelStore(cacheDir)
    val runner = OrtPiiModelRunner(store)
    private val deterministicEngine =
        DeterministicEngine(
            CredentialDetector(),
            CardDetector(),
            IbanDetector(),
            EmailDetector(),
            PhoneDetector(),
            NationalIdDetector(),
        )
    private val contextExtractor = ContextExtractor()
    private val redactor = Redactor(PseudonymStore())

    /** All-categories-on config: the published numbers measure the full default protection surface. */
    val fullConfig = PrivacyModeConfig(enabled = true)

    private fun newNerEngine(): NerEngine = NerEngine(inferenceOverride ?: runner, NerCache())

    private fun newRedactionEngine(): RedactionEngine =
        RedactionEngine(deterministicEngine, newNerEngine(), contextExtractor, redactor)

    suspend fun detect(
        layer: Layer,
        samples: List<BenchmarkSample>,
    ): List<List<PiiDetection>> =
        when (layer) {
            Layer.DETERMINISTIC ->
                samples.map {
                    DeterministicEngine.mergeOverlaps(deterministicEngine.detectAll(it.text, it.context))
                }
            Layer.MODEL -> {
                val engine = newNerEngine()
                samples.chunked(CHUNK).flatMap { detectModelChunk(engine, it) }
            }
            Layer.FULL -> detectFull(samples)
        }

    /**
     * FULL layer: production detections ([RedactionEngine.detect]) + production rendering
     * ([Redactor.apply]) in a SINGLE model pass — the exact composition of
     * [RedactionEngine.redactTexts] without re-running inference for the redacted texts.
     */
    suspend fun detectAndRedactFull(
        samples: List<BenchmarkSample>,
    ): Pair<List<List<PiiDetection>>, List<String>> {
        val detections = detectFull(samples)
        val redacted =
            samples.mapIndexed { index, sample ->
                redactor.apply(sample.text, detections[index], fullConfig)
            }
        return detections to redacted
    }

    private suspend fun detectFull(samples: List<BenchmarkSample>): List<List<PiiDetection>> {
        val engine = newRedactionEngine()
        return samples.chunked(CHUNK).flatMap { chunk ->
            engine.detect(chunk.map { TextItem(it.text, it.context) }, fullConfig)
        }
    }

    private suspend fun detectModelChunk(
        engine: NerEngine,
        chunk: List<BenchmarkSample>,
    ): List<List<PiiDetection>> {
        val segments =
            chunk.mapIndexedNotNull { index, sample ->
                if (sample.text.isBlank()) {
                    null
                } else {
                    NerSegment(index.toString(), sample.context.contextText(), sample.text)
                }
            }
        if (segments.isEmpty()) return chunk.map { emptyList() }
        val results = engine.detect(segments)
        return chunk.indices.map { DeterministicEngine.mergeOverlaps(results[it.toString()].orEmpty()) }
    }

    companion object {
        const val CHUNK = 256
    }
}
```

(Add `import com.danielealbano.androidremotecontrolmcp.privacy.ner.PiiModelInference` to the import list above.)

- [x] T5.1 tests — **File**: `.../benchmark/BenchmarkPipelineTest.kt` — **Setup**: `BenchmarkPipeline(tempDir, fakeInference)` where `fakeInference` is a hand-written `PiiModelInference` stub returning preset `NerResult`s; the real model/store are never touched

  | Test | Verifies |
  |------|----------|
  | `deterministic layer detects email without model` | email sample → EMAILS span; stub never invoked |
  | `model layer maps chunked predictions back to samples` | 3 samples with a blank middle one → blank gets empty, others get the stubbed spans at the right indices |
  | `model layer keying survives multiple chunks` | CHUNK + 1 samples map back to the correct samples across the chunk boundary |
  | `full layer merges deterministic and model and redacts` | `detectAndRedactFull` output contains both sources' spans and redacted texts with placeholders |

### T5.2 — Scorer

- [x] A5.2.1 — Create `.../benchmark/scoring/Scorer.kt` implementing EXACTLY the plan's scoring rules:

```kotlin
package com.danielealbano.androidremotecontrolmcp.benchmark.scoring

import com.danielealbano.androidremotecontrolmcp.benchmark.corpus.BenchmarkSample
import com.danielealbano.androidremotecontrolmcp.benchmark.corpus.GoldSpan
import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection
import kotlinx.serialization.Serializable

@Serializable
data class MetricValues(
    val tp: Int,
    val fp: Int,
    val fn: Int,
    val precision: Double,
    val recall: Double,
    val f1: Double,
    val fBeta2: Double,
) {
    companion object {
        fun from(
            tp: Int,
            fp: Int,
            fn: Int,
        ): MetricValues {
            val p = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
            val r = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)
            val f1 = if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)
            val fbDenominator = BETA_SQUARED * p + r
            val fb = if (fbDenominator == 0.0) 0.0 else (1 + BETA_SQUARED) * p * r / fbDenominator
            return MetricValues(tp, fp, fn, p, r, f1, fb)
        }

        /** β = 2 (recall-weighted Fβ per the plan's scoring rules), so β² = 4. */
        private const val BETA_SQUARED = 4.0
    }
}

@Serializable
data class CategoryScore(
    val category: String,
    val goldSpans: Int,
    val partial: MetricValues,
    val strict: MetricValues,
    val goldChars: Long,
    val leakedChars: Long,
    val leakRate: Double,
)

@Serializable
data class LanguageScore(
    val language: String,
    val samples: Int,
    val partial: MetricValues,
    val leakRate: Double,
)

@Serializable
data class LayerScore(
    val layer: String,
    val samples: Int,
    val durationMs: Long,
    val categories: List<CategoryScore>,
    val microPartial: MetricValues,
    val microStrict: MetricValues,
    val macroPartialF1: Double,
    val macroFBeta2: Double,
    val leakRate: Double,
    val residualValueLeaks: Int? = null,
    val perLanguage: List<LanguageScore>,
)

class Scorer {
    fun score(
        layer: String,
        samples: List<BenchmarkSample>,
        predictions: List<List<PiiDetection>>,
        durationMs: Long,
        redactedTexts: List<String>? = null,
    ): LayerScore {
        require(samples.size == predictions.size) { "samples/predictions size mismatch" }
        val categories = sortedMapOf<String, CategoryAcc>()
        val languages = sortedMapOf<String, LanguageAcc>()
        var residual = 0
        samples.forEachIndexed { index, sample ->
            val inScope = sample.gold.filter { it.category != null }
            val excluded = sample.gold.filter { it.category == null }
            val usable = usablePredictions(predictions[index], inScope, excluded)
            val language = languages.getOrPut(sample.language) { LanguageAcc() }
            language.samples++
            scoreGold(inScope, usable, predictions[index], categories, language)
            scoreFalsePositives(usable, inScope, categories, language)
            if (redactedTexts != null && hasResidualLeak(sample, inScope, redactedTexts[index])) {
                residual++
            }
        }
        val meta = LayerMeta(layer, samples.size, durationMs)
        return assemble(meta, categories, languages, redactedTexts?.let { residual })
    }

    /** Ignore rule: overlaps an excluded span AND matches no same-category in-scope gold span. */
    private fun usablePredictions(
        predictions: List<PiiDetection>,
        inScope: List<GoldSpan>,
        excluded: List<GoldSpan>,
    ): List<PiiDetection> =
        predictions.filterNot { p ->
            excluded.any { overlaps(p.start, p.end, it.start, it.end) } &&
                inScope.none { it.category == p.category && overlaps(p.start, p.end, it.start, it.end) }
        }

    private fun scoreGold(
        inScope: List<GoldSpan>,
        usable: List<PiiDetection>,
        allPredictions: List<PiiDetection>,
        categories: MutableMap<String, CategoryAcc>,
        language: LanguageAcc,
    ) {
        for (gold in inScope) {
            val category = requireNotNull(gold.category)
            val acc = categories.getOrPut(category.name) { CategoryAcc() }
            val length = (gold.end - gold.start).toLong()
            // Char-leak counts coverage by ANY prediction (even ignore-filtered ones): in production
            // every emitted span redacts, so a miscategorized-but-redacted char is not leaked.
            val leaked = length - coveredChars(gold, allPredictions)
            acc.goldSpans++
            acc.goldChars += length
            acc.leakedChars += leaked
            language.goldChars += length
            language.leakedChars += leaked
            val partialHit =
                usable.any { it.category == category && overlaps(it.start, it.end, gold.start, gold.end) }
            val strictHit =
                usable.any { it.category == category && it.start == gold.start && it.end == gold.end }
            if (partialHit) {
                acc.partialTp++
                language.partialTp++
            } else {
                acc.partialFn++
                language.partialFn++
            }
            if (strictHit) acc.strictTp++ else acc.strictFn++
        }
    }

    private fun scoreFalsePositives(
        usable: List<PiiDetection>,
        inScope: List<GoldSpan>,
        categories: MutableMap<String, CategoryAcc>,
        language: LanguageAcc,
    ) {
        for (prediction in usable) {
            val acc = categories.getOrPut(prediction.category.name) { CategoryAcc() }
            val partialHit =
                inScope.any {
                    it.category == prediction.category &&
                        overlaps(prediction.start, prediction.end, it.start, it.end)
                }
            if (!partialHit) {
                acc.partialFp++
                language.partialFp++
            }
            val strictHit =
                inScope.any {
                    it.category == prediction.category &&
                        it.start == prediction.start && it.end == prediction.end
                }
            if (!strictHit) acc.strictFp++
        }
    }

    /** Chars of [gold] covered by the union of prediction intervals (any category), clipped to gold. */
    private fun coveredChars(
        gold: GoldSpan,
        predictions: List<PiiDetection>,
    ): Long {
        val intervals =
            predictions
                .map { maxOf(it.start, gold.start) to minOf(it.end, gold.end) }
                .filter { it.first < it.second }
                .sortedBy { it.first }
        var covered = 0L
        var cursor = gold.start
        for ((start, end) in intervals) {
            val from = maxOf(cursor, start)
            if (end > from) {
                covered += end - from
                cursor = end
            }
        }
        return covered
    }

    private fun hasResidualLeak(
        sample: BenchmarkSample,
        inScope: List<GoldSpan>,
        redacted: String,
    ): Boolean =
        inScope.any { span ->
            val value = sample.text.substring(span.start, span.end)
            value.length >= MIN_RESIDUAL_LEN && redacted.contains(value)
        }

    private fun assemble(
        meta: LayerMeta,
        categories: Map<String, CategoryAcc>,
        languages: Map<String, LanguageAcc>,
        residual: Int?,
    ): LayerScore {
        val categoryScores =
            categories.map { (name, acc) ->
                CategoryScore(
                    category = name,
                    goldSpans = acc.goldSpans,
                    partial = MetricValues.from(acc.partialTp, acc.partialFp, acc.partialFn),
                    strict = MetricValues.from(acc.strictTp, acc.strictFp, acc.strictFn),
                    goldChars = acc.goldChars,
                    leakedChars = acc.leakedChars,
                    leakRate = ratio(acc.leakedChars, acc.goldChars),
                )
            }
        val withGold = categoryScores.filter { it.goldSpans > 0 }
        return LayerScore(
            layer = meta.layer,
            samples = meta.samples,
            durationMs = meta.durationMs,
            categories = categoryScores,
            microPartial =
                MetricValues.from(
                    categoryScores.sumOf { it.partial.tp },
                    categoryScores.sumOf { it.partial.fp },
                    categoryScores.sumOf { it.partial.fn },
                ),
            microStrict =
                MetricValues.from(
                    categoryScores.sumOf { it.strict.tp },
                    categoryScores.sumOf { it.strict.fp },
                    categoryScores.sumOf { it.strict.fn },
                ),
            macroPartialF1 = if (withGold.isEmpty()) 0.0 else withGold.sumOf { it.partial.f1 } / withGold.size,
            macroFBeta2 = if (withGold.isEmpty()) 0.0 else withGold.sumOf { it.partial.fBeta2 } / withGold.size,
            leakRate = ratio(categoryScores.sumOf { it.leakedChars }, categoryScores.sumOf { it.goldChars }),
            residualValueLeaks = residual,
            perLanguage =
                languages.map { (lang, acc) ->
                    LanguageScore(
                        language = lang,
                        samples = acc.samples,
                        partial = MetricValues.from(acc.partialTp, acc.partialFp, acc.partialFn),
                        leakRate = ratio(acc.leakedChars, acc.goldChars),
                    )
                },
        )
    }

    private fun ratio(
        numerator: Long,
        denominator: Long,
    ): Double = if (denominator == 0L) 0.0 else numerator.toDouble() / denominator

    private fun overlaps(
        aStart: Int,
        aEnd: Int,
        bStart: Int,
        bEnd: Int,
    ): Boolean = aStart < bEnd && bStart < aEnd

    private class CategoryAcc {
        var goldSpans = 0
        var goldChars = 0L
        var leakedChars = 0L
        var partialTp = 0
        var partialFp = 0
        var partialFn = 0
        var strictTp = 0
        var strictFp = 0
        var strictFn = 0
    }

    private class LanguageAcc {
        var samples = 0
        var goldChars = 0L
        var leakedChars = 0L
        var partialTp = 0
        var partialFp = 0
        var partialFn = 0
    }

    private data class LayerMeta(
        val layer: String,
        val samples: Int,
        val durationMs: Long,
    )

    private companion object {
        const val MIN_RESIDUAL_LEN = 4
    }
}
```

- [x] T5.2 tests — **File**: `.../benchmark/scoring/ScorerTest.kt` — **Setup**: hand-built samples/predictions with known counts

  | Test | Verifies |
  |------|----------|
  | `partial match counts overlap with same category` | tp/fn/fp arithmetic |
  | `strict requires exact boundaries` | partial-only pred → strict fp + fn |
  | `prediction on excluded span is ignored` | DATE-only gold + DATE-overlapping pred → no fp |
  | `prediction overlapping excluded and matching in-scope gold counts as tp` | ignore rule second clause |
  | `wrong-category overlap is fn plus fp but not leaked` | leak rate 0, category counts penalized |
  | `leak rate uses interval union` | two preds covering halves of one gold → leak 0 |
  | `fbeta2 formula` | known p/r → expected Fβ=2 |
  | `residual value leak detected` | redacted text containing gold value counts |
  | `macro averages only categories with gold` | fp-only category excluded from macro |
  | `per-language accumulation` | two languages → separate rows |

### T5.3 — Report writer + main

- [x] A5.3.1 — Create `.../benchmark/scoring/ReportWriter.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.benchmark.scoring

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

@Serializable
data class CorpusScore(
    val corpus: String,
    val samples: Int,
    val droppedRows: Int,
    val unknownLabels: Map<String, Int>,
    val layers: List<LayerScore>,
)

@Serializable
data class BenchmarkReport(
    val generatedAtIso: String,
    val datasetCommit: String,
    val datasetSha256: String,
    val modelSha256: String,
    val tokenizerSha256: String,
    val seed: Long,
    val corpora: List<CorpusScore>,
)

/** Writes report.json (full DTO) and report.md (README-pasteable tables). */
class ReportWriter {
    private val json = Json { prettyPrint = true }

    fun write(
        outDir: File,
        report: BenchmarkReport,
    ) {
        outDir.mkdirs()
        File(outDir, "report.json").writeText(json.encodeToString(BenchmarkReport.serializer(), report))
        File(outDir, "report.md").writeText(markdown(report))
    }

    private fun markdown(report: BenchmarkReport): String =
        buildString {
            appendLine("# Privacy Mode effectiveness report")
            for (corpus in report.corpora) {
                appendLine()
                appendLine(
                    "## Corpus: ${corpus.corpus} " +
                        "(${corpus.samples} samples, ${corpus.droppedRows} dropped rows)",
                )
                if (corpus.unknownLabels.isNotEmpty()) {
                    appendLine("Unknown dataset labels (excluded): ${corpus.unknownLabels}")
                }
                for (layer in corpus.layers) appendLayer(layer)
            }
            appendLine()
            appendLine("---")
            appendLine(
                "Generated ${report.generatedAtIso}; dataset commit ${report.datasetCommit} " +
                    "(sha256 ${report.datasetSha256}); model sha256 ${report.modelSha256}; " +
                    "tokenizer sha256 ${report.tokenizerSha256}; seed ${report.seed}.",
            )
        }

    private fun StringBuilder.appendLayer(layer: LayerScore) {
        appendLine()
        appendLine("### Layer: ${layer.layer} (${layer.samples} samples, ${layer.durationMs} ms)")
        appendLine("| Category | Gold | P | R | F1 | Fβ=2 | Strict F1 | Leak % |")
        appendLine("|---|---|---|---|---|---|---|---|")
        for (category in layer.categories) {
            appendLine(
                "| ${category.category} | ${category.goldSpans} | ${pct(category.partial.precision)} " +
                    "| ${pct(category.partial.recall)} | ${pct(category.partial.f1)} " +
                    "| ${pct(category.partial.fBeta2)} | ${pct(category.strict.f1)} " +
                    "| ${pct(category.leakRate)} |",
            )
        }
        append(
            "Micro P ${pct(layer.microPartial.precision)} / R ${pct(layer.microPartial.recall)} " +
                "/ F1 ${pct(layer.microPartial.f1)}; macro F1 ${pct(layer.macroPartialF1)}; " +
                "macro Fβ=2 ${pct(layer.macroFBeta2)}; char-leak ${pct(layer.leakRate)}%",
        )
        layer.residualValueLeaks?.let { append("; residual value leaks: $it") }
        appendLine()
        if (layer.layer == "full" && layer.perLanguage.size > 1) {
            appendLine()
            appendLine("| Language | Samples | R | Leak % |")
            appendLine("|---|---|---|---|")
            for (lang in layer.perLanguage) {
                appendLine(
                    "| ${lang.language} | ${lang.samples} | ${pct(lang.partial.recall)} " +
                        "| ${pct(lang.leakRate)} |",
                )
            }
        }
    }

    // Explicit locale: report output must not depend on the host's default decimal separator.
    private fun pct(value: Double): String = String.format(Locale.US, "%.1f", value * PERCENT)

    private companion object {
        const val PERCENT = 100.0
    }
}
```

- [x] A5.3.2 — Create `.../benchmark/BenchmarkMain.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.benchmark

import com.danielealbano.androidremotecontrolmcp.benchmark.corpus.Ai4PrivacyCorpusLoader
import com.danielealbano.androidremotecontrolmcp.benchmark.corpus.AdversarialCorpusLoader
import com.danielealbano.androidremotecontrolmcp.benchmark.corpus.LoadedCorpus
import com.danielealbano.androidremotecontrolmcp.benchmark.corpus.UiCorpusGenerator
import com.danielealbano.androidremotecontrolmcp.benchmark.scoring.BenchmarkReport
import com.danielealbano.androidremotecontrolmcp.benchmark.scoring.CorpusScore
import com.danielealbano.androidremotecontrolmcp.benchmark.scoring.LayerScore
import com.danielealbano.androidremotecontrolmcp.benchmark.scoring.ReportWriter
import com.danielealbano.androidremotecontrolmcp.benchmark.scoring.Scorer
import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.system.exitProcess

data class BenchmarkArgs(
    val corpora: List<String> = listOf("a", "b", "c"),
    val layers: List<Layer> = listOf(Layer.DETERMINISTIC, Layer.MODEL, Layer.FULL),
    val sample: Int = 0,
    val seed: Long = DEFAULT_SEED,
    val cacheDir: File = File("privacy-benchmark/.cache"),
    val outDir: File = File("privacy-benchmark/build/reports/privacy-benchmark"),
)

internal val USAGE =
    """
    usage: privacy-benchmark [--corpora=a,b,c] [--layers=deterministic,model,full]
           [--sample=N] [--seed=L] [--cache-dir=PATH] [--out=PATH]
    """.trimIndent()

internal fun parseArgs(args: Array<String>): BenchmarkArgs {
    var parsed = BenchmarkArgs()
    for (arg in args) {
        require(arg.startsWith("--") && "=" in arg) { "invalid argument: $arg" }
        val key = arg.substringBefore("=")
        val value = arg.substringAfter("=")
        parsed =
            when (key) {
                "--corpora" ->
                    parsed.copy(
                        corpora =
                            value.split(",").onEach {
                                require(it in setOf("a", "b", "c")) { "unknown corpus: $it" }
                            },
                    )
                "--layers" ->
                    parsed.copy(layers = value.split(",").map { Layer.valueOf(it.uppercase(Locale.ROOT)) })
                "--sample" -> parsed.copy(sample = value.toInt())
                "--seed" -> parsed.copy(seed = value.toLong())
                "--cache-dir" -> parsed.copy(cacheDir = File(value))
                "--out" -> parsed.copy(outDir = File(value))
                else -> throw IllegalArgumentException("unknown argument: $key")
            }
    }
    return parsed
}

fun main(args: Array<String>) {
    val parsed =
        try {
            parseArgs(args)
        } catch (e: IllegalArgumentException) {
            System.err.println(e.message)
            System.err.println(USAGE)
            exitProcess(USAGE_EXIT_CODE)
        }
    // PhoneDetector reads the default region; a fixed locale keeps published numbers reproducible.
    Locale.setDefault(Locale.US)
    runBenchmark(parsed)
    // No catch-all: an uncaught exception exits non-zero with a stack trace (fail loudly).
}

private fun runBenchmark(args: BenchmarkArgs) {
    val downloader = BenchmarkDownloader()
    val pipeline = BenchmarkPipeline(args.cacheDir)
    // A deterministic-only run needs no model: skip the 151 MB download and the warm-up entirely.
    val modelNeeded = args.layers.any { it != Layer.DETERMINISTIC }
    if (modelNeeded) {
        downloader.ensure(BenchmarkAssets.MODEL, File(args.cacheDir, MODEL_DIR))
        downloader.ensure(BenchmarkAssets.TOKENIZER, File(args.cacheDir, MODEL_DIR))
        pipeline.store.writeVerifiedMarker()
    }
    val corpora = mutableListOf<CorpusScore>()
    runBlocking {
        if (modelNeeded) {
            pipeline.runner.warmUp().getOrElse { throw IllegalStateException("model warm-up failed", it) }
        }
        for (corpusId in args.corpora) {
            val corpus = loadCorpus(corpusId, args, downloader)
            println("[corpus] ${corpus.name}: ${corpus.samples.size} samples (${corpus.droppedRows} dropped)")
            val layerScores = args.layers.map { layer -> runLayer(pipeline, layer, corpus) }
            corpora +=
                CorpusScore(corpus.name, corpus.samples.size, corpus.droppedRows, corpus.unknownLabels, layerScores)
        }
    }
    pipeline.runner.close()
    val report =
        BenchmarkReport(
            generatedAtIso = Instant.now().toString(),
            datasetCommit = BenchmarkAssets.DATASET_COMMIT,
            datasetSha256 = BenchmarkAssets.DATASET.sha256,
            modelSha256 = BenchmarkAssets.MODEL.sha256,
            tokenizerSha256 = BenchmarkAssets.TOKENIZER.sha256,
            seed = args.seed,
            corpora = corpora,
        )
    ReportWriter().write(args.outDir, report)
    println("Report: ${File(args.outDir, "report.md")}")
}

private fun loadCorpus(
    id: String,
    args: BenchmarkArgs,
    downloader: BenchmarkDownloader,
): LoadedCorpus =
    when (id) {
        "a" -> {
            val dataset = downloader.ensure(BenchmarkAssets.DATASET, File(args.cacheDir, DATASET_DIR))
            Ai4PrivacyCorpusLoader().load(dataset, args.sample)
        }
        "b" -> UiCorpusGenerator(args.seed).generate()
        "c" -> AdversarialCorpusLoader().load()
        else -> error("unreachable: corpus $id")
    }

private suspend fun runLayer(
    pipeline: BenchmarkPipeline,
    layer: Layer,
    corpus: LoadedCorpus,
): LayerScore {
    val start = System.nanoTime()
    val predictions: List<List<PiiDetection>>
    var redacted: List<String>? = null
    if (layer == Layer.FULL) {
        val result = pipeline.detectAndRedactFull(corpus.samples)
        predictions = result.first
        redacted = result.second
    } else {
        predictions = pipeline.detect(layer, corpus.samples)
    }
    val durationMs = (System.nanoTime() - start) / NANOS_PER_MILLI
    val score = Scorer().score(layer.name.lowercase(Locale.ROOT), corpus.samples, predictions, durationMs, redacted)
    println(
        "[layer] ${corpus.name}/${score.layer}: micro F1 ${score.microPartial.f1}, " +
            "char-leak ${score.leakRate} ($durationMs ms)",
    )
    return score
}

private const val DEFAULT_SEED = 20260803L
private const val USAGE_EXIT_CODE = 2
private const val NANOS_PER_MILLI = 1_000_000L
private const val MODEL_DIR = "privacy_model"
private const val DATASET_DIR = "dataset"
```

- [x] T5.3 tests — **File**: `.../benchmark/scoring/ReportWriterTest.kt`

  | Test | Verifies |
  |------|----------|
  | `writes json and markdown` | both files exist; json round-trips; md contains category table header |
  | `formats percentages with one decimal` | leak 0.123 → "12.3" |

  **File**: `.../benchmark/BenchmarkArgsTest.kt`

  | Test | Verifies |
  |------|----------|
  | `defaults when no args` | corpora a,b,c; all layers; sample 0; seed 20260803; default dirs |
  | `parses overrides` | `--corpora=c --layers=full --sample=100 --seed=7 --cache-dir=/x --out=/y` |
  | `rejects unknown key` | `--nope=1` throws IllegalArgumentException |
  | `rejects unknown corpus and malformed arg` | `--corpora=x` and `--sample` (no `=`) throw |

  **File**: `.../benchmark/BenchmarkSmokeTest.kt` — **Setup**: gated `assumeTrue(System.getenv("PRIVACY_MODEL_DIR") != null)`; copy/point store at real model dir (mirrors `OrtPiiModelRunnerRealModelTest` gating)

  | Test | Verifies |
  |------|----------|
  | `corpus c runs through full layer with real model` | no exception; predictions size == samples; report writable |

---

## US6 — Makefile target, benchmark run + user review of the numbers, final gates

USER DECISION (2026-08-03): the measured numbers MUST NOT be published anywhere (README, UI, release notes) without the user first SEEING and APPROVING them. The benchmark run produces the report; publication is a separate, user-gated step.

Acceptance criteria:
- [x] `make privacy-benchmark` runs the tool.
- [x] Full benchmark run completed (complete Corpus A validation split + B + C); `report.json`/`report.md` produced and the measured tables PRESENTED to the user.
- [x] README "Privacy Mode effectiveness" section added ONLY IF AND WHEN the user explicitly approves the numbers (template below); otherwise the section is NOT added and A6.2.2 stays unchecked.
- [x] All quality gates green; code-reviewer plan-compliance pass.

### T6.1 — Makefile

- [x] A6.1.1 — Modify `Makefile` in TWO places:
  1. Add the target (Testing section, after `test-e2e`):

```make
privacy-benchmark: ## Run the Privacy Mode effectiveness benchmark (downloads model + dataset to privacy-benchmark/.cache on first run)
	$(GRADLE) :privacy-benchmark:run --args="$(BENCHMARK_ARGS)"
```

  2. Add `privacy-benchmark` to the `.PHONY` declaration at the top of the Makefile (line 2 currently reads `        test-unit test-integration test-e2e test coverage \` → append ` privacy-benchmark` before the backslash). REQUIRED: the target name collides with the `privacy-benchmark/` module DIRECTORY created in US2 — without `.PHONY`, GNU make treats the prerequisite-less target as always up-to-date and never runs the recipe (review finding P59-022).

### T6.2 — Run the benchmark; present the numbers; publish ONLY on user approval

- [x] A6.2.1 — Run the full benchmark locally: `make privacy-benchmark 2>&1 | tee /tmp/p59-privacy-benchmark.log | tail -20` (full Corpus A; expect a long model pass). Inspect `/tmp/p59-privacy-benchmark.log` and `privacy-benchmark/build/reports/privacy-benchmark/report.md` — do NOT re-run to re-read output. Then PRESENT the measured tables and headline numbers from `report.md` to the user and STOP: the user reviews and decides what (if anything) gets published and where (README and/or UI wording are the user's call after seeing the numbers).
- [x] A6.2.2 — **GATED ON EXPLICIT USER APPROVAL — you MUST NOT perform this action, or publish the numbers anywhere else, until the user has reviewed the A6.2.1 report and explicitly approved publication.** If approved, modify `README.md` in TWO places (the README has NO existing Privacy Mode section — verified 2026-08-03, review finding P59-023):
  1. Add `- [Privacy Mode effectiveness](#privacy-mode-effectiveness)` to the `## Contents` list, between the `Features` and `Install` entries (matching the list's existing format).
  2. Insert the `## Privacy Mode effectiveness` section immediately BEFORE the `## Install` heading (i.e., at the end of the `## Features` section, after `### Comparison with Alternatives`), with EXACTLY this structure, pasting the measured tables from `report.md`:

```markdown
## Privacy Mode effectiveness

Measured with the in-repo benchmark (`make privacy-benchmark`), which runs the REAL production
detection pipeline (deterministic + on-device NER model + merge) over three corpora and scores
span-level precision/recall plus a character-level leak rate:

- **Corpus A** — [ai4privacy open-pii-masking-500k](https://huggingface.co/datasets/ai4privacy/open-pii-masking-500k-ai4privacy)
  validation split (116k multilingual sentences, CC-BY-4.0, DOI 10.57967/hf/4852), pinned to commit
  `506996d6`. This is the detection model's own training distribution — treat these numbers as an
  in-domain upper bound.
- **Corpus B** — seeded synthetic accessibility-tree corpus (UI-shaped short strings with labels,
  hints and resource-ids, incl. cards/IBANs/credentials), seed `20260803`.
- **Corpus C** — hand-curated adversarial suite (obfuscated, partial and lookalike cases).

{PASTE: headline full-layer table per corpus}

{PASTE: layer-attribution summary (deterministic vs model vs full)}

{PASTE: per-language table, Corpus A full layer}

Numbers were produced with model `model_int8.onnx` (sha256 `8e8af012…`) on commit {git short sha};
reproduce with `make privacy-benchmark`. Detection is best-effort mitigation, not a guarantee —
see the Privacy Mode settings screen wording.
```

Attribution requirements (CC-BY-4.0): the dataset link + DOI line above MUST remain.

### T6.3 — Quality gates + review

- [x] A6.3.1 — `make lint 2>&1 | tee /tmp/p59-lint.log | tail -20` — fix everything until clean.
- [x] A6.3.2 — `set -a && source .env && set +a && ./gradlew :app:test :privacy:test :privacy-benchmark:test jacocoTestReport jacocoTestCoverageVerification 2>&1 | tee /tmp/p59-test.log | tail -20` — fix everything until green (including any unrelated broken test). This also verifies BOTH coverage gates: `:privacy` ≥ 0.50 (with the OrtPiiModelRunner exclusion) AND `:app` still ≥ 0.50 after the well-covered privacy classes moved out (review finding P59-013). If either gate fails, STOP and ask the user how to proceed.
- [x] A6.3.3 — `make build 2>&1 | tee /tmp/p59-build.log | tail -20` — no errors, no warnings.
- [x] A6.3.4 — Spawn `code-reviewer` in plan compliance mode over the full implementation; fix ALL findings; re-run until clean.
- [x] A6.3.5 — Push, open PR via `gh pr create` per TOOLS.md, report PR URL. If the user has NOT (yet) approved publication at A6.2.2, the PR contains the benchmark tooling WITHOUT any README numbers section — the README publication happens later as a separate user-approved change, and A6.2.2 stays unchecked until then.

---

## Review Findings — plan-reviewer round 1 (2026-08-03): FAIL (2 CRITICAL, 3 WARNING, 8 INFO) — all addressed in place

- P59-001 CRITICAL — Corpus B GEOMETRIC layout: value node width put its bounds-center 300 px right of the label's center, exceeding `ContextExtractor`'s 300 px cap (√(300²+70²) ≈ 308) → label never resolved. Fixed: value bounds narrowed to `(40, y+50, 400, y+130)` so centers align (70 px apart); US3 point 3 rewritten with the center-distance math.
- P59-002 CRITICAL — `PrivacyModelDownloaderTest` was listed as "stays unchanged" but constructs `PrivacyModelStore(context)` (type error after the `File` refactor). Fixed: new A1.5.6 adapts it to `@TempDir` `File`; the unchanged-list moved to A1.5.7.
- P59-003 WARNING — an all-digit UUID group could form a ≥12-digit dash-joined run and hit `CardDetector`. Fixed: REFERENCE spec forces the first char of every UUID group to a letter `a`–`f`; parenthetical corrected.
- P59-004 WARNING — `:privacy` 0.50 jacoco gate at risk (OrtPiiModelRunner covered only by the env-gated real-model test, 0% in CI). Fixed per user decision: jacoco classDirectories exclusion for `OrtPiiModelRunner*` in A1.1.3 with justifying comment (mirrors `:app`'s device-only exclusions).
- P59-005 WARNING — `:privacy-benchmark` had no coverage gate. User decision: keep NO gate (tooling module, tests mandatory) — documented in A2.1.3.
- P59-006 INFO — split changes `currentConfig()` call count and gate ordering (output-neutral). Documented under T1.4; slimmed `PrivacyPipelineImplTest` asserts single-call behavior.
- P59-007 INFO — QA gaps. Fixed: T3.1 gains tests for all five context styles, per-screen composition, and negative digit-run bounds; new non-gated `BenchmarkPipelineTest` (fake `PiiModelInference`) covers the deterministic layer and chunk keying incl. blanks and chunk boundaries; new `BenchmarkArgsTest` covers arg parsing.
- P59-008 INFO — Scorer/ReportWriter/BenchmarkMain were prose. Fixed: all three now specified as complete implementations (A5.2.1, A5.3.1, A5.3.2).
- P59-009 INFO — redundant `:privacy:`-qualified jacoco tasks in ci.yml. Fixed: A1.6.2 uses the unqualified tasks with an explanatory note.
- P59-010 INFO — `uid: Long` unverified. Fixed: Verified-facts notes the sampled rows (`5706814`, `5760565`) are integral.
- P59-011 INFO — removing `:app`'s `testImplementation(onnxruntime.jvm)` touches `PrivacyModeManagerTest`'s relaxed mock of `OrtPiiModelRunner`. Documented in A1.5.1: the kept `onnxruntime-android` AAR supplies the classes on the unit-test classpath; verified at the end-of-plan test gate.
- P59-012 INFO — shared NerCache skewed per-layer durations. Fixed: `BenchmarkPipeline` builds a FRESH `NerEngine`+`NerCache` per layer run; FULL uses a single model pass via `detectAndRedactFull` (production `RedactionEngine.detect` + production `Redactor.apply`, the exact composition of `redactTexts`).
- P59-013 INFO — `:app`'s own 0.50 floor could shift after the extraction. Fixed: A6.3.2 runs both coverage verifications and mandates stopping to ask the user if either fails.

## Review Findings — plan-reviewer round 2 (2026-08-03): FAIL (1 CRITICAL, 2 WARNING, 3 INFO) — all addressed in place

- P59-014 CRITICAL — the round-1 geometry fix let cross-row candidates (a neighboring row's label at 230 px, or a previous row's non-editable value node) attach to no-own-label editable rows (RESOURCE_ID/HINT) within the 300 px nearest-label cap. Fixed: row spacing raised to 320 px (root bounds height 3500); US3 point 3 now derives every cross-row candidate distance (own label 70 px accepted; previous row's value 320 px and label 390 px rejected).
- P59-015 WARNING — detekt MagicNumber on the Fβ=2 literals `4`/`5`. Fixed: `BETA_SQUARED = 4.0` named constant with the `(1 + β²)·P·R / (β²·P + R)` form.
- P59-016 WARNING — 121-char ReportWriter line. Fixed: split into concatenated strings.
- P59-017 INFO — char-leak was computed over ignore-filtered predictions, deviating from the "ANY prediction of ANY category" rule. Fixed: `coveredChars` now takes ALL predictions (with an explanatory comment); `scoreGold` signature updated.
- P59-018 INFO — the per-screen composition test was not derivable from `BenchmarkSample`. Fixed: sample ids gain a `b-<language>-` prefix (also making ids globally unique across languages), and the test is restated as "exactly six gold-bearing samples per screen".
- P59-019 INFO — locale-dependent `pct` formatting. Fixed: `String.format(Locale.US, "%.1f", …)` with `java.util.Locale` import.

## Review Findings — plan-reviewer round 3 (2026-08-03): FAIL (1 WARNING, 1 INFO) — all addressed in place

- P59-020 WARNING — `Scorer.assemble` had 6 parameters, exceeding detekt's `LongParameterList` function cap (5, per the repo's documented convention). Fixed: introduced a private `LayerMeta(layer, samples, durationMs)` holder — `assemble` now takes 4 parameters; no `@Suppress`.
- P59-021 INFO — `BenchmarkArgs` had 6 non-defaulted constructor parameters (constructor threshold unconfirmed). Fixed: every field now carries its default (removing the duplication in `parseArgs`, which was rewritten in `copy()` style over `BenchmarkArgs()`), making the constructor safe under any plausible detekt configuration.

## Review Findings — plan-reviewer round 4 (2026-08-03): **PASS** (0 CRITICAL, 0 WARNING, 0 INFO)

Both round-3 fixes verified correct; all 21 findings (P59-001…P59-021) across rounds 1–3 genuinely resolved; no new issues. The reviewer additionally verified empirically that detekt's LongParameterList cap applies to functions (5) and not to data-class constructors in this repo (un-suppressed `NotificationData` with 13 params passes), so the wide `@Serializable` report DTOs are not a lint risk.

## Review Findings — FRESH adversarial plan-reviewer round 5 (2026-08-03, new reviewer instance, post-#138-merge): FAIL (1 CRITICAL, 2 WARNING, 2 INFO) — all addressed in place

- P59-022 CRITICAL — the `privacy-benchmark` Makefile target collides with the `privacy-benchmark/` module directory and was not `.PHONY`: GNU make would report "up to date" and never run the benchmark. Fixed: A6.1.1 now also appends `privacy-benchmark` to the `.PHONY` declaration.
- P59-023 WARNING — A6.2.2's placement anchor ("after the existing Privacy Mode feature description") did not exist: the README contains no Privacy Mode mention. Fixed: the action now adds a `## Contents` TOC entry and inserts the section immediately before `## Install` (end of the `## Features` section).
- P59-024 WARNING — `UiCorpusGenerator` was a skeleton + prose spec, violating the actions-carry-full-code rule for non-test files. Fixed: A3.1.1 now contains the COMPLETE implementation (kinds, layout, localized label/name/address/sentence pools for all 8 languages, Luhn/mod-97/DNI-check-letter/pattern generators, negatives, sample extraction) with only the non-derivable constraints kept as prose.
- P59-025 INFO — `runBenchmark` unconditionally downloaded the model and warmed up even for `--layers=deterministic`. Fixed: model download + `writeVerifiedMarker` + `warmUp` are now gated on the selected layers including MODEL or FULL.
- P59-026 INFO — `corpus_c.jsonl` remains a specified (not inlined) data resource; the load-time validation test is its only guard. Fixed: `AdversarialCorpusTest` assertions strengthened (per-group exact counts, non-blank in-bounds substrings, group-name membership, non-negative groups must carry gold) and marked as MUST-stay-exhaustive.

Verified clean by the fresh reviewer in the same round: extraction completeness/behavior-neutrality, all signatures vs post-merge main, geometry re-derivation, ContextKeywords/CardDetector/IbanDetector consistency (space-grouped IBANs are a MEASURED limitation of the production detector, not a benchmark bug), scoring rules ↔ Scorer equivalence, corpus counts, downloader integrity, no secrets, no AI attribution, no suppressions.

## Review Findings — fresh reviewer round 6 (2026-08-03): FAIL (1 WARNING) — addressed in place

- P59-027 WARNING — the round-5 "complete implementation" put 28 functions in one `UiCorpusGenerator` class, exceeding detekt's `TooManyFunctions` cap of 11 (rule verified ACTIVE in this repo via `ServiceModule`'s pre-existing `@Suppress("TooManyFunctions")`); a suppression is forbidden. Fixed: A3.1.1–A3.1.5 now create FIVE files in dependency order — `FieldKind.kt` (enums, 0 functions), `RandomText.kt` (4), `IdValueGenerators.kt` (10, checksum values: Luhn cards, mod-97 IBANs, national IDs, UUIDs), `UiValueFactory.kt` (10, per-kind value+gold incl. all localized pools), `UiCorpusGenerator.kt` (4, screen assembly + sample extraction) — behavior identical, every class/file ≤11 functions, no `@Suppress`.

Verified correct by the same round-6 pass: the `.PHONY` fix (quoted Makefile line matches), both README anchors exist and the instruction is followable, the generator's math (Luhn parity, IBAN lengths GB 22 / FR 27 / DE 22 / ES 24 / IT 27 / NL 18, mod-97 check, DNI mod-23), geometry constants, every negative's maximal digit run < 12, UTF-16-consistent sentence offsets, determinism, `runBenchmark` layer gating (with `close()` safe on an unloaded runner), and the strengthened corpus C assertions.

## Review Findings — fresh reviewer round 7 (2026-08-03): FAIL (1 WARNING) — addressed in place

- P59-028 WARNING — the round-6 split moved the numeric collections (`UUID_GROUP_SIZES`, `FOUR_GROUPS`, `AMEX_GROUPS`, `CARD_SPECS`) from a companion object into the plain `IdValueGenerators` object, losing detekt MagicNumber's `ignoreCompanionObjectPropertyDeclaration` exemption (non-const `val`s in a plain object are checked). Fixed: every numeric element is now a named `const val` (`GROUP_OF_FOUR`, `AMEX_GROUP_MIDDLE/TAIL`, `UUID_GROUP_HEAD/TAIL`, `PAN_LENGTH_STANDARD`, reusing `AMEX_LENGTH`) and the lists are built from them — no `@Suppress`.

The same round-7 pass verified the five-file split correct on every other axis: per-class AND per-file function counts all ≤11 (`visit` is a local function, not counted), behavior identity (bodies verbatim, `valueFor` branch reorder equivalence proven for all 15 kinds), no duplicated or dangling constants, sufficient cross-file visibility, sequential `Random` threading preserved (same seed → same corpus), exact imports per file, unchanged public API (T3.1 tests and US3 criteria still accurate), and an accurate round-6 record.

## Review Findings — fresh reviewer round 8 (2026-08-03): **PASS** (0 CRITICAL, 0 WARNING, 0 INFO) — FINAL

P59-028 verified genuinely fixed (lists value-identical: UUID 8-4-4-4-12, groups 4-4-4-4, Amex 4-6-5, card specs 16/16/15/16/16 with `AMEX_LENGTH` reuse coherent with `formatCard`); a full MagicNumber sweep of all five generator files plus every other planned code block found zero non-exempt literals; function counts, line lengths, wrapping, and references all clean. The reviewer's exhaustive final re-scan of the ENTIRE plan (structure, sequencing, every code block vs post-#138 main, module/build/CI/Makefile/README wiring, all three corpora, scorer ↔ rules equivalence, runner gating, QA mapping, no secrets/attribution/suppressions) surfaced nothing outstanding. All 28 findings (P59-001…P59-028) across two reviewer instances and eight rounds are resolved.

## Review Findings — round 9 (2026-08-03, targeted: US6 user-approval gating per USER DECISION): FAIL (1 INFO) — addressed in place

USER DECISION applied: measured numbers MUST NOT be published anywhere without the user first seeing and approving them. US6 restructured (run → present report to user → STOP; A6.2.2 hard-gated on explicit approval; A6.3.5 PR may ship without the README section while approval is pending). The reviewer verified the four gating edits internally consistent, ordering-sound, and checkbox-lifecycle-compliant, and found one residual:

- P59-029 INFO — the plan's line-7 purpose sentence still asserted unconditional README publication. Fixed: reworded to "subject to explicit user approval after the user has reviewed the measured numbers". All other publish/README mentions verified descriptive, not contradictory.

## Review Findings — round 10 (2026-08-03): **PASS** (0 CRITICAL, 0 WARNING, 0 INFO) — FINAL

P59-029 verified genuinely fixed (line 7 aligns exactly with the US6 gating; grammar and references correct); the round-9 record verified accurate; nothing else disturbed (sacred header intact, append-only review history). All 29 findings (P59-001…P59-029) across two reviewer instances and ten rounds are resolved. The plan is settled and ready for implementation.

## Implementation note — user-approved mapping amendment (2026-08-03)

The pinned validation split contains 2,609 `CREDITCARDNUMBER` spans although the dataset card's
label list omits that label (the loader's unknown-label counter surfaced them during the first
full run). USER DECISION: map `CREDITCARDNUMBER` → `CARDS_AND_IBAN` in `Ai4PrivacyCorpusLoader`
(giving in-domain card measurements) and re-run the benchmark. The plan's pinned mapping table
above reflects the pre-run knowledge and is superseded on this one label by this note.

## Implementation note — user-approved README publication (2026-08-03)

USER DECISION at publication time: instead of the A6.2.2 template, the README gains a
`## Privacy Mode` section placed BEFORE `## Integrations` with (a) a brief explanation of the
feature, (b) the per-category detection-rates table (corpus B ui-synthetic FULL-layer recall — the
same numbers published in the app's Privacy settings screen, alphabetical by label, no
static/model split and no layer totals), and (c) enable instructions for both entry points. The
model attribution (MIT, Built with Llama) and the `make privacy-benchmark` reproduce command are
included; the ai4privacy dataset attribution is not required here because the published numbers
come from the project's own synthetic corpus B, not the CC-BY dataset.
