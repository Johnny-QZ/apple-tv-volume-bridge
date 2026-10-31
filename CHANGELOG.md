# Changelog

## 0.3.0-alpha.3 — latest prototype merged for source release

- Merge the private prototype through `0.2.11` while preserving per-install mutual HMAC authentication.
- Add paired Companion headphone volume steps of `6.25/3` and `6.25/6` percentage points.
- Add experimental AirPlay/MRP output observation with explicit headphone-name configuration; unknown/stale/mixed routes do not qualify as headphones.
- Preserve one volume path per physical press and avoid retry/fallback writes after uncertain fine-volume results.
- Restore Xiaomi HDMI hardware audio after the stock player leaves or headphones disconnect, while respecting TV mute and zero volume.
- Keep all three power-link switches off by default; preserve hold, wake-view, cleanup and input-release behavior.
- Fetch SHA-256-pinned Bouncy Castle 1.80 and dd-plist 1.28 inputs, reproduce the Android class-only repack, and shrink the helper with R8. Include third-party license notices in the APK/helper.
- Add optional pyatv 0.18.0 user pairing and explicit-TV private configuration provisioning; do not include private identities or binary dependencies in source.
- Document that direction scrolling can also stop with the original Apple TV remote in the compared scenario.

The latest private `0.2.11` prototype was hardware-tested on one Xiaomi TV. This merged authenticated public alpha has only offline build/test validation; the new provisioning flow and complete public build still need device regression. No claim of universal TV compatibility or unattended reboot recovery.

## 0.3.0-alpha.2 — local source preparation

- Merge the tested 0.2.1–0.2.4 prototype fixes while retaining mutual HMAC authentication.
- Preserve sustained confirm-key holds and release behavior.
- Add three independent screen-off linkage switches (public build defaults to off).
- Use the verified Xiaomi standby notification path, with temporary-setting restoration.
- Restore full screen only when the pre-sleep view was identified as the bridge; preserve the home preview otherwise.
- Hide transient startup/reconnect banners; retain persistent failure messages.
- Add state-machine tests and update compatibility, setup and rollback documentation.

The merged authenticated build is compiled and tested offline, but has not been deployed to the TV. Fine headphone volume steps remain unimplemented.


## 0.3.0-alpha.1 — prepared for source release

- Organize the v0.2 prototype into app, shell helper and shared Java sources.
- Build the helper and APK from source using Python 3, JDK 17 and Android SDK tools.
- Replace the shared static local socket password with a per-install random key and
  mutual HMAC-SHA256 challenge-response authentication; bound protocol command sizes.
- Put HDMI input, CEC physical route and remote matching in one device profile.
- Add explicit-device install/start/stop/uninstall commands, MIT license and compatibility notes.
- Refuse the unsupported 64-bit input-event format before grabbing the remote.

This version has not been installed or tested on a TV. It does not solve ADB being
disabled by firmware after reboot. See `docs/compatibility.md` for the v0.2 observations.

## 0.2 — local prototype

- Forward Xiaomi remote volume keys to Apple TV through the system TV input service and CEC.
- Recover the forwarding UI after switching away from and back to the configured HDMI source.
- Start the shell helper through locally authorized ADB, without a computer during normal use.
- Offer a manual direct-TV-speaker mode in the menu.
