# CAPCAKE Architecture Refactor Plan

Branch: `feature/rife-motion-blur-foundation`

## Objective

Stabilize preview and export without changing established editing behavior. The immediate export failure is a Media3 multi-input GL texture exhaustion error (`Textures are all in use`), so the refactor must make frame ownership, queueing, and release behavior explicit rather than merely increasing texture-pool sizes.

## Current architecture findings

- `MainActivity.kt` is approximately 497 KB and currently acts as the main integration point for UI, timeline state, playback, composition building, export, and diagnostics.
- Timeline primitives already exist in `TimelineEngine.kt`, separate from Android Views.
- Video/overlay models exist in `VideoClip.kt` and `OverlayVideo.kt`.
- Custom GL effects are separate files (`ChromaKeyGlEffect.kt`, `DreamyGlEffect.kt`).
- Export currently constructs Media3 `Composition` / `EditedMediaItemSequence` objects from activity-owned state. Main video and overlays are separate sequences and meet in Media3's multi-input graph.
- The observed crash is inside Media3's `DefaultFrameDroppingShaderProgram` / `MultipleInputVideoGraph`. The custom shader pool configuration alone does not prove the cause.

## Target boundaries

1. **Project layer** — project serialization, autosave, restore, and migration.
2. **Timeline domain** — immutable timeline state, clip timing, track ordering, trim/split, and validation; no Android View dependencies.
3. **Media source layer** — source metadata, duration, dimensions, frame rate, and audio characteristics.
4. **Composition layer** — converts a validated timeline snapshot into a deterministic render graph. It must not read mutable UI state.
5. **Preview engine** — playback and scrubbing against the composition snapshot, with explicit lifecycle and seek coalescing.
6. **GL/effect layer** — effect factories create independent shader-program instances; texture ownership and release rules are documented and tested.
7. **Export engine** — consumes the same immutable composition description as preview, owns Transformer lifecycle, validates supported output formats, and reports structured errors.
8. **UI layer** — `MainActivity` becomes an orchestrator; UI events call domain/controller interfaces rather than building Media3 graphs inline.

## Non-negotiable invariants

- Timeline time is stored in milliseconds; conversion to frame timestamps happens only at media boundaries.
- Source FPS, output FPS, and preview FPS are distinct values. Export must never silently speed up or reinterpret timestamps.
- Output dimensions and aspect ratio are computed once by a render-settings resolver; no hidden full-screen render followed by unnecessary crop.
- Every GL input frame has one clear owner. A texture is not reused until its consumer releases it.
- No GL effect or shader-program instance is shared across independent pipelines unless the API explicitly guarantees thread safety.
- Preview/export composition ordering, transforms, crop, alpha, and chroma-key parameters must derive from the same immutable snapshot.
- Existing project files remain readable; schema changes require explicit version migration.

## Migration sequence

### Phase 0 — Baseline and safety
- Record current branch/commit and successful build baseline.
- Add reproducible export test cases: main video only, one overlay, chroma key, two overlays, mixed audio, and 60 FPS source.
- Preserve current behavior while instrumenting graph inputs, frame timestamps, queue depth where observable, and effect lifecycle.

### Phase 1 — Extract pure domain contracts
- Introduce immutable `EditorProjectSnapshot`, `RenderSettings`, `MediaSourceInfo`, and `CompositionPlan` data contracts.
- Add validation for overlapping tracks, invalid trims, unsupported dimensions/FPS, and missing media.
- Keep existing UI and playback implementation wired through adapters.

### Phase 2 — Isolate composition building
- Move Media3 sequence/effect construction out of `MainActivity` into a dedicated composition factory.
- Make sequence ordering and input-id mapping explicit and deterministic.
- Add unit tests for timeline-to-composition mapping.

### Phase 3 — Stabilize GL and multi-input processing
- Audit all custom effects and Media3 graph configuration on the active branch.
- Verify frame release/backpressure behavior at the graph boundary.
- Reduce redundant per-overlay effects where equivalent transforms can be expressed in compositor settings.
- Test with increasing overlay count and long-duration clips before changing pool sizes.

### Phase 4 — Separate preview and export lifecycle
- Introduce dedicated preview/export controllers consuming the same composition snapshot.
- Coalesce rapid scrubs; prevent overlapping export jobs and stale callbacks.
- Ensure cancellation and release are deterministic.

### Phase 5 — Shrink MainActivity
- Move UI sections into focused controllers/views incrementally.
- Keep a compatibility adapter until all existing workflows are covered.

## Acceptance gates

A phase is not considered complete merely because it compiles. It must pass:
- Main video export.
- One and multiple overlay export.
- Chroma key with the same source dimensions and frame rate.
- No audio drift or speed-up.
- 1:1 and non-square output.
- Repeated seek/scrub followed by export.
- Export cancellation followed by a second export.
- No GL texture exhaustion in repeated runs.

## Current status

- Phase 0 architecture inventory started.
- No runtime code changed by this document.
- No APK installation is required for this documentation-only checkpoint.
