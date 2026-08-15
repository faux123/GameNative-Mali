# GameNative-Mali: Architecture / Execution Plan

Companion to [PRD.md](PRD.md). File:line references are against fork point
`b0424df` (upstream master, 2026-08-13); they are documentation anchors
only. Actual patches must be content-anchored (context diffs, not line
offsets) so upstream syncs don't churn them.

Reviewed by /challenge (Gemini): round 1 folded in, round 2 returned
"CONVERGED — No remaining architectural flaws block starting Phase 0"
(2026-08-14) with its IMPORTANT/MINOR leftovers folded in below. Disputed-and-rejected findings, with evidence: "future
dates are fabricated" (reviewer's knowledge cutoff; dates are real),
"applicationIdSuffix breaks JNI" (suffix does not change the
`app.gamenative` namespace or Java packages; upstream itself ships a
`.gold`-suffixed flavor with the same jniLibs).

## How the stock app actually works (recon summary)

- Two halves: `app/gamenative/` (Kotlin/Compose Steam client, all graphics
  POLICY) and `com/winlator/` (vendored Winlator MECHANISM). The seam is
  `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt`
  (~5900 lines): reads the `.container` JSON, builds env vars, extracts
  drivers and DX wrappers, boots the XEnvironment.
- Flavors: `legacy` (targetSdk 28, bundles all driver/dxwrapper tzsts from
  `app/src/legacy/assets/`) vs `modern` (targetSdk 36, `MODERN_ANDROID=true`,
  downloads components from `downloads.gamenative.app` per
  `assets/*_download.json` manifests, caches in `filesDir/assets/`).
  Upstream's tagged releases build ONLY legacy (+legacyXr)
  (`.github/workflows/tagged-release.yml:80`); the Ace's installed 1.1.1
  (targetSdk 36) is a modern build from the push-to-master workflow.
- Native `.so` files are checked-in prebuilts; every `externalNativeBuild`
  block is commented out (`app/build.gradle.kts:280-314`). The wrapper is
  NOT a jniLib: it ships as a `.tzst` payload extracted into imagefs at
  launch. We never need the Gradle native build for wrapper work.
- `ALWAYS_REEXTRACT = true` (`XServerScreen.kt:229`): wrapper + DXVK +
  driver files are redeployed on every launch. Great for iteration; it is
  upstream's current value and stays untouched, but it costs launch-time
  disk I/O on every boot. If it ever flips upstream, our raw-config-edit
  workflows must account for the marker comparisons coming back to life.
- GPU detection is Adreno-only (`com/winlator/core/GPUInformation.java`).
  Mali currently falls into the default else-branch of
  `ContainerUtils.getDefaultContainerData()` (`ContainerUtils.kt:86-95`):
  `Wrapper-gamenative` + `dxvk async-1.10.3` + vkd3d 2.14.1. Exactly three
  `Mali` string matches exist in the whole tree (BOX64_MMAP32=0 in the two
  program launcher components; zink_dlls skip at `XServerScreen.kt:5616-5623`).

## Design decisions

1. **Side-by-side install.** `applicationId` gets a `.mali` suffix in our
   builds. Rationale: upstream APKs are signed with their key; an in-place
   upgrade of `app.gamenative` with our key is impossible without
   uninstall, which would destroy 42 wine prefixes (saves live in
   `.wine/drive_c`). To be precise: side-by-side ISOLATES rather than
   migrates; the fork starts with zero prefixes and zero installed games
   (card-resident game dirs can be re-bound, but prefixes/saves stay with
   stock unless root-copied deliberately). That cost is accepted; stock
   stays untouched.
   Empirical gate: Steam login is not expected to check package name or
   signature (Steam-network protocol, not store OAuth), but this is
   verified, not assumed, in Phase 0.
2. **Target flavor = modern** for the Ace (Android 13), and our CI builds
   both modern and legacy. Because modern downloads components, our fork
   must repoint the download host or pre-seed the cache; until Phase 4 we
   ALSO bundle our Mali components in `app/src/main/assets/` so both
   flavors work offline (bundled asset is preferred over download:
   `DXWrapperDownloader.kt:62-66`).
3. **All Mali behavior is additive and gated** on a new
   `GPUInformation.isMaliGPU()` so Adreno paths are untouched (PRD
   non-goal: no Adreno regressions, cheap upstream merges).
4. **Proven default stays.** Mali default container remains
   `Wrapper-gamenative` + `dxvk async-1.10.3` until the modern-DXVK path
   demonstrably renders correctly; then we flip the default in ONE place
   (`ContainerUtils.kt` Mali branch).

## Phases

### Phase 0: Build bring-up (no code changes)

- CI: fork `tagged-release.yml` into `mali-release.yml`: build
  `assembleModernRelease` + `assembleLegacyRelease` (direct `.apk`
  artifacts; upstream's bundle+bundletool universal-APK dance is only
  needed for Play-style delivery), debug-sign (upstream's keystore
  secrets don't exist in our fork), publish GitHub release on `v*-mali`
  tags. Drop Discord/PostHog secrets.
- Gradle: `applicationIdSuffix = ".mali"`, `versionName = "<upstream>-mali.N"`.
- Deliverable: unmodified-behavior APK installs on the Ace next to stock,
  boots, **Steam login succeeds on the renamed debug-signed build** (the
  /challenge reviewer flagged possible package/signature gating; settle it
  here), installs and launches one game. Proves the toolchain end to end.

### Phase 1: Mali detection and defaults (Kotlin/Java only)

- `GPUInformation.java`: add `isMaliGPU()`, GLES renderer string
  ("Mali"/"Immortalis") as the PRIMARY signal with Vulkan vendorID
  `0x13B5` via the existing `getVendorID` JNI as confirmation. Both
  queries run in the Android app process against the system driver, so
  neither can be affected by the in-container gpu_cards identity spoof
  (challenge round 2 corrected the earlier rationale); renderer-first is
  chosen because it is already cached in prefs and matches how the
  existing Adreno predicates work, and
  `isValhallCSF()` (renderer matches G[3679]1[05]/G7[12]0/G925/Immortalis).
- `ContainerUtils.kt:86-95`: insert an explicit Mali branch ahead of the
  generic else. Initially identical values (Wrapper-gamenative +
  async-1.10.3) so behavior is unchanged but the branch is loggable and
  flippable.
- `BestConfigService.kt:211-262`: on Mali, reject `gpu_family_match` /
  `fallback_match` configs authored for Adreno (today a Mali device can
  receive an Adreno-authored server config unfiltered).
- `GPUInformation.isDriverSupported` (`:209-216`): returns false for any
  non-System driver on non-Adreno; extend so Mali wrapper packages are
  legal once Phase 2 lands.
- Deliverable: logcat shows the Mali branch taken on the Ace; Adreno unit
  tests (`pluvia-pr-check` set) still green.

### Phase 2: Rebuilt wrapper as a first-class Mali driver

- Build leegao's bionic-vulkan-wrapper from source (Mesa-tree fork, built
  via `leegao/vulkan_wrapper_termux-packages`; pin the commit of the last
  release Bannerlator/GameNative package = "leegao ICD 2025-10").
- Patch set (kept as `patches/` in this repo, applied in CI):
  1. `spirv_edit.cpp`: replace the six hardcoded
     `SPV_ENV_VULKAN_1_1_SPIRV_1_4` with an env selected from the device's
     advertised Vulkan version (1.3 blob → `SPV_ENV_VULKAN_1_3`), plus a
     `WRAPPER_SPIRV_ENV` env override for A/B testing. Insertion point for
     the app-side knob already exists: `WRAPPER_NO_PATCH_OPCONSTCOMP` is
     set at `XServerScreen.kt:5556-5559`.
     Scope honesty (challenge round 1): raising the env only makes
     spirv-tools PARSE SPIR-V 1.5/1.6; the custom passes may still
     mishandle newer opcodes. The patch must (a) preserve the existing
     fail-open behavior (pass failure → original shader passes through,
     verified in wrapper_device.c), (b) run `spirv-val` on every
     transformed module in debug builds and fall back on validation
     failure, (c) treat per-opcode pass fixes as follow-up work driven by
     real logcat evidence, not up-front rewrites.
  2. Keep the Mali quirk passes and BCn compute path untouched.
- Package `wrapper-mali-<date>.tzst` (same layout as wrapper-leegao.tzst:
  `usr/lib/libvulkan_wrapper.so` + hook libs + `wrapper_icd.aarch64.json`).
- Wire in:
  - `app/src/legacy/assets/graphics_driver/` (bundle for legacy flavor).
  - Modern-flavor gap (challenge round 2): `GraphicsDriverDownloader`
    only short-circuits to bundled assets on LEGACY; under
    `MODERN_ANDROID=true` it downloads per the manifest
    (`GraphicsDriverDownloader.kt:46-92`), unlike `DXWrapperDownloader`
    which prefers a bundled asset on any flavor. Two options, pick one at
    implementation: (a) small additive patch teaching
    `GraphicsDriverDownloader` to check `app/src/main/assets/` first
    (mirrors the DXWrapper behavior), or (b) host `wrapper-mali` at our
    Phase 4 endpoint before the modern build can use it. Default: (a),
    it keeps modern builds offline-capable.
  - `app/src/main/assets/graphics_driver_download.json`: add
    `wrapper-mali` component (all `*_download.json` paths in this doc are
    under `app/src/main/assets/`, not repo root).
  - `arrays.xml:26-32` `bionic_graphics_driver_entries`: add
    `Wrapper-mali (Mali G610+)`.
  - Extraction needs nothing new: `XServerScreen.kt:5590-5625` already
    extracts any selection starting with `wrapper`.
- Verification (prove-it-works): logcat during a DXVK 2.x launch shows the
  fixup passes RUNNING (no "Invalid SPIR-V binary version" errors);
  regression: async-1.10.3 titles still render (MM11 gameplay frames).

### Phase 3: Modern DXVK for Mali

- Source: The412Banner/Nightlies `DXVK-v3.0.2-binsem-gplasync[-arm64ec]`
  recipe (upstream master + gplasync + binary-semaphore fallback), or our
  own build of the same with `--enable-debug`-class logging so failures
  name themselves. Repack `.wcp` → `.tzst` with the in-tree helper
  (`tools/convert-wcp-to-tzst.sh`).
- ARCH PAIRING (challenge round 1, valid): the DXVK binary arch must match
  the container's wine. Stock GameNative wine runs x86_64 PE DLLs under
  FEX/Box64 → ship the plain x86_64 binsem build as the default; the
  arm64ec build is only valid when an arm64ec Proton is the container's
  wine, and must be gated on that (wineVersion check) or not offered.
- PACKAGE LAYOUT (challenge round 2, valid): the repacked `.tzst` must
  carry BOTH trees, `system32/` (64-bit) and `syswow64/` (32-bit x86),
  matching the existing dxwrapper package layout; dropping syswow64
  breaks 32-bit D3D titles under WOW64.
- Wire in (all three required, else modern-flavor extraction silently
  fails: `DXWrapperDownloader.kt:52-54`):
  - `app/src/main/assets/dxwrapper/dxvk-3.0.2-binsem-gplasync.tzst`
  - `arrays.xml:91-102` `dxvk_version_entries`
  - `assets/dxwrapper_download.json`
- Check `ManifestComponentHelper.buildDxvkContext` (`:212-286`) constraints
  don't hide the new version on wrapper drivers.
- Diagnosis loop for the standing E_FAIL: DXVK env already plumbed via
  `DXVKHelper.java:20-64`; set `DXVK_LOG_LEVEL=info` + file path on Mali
  debug builds. Working hypothesis (consistent with the challenge
  reviewer's framing AND our trace): a DXVK userspace capability check
  fails without any erroring Vulkan call. Our trace shows VkDevice
  creation and internal shader-module compiles SUCCEEDING before the
  E_FAIL, so the gate is after device init, not adapter rejection; the
  logging-enabled build names the exact check either way. First target
  title: Dreamscaper (UE4 4.25, FL 11_0).
- Deliverable: either a UE4 DX11 title renders on DXVK 3.0.x on the Ace
  (gameplay-frame judged), or the exact DXVK-side refusal is documented
  with an upstream issue.

### Phase 4: Catalog ownership

Three independent manifests to repoint (keep upstream URL as fallback):

| Manifest | Consumer | Where |
|---|---|---|
| `downloads.gamenative.app/<file>` primary + R2 fallback | all component downloaders | `SteamService.kt:1558-1580` |
| hosted component `manifest.json` | ManifestRepository/Installer | `ManifestRepository.kt:13` |
| landing-page `data/manifest.json` | DriverManagerDialog | `DriverManagerDialog.kt:128` |

Ours host on GitHub Pages for the JSON manifests (raw.githubusercontent
rate limits and 5-minute caching make it a poor primary endpoint;
challenge round 1) and GitHub release assets for the binaries. Mali
wrapper/DXVK updates then ship without APK bumps.

### Phase 5: Upstream tracking

- `master` mirrors upstream; all work on `mali` branch; releases tagged
  `v<upstream>-mali.N`.
- Merge upstream master into `mali` at least per upstream release. The
  Mali diff is: 2 new asset files, 3 array entries, 1 new class method
  set, 1 gated branch in ContainerUtils, 1 gated filter in
  BestConfigService, CI workflow, docs. Everything else untouched.

## Test matrix (per prove-it-works)

| Check | Instrument |
|---|---|
| Wrapper passes run on SPIR-V 1.6 | logcat VkWrapper (no "Invalid SPIR-V binary version") |
| Rendering correctness | live-gameplay screencaps with player character on screen (never cutscenes/menus) |
| DX11 FL 11_0 device creation | Dreamscaper boots past "DX11 feature level 10" error |
| Perf | in-game FPS HUD on uncapped scenes; same scene A/B vs stock GameNative |
| No Adreno regression | unit tests + unchanged defaults for Adreno branches (code review; no Adreno hardware on hand, state this in release notes) |

## Open items

- Wrapper build reproducibility: exact Mesa-tree commit + spirv-tools
  version for "leegao ICD 2025-10" must be pinned before Phase 2 starts.
- Whether `WRAPPER_VK_VERSION=1.3.231` interacts with the raised SPIR-V
  env (wrapper advertises what config says; env selection should read the
  BLOB's version, not the spoofed one).
- gpu_cards.json spoof identity: games see an NVIDIA/AMD/Intel name;
  unchanged for now.

## Empirical status (2026-08-14, mali.1 -> mali.10, RK3588 / Mali-G610 Ace)

On-device testing this session settled several phase assumptions. Where a
phase's premise turned out wrong, the corrected reality is recorded here.

### Baseline captured
Mega Man 11 (appid 742300) runs on the fork's out-of-box Mali stack
(Wrapper-gamenative + arm64ec Proton + FEXCore + DXVK). 720p: intro
cutscene 44-46 fps, title 27-32; FPS-only green HUD shows by default. This
is the reference the rest is measured against.

### proton default bug (fixed)
setContainerDefaults pinned `proton-10.0-arm64ec-2`, which is not installed
(only `proton-11.0-1-arm64ec-1` and `proton-9.0-arm64ec` ship). Fresh
containers referenced a missing Proton, the prefix never unpacked, nothing
booted. Fixed ContainerUtils to `proton-11.0-1-arm64ec-1`.

### Phase 3 (DXVK) reality: DXVK 1.x Sarek is a HARDWARE ceiling
Mali-G610 lacks the core Vulkan features `logicOp`, `fillModeNonSolid`,
`shaderClipDistance` (confirmed from the device's own wrapper log and
vulkan.gpuinfo.org across the Mali family; these are core
VkPhysicalDeviceFeatures, not extensions). Consequence, verified on MM11:
- async-1.10.3 / 1.11.1-sarek / 1.12-sarek (DXVK 1.x line): render (kept).
- 2.4.1-gplasync / 2.6.1-gplasync (mainline DXVK 2.x): BLACK SCREEN. Removed
  from selection.
- 3.0.2-mali-compat (zeyadadev DXVK 3.0.2 + Mali patches): ERR03 "Failed to
  initialize DirectX11", even with the SPIR-V workaround and
  `d3d11.forceLocalSharedResources`. DXVK 3.x needs more than this wrapper
  exposes. Not shipped.
Integrated DXVK-Sarek 1.12 (`dxvk-1.12-sarek.tzst`) and generalized the
Sarek `WRAPPER_NO_PATCH_OPCONSTCOMP` env from the pinned `1.11.1-sarek` to
`contains("sarek")`. Mali DXVK default stays `async-1.10.3` (the FPS delta
across the working set is within noise). No newer DXVK is usable on this GPU
without a newer graphics wrapper, which does not exist (see Phase 2).

### Phase 2 (wrapper) reality: no upgrade exists, and it is not the limiter
The fork already ships `wrapper-gamenative-20260724` (GameNative's build of
the bionic Vulkan wrapper, `libvulkan_wrapper.so`, Vulkan 1.3.289), which is
newer than leegao's public source (master HEAD 2025-08-22) and matches
upstream. The missing DXVK features above are Mali silicon/driver limits, so
no wrapper can add them; "rebuild the wrapper" does not unlock DXVK 2.x/3.x.
Phase 2 as originally scoped (SPIR-V env-bump to unlock modern DXVK) is moot.

### DX12 / VKD3D on Mali: not viable
Mali-G610 cannot do DX12 translation (VKD3D needs Vulkan features Mali
lacks). Community norm is DX11-via-DXVK and DX9-and-older via WineD3D; Turnip
does DX12 on Adreno, not Mali. `vkd3d-2.14.1` stays but is unusable here.

### BCn: forced to software on Mali (mali.9)
Mali has no hardware BCn, only ASTC. `bcnEmulationType=compute` transcodes
BC->ASTC on a GPU compute shader, i.e. re-compresses lossy BC into lossy
ASTC (double compression) -> texture artifacts. Added `isMali` (vendorId
0x13B5) to `excludeBcnCompute` in XServerScreen so Mali always uses the
software BC->RGBA path, matching how Adreno/Xclipse are already excluded.

### Box64 / FEXCore: baked into the Proton, not swappable
`wowbox64.dll` reports Box64 v0.4.2, FEXCore is 2605; both ship inside
`proton-11.0-1-arm64ec-1` (the newest GameNative arm64ec Proton). Upstream
Box64 is v0.4.4 but is not a drop-in (bound to the wine WoW64 ABI). Unity
support already exists via the `UNITY` / `UNITY_MONO_BLEEDING_EDGE` Box64
presets + `BOX64_UNITYPLAYER`. Mono/.NET cost under emulation is
architectural (JIT re-translation), not a component to update. FEX TSO A/B
(INTERMEDIATE vs PERFORMANCE) showed no MM11 change (GPU-bound); PERFORMANCE
is a per-game option for CPU-bound titles.

### UE4/UE5 -> D3D11 Mali default (mali.10)
UE4/UE5 default to the D3D12 RHI, which Mali cannot do, so they die out of
the box. `ContainerUtils.createNewContainer` now detects a 64-bit Unreal
engine tree on Mali Steam games and appends `-d3d11` so they route through
DXVK-Sarek. UE3 (Win32/D3D9, e.g. Guilty Gear Xrd) is excluded by keying on
the Win64 tree. (Not yet verified on an actual UE4 title on-device.)

### Controller fix (mali.10) + upstream PR
No pad input reached any game: the native evshim (`libevshim.so`) builds its
gamepad shm in a C constructor at library load from `getenv(EVSHIM_BASE_PATH)`
else a hardcoded `/data/data/app.gamenative/files`. The fork set that env for
the guest (wine) but not the HOST process, so the host shm landed on the stock
dir the `.mali` app cannot use ("notifyStateChanged missing shm for slot=0").
Fixed by setting `EVSHIM_BASE_PATH = filesDir` in `PluviaApp.onCreate` before
any evshim load. Verified: host mmaps all player slots, zero missing-shm,
controller works in-game. Filed upstream as PR utkarshdalal/GameNative#1818
(package-agnostic; also fixes their own `release-gold` build).

### Per-game findings (candidates for docs/per-game tuning)
- Guilty Gear X2 #Reload (314030, 2003 DirectX 8.1 / DirectDraw): would not
  load at the default 720p; its DirectDraw fullscreen cannot present at a
  non-native mode. Fix: container `screenSize=640x480` (native) + explicit
  `executablePath=ggx2.exe`; enable `directinput8=1` for the pad (DInput-only
  game). Runs 60 fps after.
- Guilty Gear Xrd -SIGN- (376300, UE3 / DirectX 9): NOT 640x480 (modern-res);
  standard DXVK-Sarek D3D9 path; if it crashes in winevulkan on a shader
  module, switch graphicsDriver to `Wrapper-leegao`. Diagnosis in progress.

### Version / signing
Now `versionName 1.1.1-mali.10` (versionCode 29). All mali.N from mali.3 on
share the committed stable debug keystore and upgrade in place. Local builds
(`./gradlew :app:assembleModernRelease`) are the fast path; CI still on tags.
