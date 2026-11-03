# Engineering Preferences — KMP/CMP Template First

This file has two parts:

- **Part A — Core CMP instructions:** reusable coding preferences, project structure, and engineering guidelines applicable to all CMP projects using this template.
- **Part B — Vaulty-specific instructions:** project conventions and constraints that supplement Part A.

All implementation plans, proposals, roadmaps, and project-specific planning decisions belong in `private/plans/`. Keep product requirements, phase tracking, and implementation status out of `AGENTS.md`.

# Part A — Core CMP instructions

## General architecture

- Keep shared business logic in Kotlin Multiplatform `commonMain`; isolate platform work behind small interfaces with platform implementations.
- Support Android, iOS Arm64, iOS Simulator Arm64, and Desktop/JVM (macOS, Windows, Linux) from the same shared architecture.
- Use Kotlin Multiplatform, Compose Multiplatform, Material 3, Navigation 3, Coroutines/Flow, Kotlin Serialization, Gradle Kotlin DSL, and the version catalog.
- Reuse existing core modules, UI components, Navigation 3, Koin DI, logging, networking, and persistence before adding new abstractions or dependencies.
- Do not introduce another navigation framework or duplicate existing core functionality.
- Preserve the feature-module boundary: a concrete feature may depend on core modules and an explicitly shared feature module, never another concrete feature. `core:*` modules never depend on `feature:*`, and no module may depend on `composeApp`.
- Keep core modules as independent leaves. A `core:*` module may depend only on `core:common` and `core:logger`; it must not depend on another feature-specific or technical `core:*` module. If two tightly coupled core modules require each other, merge them into one core module with separate packages.
- Keep `verifyFeatureModuleBoundaries` current whenever modules are added or removed.
- Prefer incremental, compile-verified changes. Do not change unrelated core modules without explicit approval.
- Keep the Room schema at version `1` during active development; request explicit approval before introducing a migration.

## General module and package structure

Use this dependency direction. It is intentional and must not be bypassed:

```text
composeApp  -> feature:* -> core:common / core:logger / independent core modules
                    \-> feature:shared (only for genuinely cross-feature contracts)
independent core:* -> core:common and/or core:logger only
core:*      -X-> feature:* or composeApp
```

- `composeApp` is the composition root and platform application shell. It owns `App`, platform `actual` factories, app-wide startup wiring, navigation assembly, and desktop entry points. It must not contain feature business logic.
- A `core:*` module owns a reusable technical concern; `core:ui` owns reusable design-system/UI/navigation infrastructure and `core:persistence` owns Room setup and database access. Product-specific module responsibilities belong in the relevant plan under `private/plans/`.
- Keep `core:*` modules project-agnostic, because they are reused across projects built from this template. Name their classes, objects, functions, files, constants, and theme/API types generically (for example `Palette`, `AppColors`, `AppTheme`), never after the product (`Vaulty…`, `VAULTY_…`). Supply product-specific values (app name, user-facing copy, brand colours, service identifiers) from `composeApp` or a feature through configuration or parameters.
  - Exceptions: persistence entities, DAOs, and table/column names in `core:persistence` may use domain names.
  - Already-shipped wire, storage, and platform identifiers (for example mDNS service types, protocol tags, Keystore aliases, DataStore keys, and file names) keep their values for compatibility. Rename the code symbol that holds them, and change a value only through an approved migration.
- Each concrete feature remains independent. Never import one concrete feature from another.
- `feature:shared` is for compact, stable, non-sensitive feature models/contracts genuinely consumed by two or more features. Reusable persistence interfaces and implementations belong in `core:persistence`; `feature:shared` is not a dumping ground and must not contain screen-specific UI, repositories for one feature, credentials, or platform code.
- Project structure (product-specific core modules are listed in Part B):

```text
composeApp/                 # app shell and composition root
  com.jagdeep.<app>/        # App.kt, app services, Platform*.kt actual factories
    presentation/navigation/  # AppNavHost, decorated back stacks, transitions
core/common/                # com.jagdeep.common: platform/, time/
core/logger/                # com.jagdeep.logger
core/ui/                    # com.jagdeep.ui
  components/               # reusable composables only (app bars, scaffold, list rows, toast/snackbar)
  navigation/               # routes, NavigationState, Navigator, TopLevelPager
  platform/                 # form factor and display helpers (expect/actual)
  themes/                   # AppTheme, Palette*.kt, Typography, Shape, SystemBars
  util/                     # non-composable UI helpers (formatting, user-message text)
core/network/               # connectivity/, discovery/, speed/, util/
core/persistence/           # preferences/, room/ (dao/, entity/, per-domain stores)
core/notifications/         # system notifications
feature/shared/             # com.jagdeep.shared.<capability>: cross-feature contracts
feature/<name>/             # one concrete feature each (layout below)
private/                    # plans/, misc/, signing/ (misc/ and signing/ are git-ignored)
```

- New package layout inside a concrete feature is:

```text
com.jagdeep.<feature>/
  data/          # repository implementations, mappers; no Compose
  domain/        # models, repository interfaces, use cases, ViewModels
  ui/screens/    # composable screens only
  ui/components/ # feature-private composables only
  ui/navigation/ # Navigation 3 entry provider(s)
  ui/util/       # optional: feature-private non-composable UI helpers
```

- `components` packages hold composables only. Put formatters, text mappers, and other non-composable helpers in `util` (`com.jagdeep.ui.util` when reused, the feature's `ui/util` otherwise), never beside composables.

- Keep existing package names where moving them is unrelated work. New shared contracts follow `com.jagdeep.shared.<capability>`.
- Platform code belongs in `androidMain`, `iosMain`, or `jvmMain`, behind a common interface/`expect` declaration only where a common API is required. Keep platform implementations out of `commonMain`.

## Template-project cleanup and rename rules

When creating a new project from this template, perform a targeted product cleanup. Do not treat a product rename as permission to delete reusable architecture.

- First inventory each module and classify every file as **template infrastructure**, **common feature pattern**, or **old-product behavior/assets**. Review the module build files, navigation registration, platform entry points, and tests before deleting anything.
- Retain all reusable `core:*` modules and their module structure unless a module is demonstrably old-product-specific. Adapt models/DAOs carefully instead of deleting the entire infrastructure.
- Retain the common feature-module pattern even if the old feature's behavior is removed: its Gradle KMP targets, `data`/`domain`/`ui`/`navigation` package skeleton, ViewModel state/event pattern, Navigation 3 entry provider, tests, and app-shell registration. Replace old-product content inside that structure with the new app's feature, or leave a small empty-state implementation until its phase begins.
- Preserve the app shell in `composeApp`: `App`, adaptive navigation scaffold, top-app-bar/toast infrastructure, `AppNavHost`, navigation state, platform entry points, and desktop window configuration. Rename packages/resources and routes deliberately; do not delete the shell because a template screen is obsolete.
- Retain the template's foundational feature modules (including `feature:shared`, `feature:home`, and `feature:settings` where present) during cleanup. Remove or replace only old-product behavior, assets, models, repositories, and routes while preserving Gradle/KMP setup, package skeletons, ViewModels, navigation entry providers, tests, and app-shell registration.
- Retain `feature:shared` as the cross-feature contract module. Remove old-product contracts and implementations from it, then introduce the new product's shared contracts when two or more features need the same model/store/use case.
- Before deleting a feature, core API, dependency, database table, asset, or platform implementation, search all source sets, Gradle files, tests, documentation, and generated-resource references. Remove only the old-product dependency chain and update every affected registration/reference in the same change.
- Do not delete a feature merely because its old screen is product-specific. Keep or recreate its standard skeleton when the new product needs that app concern (for example Home, Sharing, Settings, History, or onboarding).
- Product-specific items that are normally safe to remove after reference checks include old brand assets, old package names, old route names, old media/content parsers, product-only repositories/DAOs/entities, legacy external-tool binaries, and documentation tied solely to the old product.
- After cleanup, verify: Gradle settings include every retained/new module; build files have no stale project dependencies; `App.kt` and `AppNavHost` register the intended top-level features; Android/iOS/JVM entry points use the new package; Room schema/database code has no stale entities; and focused JVM tests compile.
- Never delete broad directories such as `core`, `feature`, `composeApp`, or database schema folders as a shortcut. Use explicit file-level targets and retain a compileable, navigable application after each cleanup step.

## Navigation, UI, and DI

- Reuse the Navigation 3 infrastructure in `core:ui`: central top-level routes, serialized saved state, `Navigator`, `NavigationState`, and an `AppNavHost` assembled by `composeApp`.
- Let each feature own its navigation entry provider. Keep top-level navigation items in `composeApp`.
- Screens receive ViewModels, never repositories directly. Entry providers create ViewModels and supply dependencies.
- Use Koin for new dependency-injection registrations while preserving compatible existing composition-root behavior during incremental migration.
- Do not instantiate repositories, Room databases, socket clients, or preference stores inside a screen or a ViewModel. The composition root supplies them through Koin or, during the current incremental migration, through a Navigation entry-provider ViewModel factory.
- A screen accepts a ViewModel plus UI callbacks only. It renders immutable UI state and sends user events to the ViewModel; it does not collect from repositories or perform I/O.
- A ViewModel belongs in the feature's `domain` package. It exposes one immutable `StateFlow<FeatureUiState>`, owns UI-event handling, calls use cases/repositories, maps domain errors to user-safe state, and never imports Compose UI types, Android/iOS APIs, or database entities.
- Keep ViewModel construction in the feature's `ui/navigation` entry provider. The provider receives dependencies from `AppNavHost`; it must not reach into another feature to obtain them.
- Use a repository interface in `domain` whenever storage, networking, discovery, secure storage, or a replaceable platform capability is involved. Place the production implementation in `data`, inject the interface, and use fakes in tests. Do not create a repository abstraction for pure, local one-function transformations.
- Reuse Material 3, the existing adaptive app shell, app bars, standard loading/empty/error states, toast/snackbar abstractions, and accessibility patterns.
- Build responsive desktop/mobile layouts with keyboard navigation, focus behavior, semantic labels, light theme, and dark theme.

## UI creation rules

- Follow current Google Material 3/Android adaptive-layout guidance and Apple Human Interface Guidelines. Use the existing Compose Multiplatform Material 3 theme, semantic colors, typography, spacing, navigation, interaction states, and accessibility support before inventing custom patterns.
- Use Material 3 as the shared design-system baseline. On iOS, also respect Apple conventions for navigation, safe areas, typography scaling, touch feedback, modal presentation, destructive actions, document selection, permissions, and platform terminology. Do not force an Android-specific interaction on iOS when the Apple convention is clearer or expected.
- When Material 3 and Apple HIG differ, keep shared visual tokens and feature behavior consistent, but choose the platform-native interaction model behind a small platform-specific composable/API. Examples include back navigation, modal/sheet presentation, file/document pickers, permission prompts, context menus, keyboard behavior, and lifecycle/background-state messaging.
- Never imitate iOS chrome on Android/Desktop or Android Material chrome on iOS solely for visual uniformity. Preserve the product's identity on every platform while behaving naturally on the host platform.
- Prefer a simple, flat, calm interface. Use clear page hierarchy, whitespace, dividers, section headings, and standard list rows before adding containers or visual decoration.
- Do not wrap every section, setting, or action in a `Card`. A card is justified only for a genuinely distinct, self-contained, actionable group that benefits from visual separation. Avoid nested cards, card grids for ordinary settings, excessive elevation, gradients, decorative borders, and redundant surfaces.
- Settings screens should primarily use grouped preference rows with concise labels, supporting text only when useful, switches/selection controls, and dividers. Do not render every setting as a card.
- Task-oriented screens should foreground the primary task, current status, and next action. Keep secondary diagnostics or advanced controls visually quieter and progressively disclosed.
- Use Material 3 components as intended: top app bars for page context, navigation rail/bottom navigation through the existing adaptive scaffold, `ListItem`/rows for lists, `OutlinedTextField` only for editable input, dialogs for confirmation/destructive choices, and standard progress indicators for transfer state.
- Maintain one obvious primary action per screen or section. Use filled buttons for the primary action, tonal/outlined/text buttons for secondary actions, and do not duplicate the same navigation/action in multiple prominent controls without a user-flow reason.
- Design responsively from the same composable structure: compact mobile layouts stack content; medium/expanded desktop layouts use readable maximum widths, panes, or columns only when they improve scanning. Do not make desktop a stretched phone layout or a dense dashboard by default.
- Preserve comfortable touch targets, keyboard focus order, visible focus indications, scalable text, meaningful content descriptions, contrast in light/dark themes, and state feedback for loading, empty, success, offline, and error cases.
- On iOS, honor safe-area insets, Dynamic Type, VoiceOver labels/actions, system gesture/back behavior, and the user's reduced-motion setting. Do not promise background transfer continuity beyond iOS lifecycle capabilities.
- Keep copy direct and human: state what happened, what the user can do next, and any security implication. Do not expose protocol jargon, raw exceptions, credentials, or internal implementation details in UI text.
- Use the product's primary brand color as an accent for key actions and status, not as a large background or substitute for hierarchy. Respect theme tokens instead of scattering hard-coded colors.
- Colour palettes live in `core:ui` themes, one `Palette<Name>.kt` per palette, registered in the `Palette` enum. `Palette.Default` is the standard, product-neutral palette for every project on this template; a product chooses its own default palette in its app or shared preferences, never by editing `Palette.Default`. Every palette must keep all text pairs at 4.5:1 or above in light and dark.
- Top app bar: every screen except the start destination shows a back button in the navigation-icon slot. The start destination shows the app icon in that same slot, never inline in the title, so every title starts at the same position and keeps the same gap from the icon.
- Top-level navigation on phones: the top-level destinations are pages of `TopLevelPager`, so the neighbouring destination follows the finger while swiping and the pager handles flings and settling. Swiping is enabled only at the root of the current destination's stack. Each destination keeps its own back stack, and back at the root of a non-start destination returns to the start destination. Desktop navigates with the sidebar and a fade; it does not swipe.
- Motion must feel continuous: a gesture's hand-off never pauses or steps backwards, pushes slide forward and pops slide back, and transitions stay short (about 300 ms or less). Do not draw a neighbouring screen as empty space during a gesture.
- On Android, keys for pagers, lazy lists, and saveable state must be types a `Bundle` can store (primitives or strings); use an index or string id, never a route object.
- Before introducing a reusable component, check `core:ui`. Put an app-wide pattern there only after it is used by multiple features; otherwise keep it feature-private under `ui/components`.

## Data and testing

- Keep business logic behind interfaces for repositories, crypto, time, randomness, transport/discovery, and platform services; provide fakes for tests.
- Store ordinary persistent application state in Room when querying or transactions are needed, and use platform secure storage for credentials and secrets. Encrypt sensitive persisted values when required.
- Test business logic without Room, network, Android framework, or native APIs wherever practical.
- Add common/JVM tests alongside each shared/domain change; run relevant platform builds/tests before claiming support.

## Thorough testing infrastructure

- Treat tests as part of each feature/module implementation, not as a final cleanup task. Every behavior change must add or update the narrowest useful automated test.
- Keep shared/domain tests in `src/commonTest`; use `src/jvmTest` for desktop/JVM-only behavior; use `androidUnitTest`/Android instrumentation tests and iOS tests only for behavior that genuinely requires those platforms. Do not duplicate the same pure-business test across targets.
- Prefer the testing pyramid: many fast deterministic unit tests, focused repository/integration tests, a small number of navigation/UI tests, and a minimal set of end-to-end smoke tests for critical flows.
- Unit-test ViewModels, use cases, reducers/state machines, validators, mappers, serializers, error mapping, and repository contracts using fakes. Control time, randomness, dispatchers, and external callbacks; never use arbitrary sleeps or live network calls in unit tests.
- Repository tests must verify persistence/network boundaries, mapping, transactions, cache/error behavior, and safe failure handling. Use an isolated temporary database or test database; never point tests at a developer's real app data.
- Database tests must verify DAO queries, constraints, transactions, schema generation, and migrations when migrations are approved. Keep schema exports versioned and review changes as source changes.
- Add contract tests for every common interface with meaningful platform implementations. The same expectations must run against fakes and, where practical, JVM/Android/iOS implementations.
- UI tests should verify observable user outcomes: screen state, enabled/disabled actions, empty/loading/error/success states, navigation, semantic labels, keyboard/focus behavior, and accessibility-relevant content. Do not make tests depend on incidental pixel positions, animation timing, or internal composable implementation details.
- Use stable test data builders/fixtures and descriptive test names in the form “given condition, when action, then outcome.” Keep fixtures local to the module unless truly shared; never encode secrets, real user data, or machine-specific paths.
- For asynchronous `Flow`/coroutine code, use coroutine test dispatchers and deterministic collection helpers. Ensure every launched test coroutine is completed/cancelled and every resource/socket/file is closed in test teardown.
- Mark slow, platform-bound, benchmark, and end-to-end tests separately so normal local feedback remains fast. Benchmarks report measurements only; they must not be used to fabricate performance claims or fail unpredictably due to host load.
- Each module should expose focused Gradle test tasks and keep them runnable independently. Before delivery, run the smallest relevant test set plus compilation for affected targets; before a release/major phase, run the full available test suite and record unavailable platform validation honestly.
- New modules require at minimum: one compilation check, unit tests for their public/shared logic, fakes for external boundaries, and a test proving their registration/wiring is reachable where applicable.
- A test failure must be fixed, explicitly quarantined with a tracked reason, or reported as a blocker. Do not delete/disable a failing test merely to make a build pass.

## Database, secure state, and settings

- `core:persistence` is the only owner of Room database construction, entities, DAOs, schema files, and database migrations. Features depend on repository interfaces, never directly on a DAO or `AppRoomDatabase`.
- `core:persistence` also owns reusable non-sensitive preference persistence contracts (for example `PreferencesStore<T>`). Features own their typed preference models, then consume the core contract through dependency injection.
- Use official AndroidX DataStore Preferences as the production KMP backend for ordinary preference stores. Expose asynchronous `Flow` reads and suspend writes through the core contract; keep DataStore keys and storage-path details out of feature code.
- Keep preference categories separate: `PreferencesStore<T>` is only for ordinary non-sensitive data; `SecurePreferencesStore<T>` is only for secrets and must be implemented with platform secure storage. Never provide an ordinary-preferences or production in-memory fallback for the secure contract.
- Database entities live in `core:persistence`; convert them to feature/domain models in a repository implementation. Do not expose Room annotations, entities, or DAO flows through a feature's domain API.
- Persist ordinary, recoverable application state in Room when it needs history, querying, or transactional updates.
- Store credentials, private keys, and other secrets only in platform secure storage. Never put them in Room, `feature:shared`, JVM Preferences, logs, or navigation saved state.
- `feature:shared` preferences must be explicitly non-sensitive. Add a value there only when it is needed by multiple features and safe for ordinary preference storage.
- Each settings area owns a typed preference model and store interface. Do not scatter string keys across screens. Use defaults in the model, preserve unknown/absent values safely, and make changes observable through the ViewModel state.

## App startup and initialization

- `App()` is the UI composition root, not a service locator. Create each app-scoped dependency exactly once with `remember` (or retrieve it from initialized Koin), then pass it down through `AppNavHost` to feature entry providers.
- Use this startup order in `composeApp/.../App.kt`:
  1. Apply `AppTheme` and create the root `Surface`.
  2. Initialize app-scoped, non-UI dependencies once.
  3. Create app UI state once: top-app-bar state, toast host, navigation state, and `Navigator`.
  4. Provide app-wide UI locals and render `AdaptiveNavigationScaffold`.
  5. Pass dependencies and platform callbacks into `AppNavHost`; `AppNavHost` delegates ViewModel construction to feature entry providers.
- When adding a database-backed repository, secure store, discovery service, or transfer runtime, initialize its platform implementation before the first feature uses it, expose it by interface, and keep lifecycle ownership at the app/Koin composition root. Close/cancel app-scoped resources through the appropriate platform lifecycle hook; do not recreate them on recomposition.
- Platform `actual` factories may create platform implementations, but must not embed feature UI or business rules. Android/iOS/desktop settings persistence must all implement the same common store interface before being called cross-platform.

## Release builds and private files

- Keep plans in `private/plans/`, personal notes in the git-ignored `private/misc/`, and signing material in the git-ignored `private/signing/`. Never commit keystores, `keystore.properties`, or passwords.
- Android release signing reads a git-ignored `keystore.properties` at the project root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without it the release build is unsigned, so CI still builds. Generate a dedicated key per app with a random password and long validity, and keep an off-machine backup: losing it blocks app updates.
- Build Android, Apple Silicon macOS, and iOS releases locally on the Mac. CI builds only what the Mac cannot (Windows first; Linux and Intel macOS when needed), and runs only when asked.
- A packaged macOS app that uses the local network must declare `NSLocalNetworkUsageDescription` and its Bonjour service types (`NSBonjourServices`) in the Info.plist; otherwise macOS blocks discovery in the packaged app even though IDE runs work.

## Delivery process

- Inspect and propose architecture before major implementation phases; wait for approval when requested.
- Add focused tests for domain rules, security, input validation, cancellation, recovery, data integrity, and platform interoperability as applicable to each feature.
- Use Material 3 and the existing adaptive UI system.
- Keep the app name configurable.
- Show changed-file ownership and test/build evidence in each delivery summary.

# Part B — Vaulty-specific instructions

## Project conventions

- Vaulty is an offline-first, locally encrypted password manager. Priorities, in order: security, privacy, reliability, maintainability, performance, UX. Follow [the project guide](private/VAULTY_PROJECT_GUIDE.md) for architecture and [the security instructions](private/VAULTY_CHATGPT_INSTRUCTIONS.md) for the threat model, cryptography, and acceptance criteria. [The security and architecture explanation](private/VAULTY_SECURITY_AND_ARCHITECTURE.md) describes how it works today and its known weaknesses; update it when the design changes.
- No backend, account, analytics, or telemetry. The Android `INTERNET` permission exists only for local-network sync sockets; Vaulty never contacts a server of its own. Sync while apart goes through a folder another app syncs (iCloud Drive, Google Drive), holding only encrypted files. `core:network` and `core:parser` are inherited template modules that Vaulty does not use; don't wire them into features.
- The UI follows the Zynk template's design system in `core:ui` (palettes, `AdaptiveNavigationScaffold`, `TopLevelPager`, `TopAppBarState`, toasts) and its rules above. Reuse the shared rows in `core:ui` (`SectionHeader`, `SwitchRow`, `PasswordField`, `StatusLine`, `Monogram`, `EmptyState`, `ReadableColumn`) before writing new ones.
- `Palette.Default` is Vaulty's default palette (`DEFAULT_COLOR_PALETTE` in `feature:shared`). Appearance (theme and palette) is ordinary, non-sensitive preferences, read before unlock.
- Navigation has two shells in `composeApp`: the locked shell (`RouteFlow.Unlock`, `RouteFlow.BackupRestore`; full screen, no navigation bars) and the unlocked shell (top-level `Vault` (start), `Generator`, `Security`, `Settings`). The unlocked shell is keyed by the unlocked keyring; locking discards it and all its state.
- The Room schema stays at version 1 during development by owner decision (2026-10-31): after a schema change, delete the database or reinstall instead of writing a migration.
- Vaulty has no `feature:home`: the Vault tab is the start destination (decided 2026-10-31). This is a deliberate exception to Part A's "retain `feature:home`" rule.
- Cross-feature navigation uses `RouteFlow` keys in `core:ui` (for example, Security opens `RouteFlow.ItemDetail`); a feature never imports another concrete feature.
- The shared core modules (`core:common`, `core:logger`, `core:network`, `core:notifications`, `core:parser`, `core:persistence`, `core:ui`) are reused across projects. Change them only with explicit approval, and keep them product-neutral. `core:ui` was replaced with the Zynk template's design system on 2026-10-31 at the owner's request.

## Security and validation

- Never implement cryptographic primitives. One keyring (the master vault) opens every vault: Argon2id derives a key from the master password that wraps a random keyring key, which wraps each vault's own random key (VEK). AES-256-GCM does the wrapping and encrypts each item with record identity bound as AAD. Every platform's Argon2id must use the saved parameters exactly, so a vault opens on any device; test with the shared reference vector. Credential fields are encrypted before they reach Room; no plaintext secret goes to Room, preferences, temporary files, logs, exceptions, navigation keys, saved state, or backups.
- Route keys hold opaque item IDs only. Editor drafts and typed passwords live in ViewModels (in memory), never in `rememberSaveable` or navigation state. Encode typed master passwords to bytes only for the call and wipe them afterwards.
- Secrets are hidden by default, revealed explicitly, and hidden again after 30 seconds. Copied values clear from the clipboard after 30 seconds when unchanged. The vault locks after 5 minutes without input, on backgrounding, and when the desktop window loses focus.
- Biometric/device unlock is optional convenience, never the root of trust; the master password always works.
- Quick unlock (PIN/biometrics) seals the keyring key under a slot bound to the keyring's ID; it is per device and never in backups or sync. A PIN-sealed key is always wrapped again by a hardware/OS-held device key; five wrong PINs delete it. The master password is required after a restart, every 14 days, and for export, master-password change, and vault deletion.
- Own-device sync uses the keyring key as its trust root; each shared vault syncs on its own channel keyed by that vault's key, reaching only that vault's items (see private/plans/MULTI_VAULT_AND_RICH_ITEMS_PLAN.md). Only encrypted rows travel, over the network or through a sync folder; every live row is verified by decrypting under its vault key and its own ID before it is stored. `core:sync` moves opaque frames only and must stay vault-agnostic.
- A vault set to "ask again" keeps its key out of the session until the user confirms again. Notifications never name an item or reminder.
- QR sharing carries only non-secret details (name, username, website) or a one-time connection code. Never put passwords, notes, keys, or backup data in a QR code; those travel inside the encrypted channel the code opens.
- Copy is direct and human, and never exposes raw exceptions or crypto jargon. Wrong-password and tampered-data errors don't reveal which check failed.
- Add focused tests for crypto vectors, wrong passwords, tampering, backup/restore, key wiping, and every ViewModel state change. Claim platform support only with recorded evidence. Build iOS with `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` (the system default points at the Command Line Tools); the simulator has no Bluetooth or enrolled Face ID, so those need a real iPhone.

## Project documentation

- Keep plans, proposals, roadmaps, phase tracking, and implementation status in `private/` (plans in `private/plans/`, personal notes in the git-ignored `private/misc/`). Part B contains durable project instructions and links, not the plans themselves.
- `private/zynk-cmp-main/` is a read-only reference copy of the Zynk template project (git-ignored). Use it to match patterns; never edit it or depend on it from the build.
