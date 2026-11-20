# Eta-por source integration (local validation branch)

## Scope and provenance

- Existing custom.5 baseline: `17a73fdb3021efe11cd09cfe49783c692a2ffc6b`.
- Incoming source: `https://github.com/Oo0520/Eta-por`, main at `58a1faec3cdc1a43bac55f2617729710429bd15f`.
- Common ancestor: `9de3a3f70aba497bd8fd677cb5b876f45115b428`.
- Integration branch: `integration/eta-por-58a1faec`, isolated from the existing custom.5 worktree.
- User-approved rule: retain existing functionality and all language support; integrate additions and fixes. No functionality removal is authorized. No install, device data deletion, publication, or remote push is implied.

This is a semantic integration, not replacement of the existing tree with the incoming tree. Branch policies from the other repository do not authorize deleting this repository's branches or publishing its artifacts.

## Preserved existing capabilities

- COUI and MIUIX; shared user/assistant message actions, explicit inline selection and editable memory/skill confirmations.
- Full-history chat search and large-font searchable model picker.
- All existing offline speech engines, model import/deletion, recommendation, VAD/manual rules, and local-only failure boundaries.
- Small Bu / Xiaoai vendor bridge and current same-UID runtime boundary.
- English, Simplified Chinese, and Traditional Chinese resources and packaging filters.
- Existing conversation history, archive, usage, and database migrations. Historical migrations 6→27 remain unchanged; only 27→28 is added. No destructive migration fallback.
- Existing GUI ownership/fresh-observation contract and main/canvas routing; older APIs are adapted rather than globally replaced.

## Integrated additions and adaptations

- Conversation categories and management, multi-run controls, queued messages, navigation and message references.
- Slash commands, MCP/skill access panels, subscription/provider configuration and additional settings pages.
- Subagent model authorization, concurrency/iteration/failure limits and live activity controls.
- Additional speech synthesis options, explicit system ASR selection, speech progress and safe entry-point routing.
- Additional virtual-screen, notification, prompt, diagnostics, usage and task UI, adapted to both existing UI styles.
- Scoped approval delivery, atomic single-use operation capabilities, fresh-target verification and no cross-conversation resume.
- Non-terminal checkpoint flush and memory-bound request/tool admission. New and existing virtual-screen paths retain per-run routing.

## Explicitly not ported or not yet equivalent

Preserving existing capabilities takes precedence over reproducing every incoming implementation. The following incoming-only details are not represented as fully delivered:

- Independent screen-subagent GUI execution: rejected with an explicit isolation error until separate ownership can be established; the main agent's GUI remains available.
- The incoming alternative summary algorithm: current archive/compaction and accounting remain authoritative.
- Category drag/drop, collapsed category presentation, recent shortcuts, and dedicated per-message fork/timestamp action rows: category management and existing continuation operations remain available via existing UI paths.
- Settings-wide search, full private ZIP restore UI, a Linux component-uninstall entry point, and Markdown-path click behavior are not all wired equivalently. Existing backup/workspace/Linux capabilities are retained.
- Cloud TTS continuation records speech chunks; it is not a claim of sample-exact audio pause/resume.
- A virtual-screen approval cannot be transplanted to an unproven new display/session. An explicit fresh confirmation is required instead of replaying coordinates. Conservative unknown-ACK states retain their card rather than resending.

## Validation and release boundary

Detailed local evidence is retained outside the source tree in `/workspace/eta-publication/merge-eta-por-58a1faec/`: actual build logs, compiler diagnostics, resource retention reports, targeted tests and independent reviews. Targeted CLI tests using Android fixtures or stubs are not App UID/Binder/Compose/native-device acceptance. Final results must be read from the actual completed jobs; this document does not assert an unfinished build passed.

The existing root LICENSE is unchanged. The incoming repository carries a different PolyForm Noncommercial license text, retained separately for provenance. Compatibility and redistribution have not been resolved. Do not interpret this local integration as permission to relicense or redistribute; no merged APK or source is published by this task. No signing key, model weight, credential, user conversation, recording or private state belongs in this integration.

### Completed local verification

See `eta-por-validation.json` for exact scope: assembleDebug passed; JVM run has 1,988 passed, 94 skipped, zero failures/errors. Another 91 specifically identified Linux-aarch64-native-incompatible methods and five legacy environment-incompatible classes were explicitly excluded, not counted as passing. The final debug APK is unsigned, not installed and not published. All 15 baseline native libraries have identical content.

ART with abort-on-hard-verifier-error passed. Enabling abort-on-soft-verifier-error fails on a lock-verification warning in unchanged VirtualDisplayController.ensureVirtualDisplay; the same warning and strict failure were reproduced using the original custom.5 APK. This is not a full strict ART pass and does not establish device feature acceptance. The integration does not silently alter that baseline implementation.
