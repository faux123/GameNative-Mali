# GameNative-Mali: PRD

Fork of utkarshdalal/GameNative (GPLv3) tailored for ARM Mali GPUs,
primarily Valhall CSF parts (G610/G710/G615/G715) on Rockchip RK3588
devices. Reference hardware: GameForce Ace (RK3588S, Mali-G610 MP4,
Android 13, rooted, custom kernel with the g18p0 DDK userspace/firmware
stack).

## Why

GameNative is the best Steam-client-plus-Winlator package on Android, but
its graphics stack is Adreno-first:

- The driver catalog (`graphics_driver_download.json`, hosted manifest)
  ships only Turnip and Adreno KGSL blobs. Mali users get whatever wrapper
  build happens to be bundled, with no updates.
- The default DXVK for Mali-viable rendering is the patched
  `dxvk-async-1.10.3` (BCn glue). It works (verified on the Ace: correct
  character materials in gameplay) but is a 2022-era DXVK: no
  VK_EXT_graphics_pipeline_library path, old shader codegen, no d3d11
  feature work since 1.10.x.
- Stock DXVK 2.x/3.x fails on Mali behind the wrapper. Verified on the Ace
  (Bannerlator 2.9.9, wrapper-leegao, g18p0 blob = Vulkan 1.3.231):
  DXGI enumerates the adapter, VkDevice creation succeeds, then
  D3D11CreateDevice returns E_FAIL with no failing Vulkan call. Two known
  contributing defects:
  1. The leegao wrapper hardcodes `SPV_ENV_VULKAN_1_1_SPIRV_1_4` in all six
     spirv-tools call sites (`src/vulkan/wrapper/spirv_edit.cpp`), so its
     Mali fixup passes (optimization barriers, clip-distance elimination,
     spec-constant composite fix) fail on the SPIR-V 1.6 that DXVK 2.x+
     emits for Vulkan 1.3 devices, and the unpatched shader passes through.
  2. The bundled 2.x builds in Winlator forks are Adreno-tuned ("Vegas").
- Upstream declined to bundle Mali-specific DXVK work
  (The412Banner/Bannerlator issue #139, closed not-planned), and the
  Bannerlator detour proved component-swapping via UI/config is fragile:
  hidden version filters, extract-on-save-only deployment, no logging in
  shipped DXVK builds.

Owning the app removes every one of those constraints: we bundle the Mali
components as first-class defaults, control container defaults per GPU,
and ship debug builds whose DXVK/wrapper logging actually exists.

## What

A maintained fork `faux123/GameNative-Mali` that:

1. **Bundles a Mali-correct driver stack as the default on Mali devices.**
   - leegao bionic-vulkan-wrapper rebuilt from source with the SPIR-V
     target environment raised to Vulkan 1.3 (six sites in
     `spirv_edit.cpp`), so the Mali quirk passes actually run on modern
     DXVK shader output. Keep the quirk passes and BCn compute path.
   - The BCn story unchanged for the proven renderer (dxvk-async-1.10.3
     stays the safe default).
2. **Adds a modern-DXVK option for Mali.** Bundle/offer
   DXVK 3.0.x gplasync + binary-semaphore fallback (The412Banner/Nightlies
   `DXVK-v3.0.2-binsem-gplasync`, binary arch matched to the container's
   wine: x86_64 for stock wine, arm64ec only under arm64ec Proton), which
   carries the upstream
   fixes that matter for Mali (VK_KHR_maintenance11 opportunistic enable,
   device-creation workaround re-add). Root-cause and fix the remaining
   E_FAIL device-creation blocker; the fork gives us logging-enabled DXVK
   builds to finally read the refusal reason.
3. **Mali-aware defaults.** Detect Mali (driverID VK_DRIVER_ID_ARM_PROPRIETARY
   or GLES renderer string) and default new containers to: wrapper driver,
   BCn compute emulation, correct env (no Turnip/Adreno knobs), the proven
   dxwrapper. gpu_cards.json / defaults code updated accordingly.
4. **A Mali driver catalog we control.** Point the driver/component
   manifests at our own hosted JSON (GitHub releases of this fork) so Mali
   wrapper/DXVK updates ship without APK updates. Keep upstream's Adreno
   catalog intact for non-Mali devices.
5. **Tracks upstream.** Regular merges from utkarshdalal/GameNative master;
   Mali changes stay additive and confined (minimal blast radius) to keep
   merges cheap.

## Non-goals

- No changes to the Steam/Pluvia client side (login, depots, saves).
- No Adreno regressions: all Mali behavior is gated on GPU detection.
- No custom kernel/blob work in this repo (that lives in ace-kernel; the
  fork consumes whatever DDK the device has, g17p0 or g18p0).
- Not a general Winlator fork: no UI redesign, no feature sprawl.

## Success criteria

1. APK builds reproducibly from this fork (CI), installs alongside stock
   GameNative on the Ace, and Steam login works on the renamed
   debug-signed build (empirically settled in Phase 0; a reviewer flagged
   possible package/signature gating). Side-by-side means the fork starts
   empty: stock's prefixes and saves are isolated, not migrated.
2. A Mali device gets working defaults with zero manual container surgery:
   fresh install, install game, launch, correct rendering (character
   materials pass on live gameplay, not cutscenes).
3. The rebuilt wrapper demonstrably runs its fixup passes on DXVK 2.x/3.x
   shaders (no "Invalid SPIR-V binary version" errors in logcat).
4. Modern DXVK (3.0.x line) either renders a UE4 DX11 title on the Ace, or
   we have the exact DXVK-side refusal documented from its own logs with an
   upstream issue filed.
5. Perf parity or better vs stock GameNative on the same titles
   (3DMark-class instrument or in-game FPS HUD on uncapped scenes, per
   the established bench rules).

## Users

Paul (GameForce Ace), plus the RK3588 handheld/SBC community (Ace, Orange
Pi 5, Rock 5B, Odroid M1S owners running Android) currently stuck choosing
between GameNative-with-old-DXVK and Adreno-first forks.

## Risks

- The E_FAIL blocker may live in the Mali blob itself (unfixable from
  userspace we control); mitigation: the logging-enabled builds tell us
  quickly, and the proven 1.10.3 path remains the default regardless.
- Upstream churn (repo is active) makes merges costly if our diff sprawls;
  mitigation: additive modules + per-GPU gating, no refactors.
- Wrapper rebuild toolchain (Mesa-tree + spirv-tools via
  vulkan_wrapper_termux-packages) may be finicky; mitigation: pin the
  exact upstream commit leegao's last release used, patch only the six
  SPV_ENV sites first.

## Status update (2026-08-14): premise correction, supersedes What #1-#4

On-device work (mali.1 -> mali.10, Ace / Mali-G610) falsified the central
premise above. Recorded here; original reasoning kept as history.

- **"What" #1 and #2 (rebuild wrapper to unlock modern DXVK): dropped.**
  The fork already ships `wrapper-gamenative-20260724` (Vulkan 1.3.289),
  newer than leegao's public source and already past the SPIR-V-target
  problem. Modern DXVK still fails on Mali because the GPU lacks core Vulkan
  features (`logicOp`, `fillModeNonSolid`, `shaderClipDistance`), which no
  wrapper can add. DXVK 2.4.1/2.6.1 black-screen; DXVK 3.0.2-mali-compat
  (patched build) returns ERR03 at D3D11 init. The E_FAIL/ERR03 blocker is
  the Mali silicon/driver, exactly the "unfixable from userspace" risk
  listed above. So the achievable ceiling is the DXVK 1.x Sarek line.
- **"What" #2 restated:** ship the DXVK-Sarek 1.x family as the Mali modern
  option, not DXVK 3.x. Integrated `dxvk-1.11.1-sarek` and `dxvk-1.12-sarek`
  alongside the `async-1.10.3` default; removed the broken mainline 2.x
  assets from selection. Success criterion #4 resolves as "documented the
  hardware-side refusal + filed upstream," not "renders modern DXVK."
- **"What" #3 (Mali-aware defaults): delivered and extended.** proton default
  fixed (`proton-11.0-1-arm64ec-1`); Mali forced to software BCn (compute
  double-compresses BC->ASTC on Mali); UE4/UE5 Steam titles on Mali default
  to `-d3d11` (D3D12/VKD3D is not viable on this GPU).
- **New scope not in the original PRD: controller correctness.** evshim host
  shm base-path bug fixed (`EVSHIM_BASE_PATH` in `PluviaApp.onCreate`); the
  fix is package-agnostic and filed upstream as PR
  utkarshdalal/GameNative#1818.
- Full technical detail in `ARCHITECTURE.md` -> "Empirical status
  (2026-08-14)". Success criteria #1 (side-by-side install), #2 (zero-surgery
  defaults, MM11 renders on live gameplay), and #5 (perf parity, MM11
  baseline captured) hold. #3 is moot (no rebuild needed). #4 resolves to the
  documented-refusal branch.
