# Tasks

## Pending

- [ ] Keep model downloads running when leaving Advanced. Reuse the retained ViewModel controller instead of cancelling downloads on screen disposal; preserve explicit Cancel and owner teardown. Add a navigation-during-download regression.
- [ ] Recover completed `.part` model downloads before requesting more bytes. Verify expected size and pinned SHA-256, then publish valid files; discard invalid files safely. Test interruption after the final byte and HTTP 416 recovery.
- [ ] Correct internet/privacy claims in `README.md` and `README.zh-CN.md`. Match the in-app disclosure: local inference, internet permission for model downloads, and opt-in Tavily queries that may contain chat details. Preserve accurate no-advertising/tracking claims.
- [ ] Audit remaining coverage: exhaustive UI/widgets, every action/parser branch, and migration data correctness. Include widget lifecycle, action validation and parser edge cases, and data-preserving migrations with defaults, indexes and Room validation. Report findings without automatically applying fixes.

## Latest release

- APK: `/Users/susuresh/genie/grace-launcher/GraceLauncher/release/GraceLauncher-1.2.6-20261103-091411-release.apk` (59,200,361 bytes); checksum: `/Users/susuresh/genie/grace-launcher/GraceLauncher/release/GraceLauncher-1.2.6-20261103-091411-release.apk.sha256`.
- Source: `dff4da26cedd0720ea3d4de393a591eaafd19f21` plus retained approved uncommitted changes, including Magic popup hint removal; provenance/report: `/private/var/folders/d4/g73rwlh56w3b712vq80zl2y40000gn/T/opencode/grace-release-popup-removal-20261103-ma97ye0m/report.md`.
- Version `1.2.6` / `14`; SHA-256 `72027c84625090614a68fd7d5047155ab81e936578c4f350946ceeff5fc4092e`.
- Verified: release build/R8, required release certificate/signature, package/version/SDKs, non-debuggable manifest, ZIP 16KB alignment, all 6 ELF libraries PT_LOAD 16KB alignment, copied digest; popup hint absent and Advanced help present in final APK resources.
- Signed-release assistant/R8 runtime smoke REQUIRED but PENDING a separately authorized compatible target; no devices accessed or installations performed. The four pending items above remain unresolved.
