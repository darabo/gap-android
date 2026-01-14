# APK Size Optimization Report

## Current State Analysis
- **Native Libraries (`.so`)**: The biggest contributor to the file size is the Rust-based Tor implementation (`libarti_android.so`).
  - `arm64-v8a`: ~5.3 MB
  - `x86_64`: ~6.1 MB
  - **Total in Universal APK**: ~11.4 MB
- **Resources**: There are some unused high-resolution images in `res/`.
  - `ic_launcher-web.png`: 143 KB
  - `playstore-icon.png`: 183 KB
- **Build Configuration**:
  - Minification and Resource Shrinking are already enabled (`true`).
  - The build is configured to generate a "Universal" APK (`isUniversalApk = true`) which bundles all architectures.

## Recommendations

1.  **Distribute Split APKs (Highest Impact)**
    - **Recommendation**: Instead of sharing the "Universal" APK, share the **`arm64-v8a`** specific APK.
    - **Why**: Stripping `x86_64` saves ~6.1 MB instantly.
    - **How**: The project is already configured for splits. Use `app-arm64-v8a-release.apk`.

2.  **Modularize Tor Support (Medium Impact)**
    - **Recommendation**: If Tor functionality is not critical, move `libarti_android.so` to a separate Product Flavor or Dynamic Feature Module.
    - **Savings**: ~5.3 MB (arm64).

3.  **Enable R8 Full Mode (Low Impact)**
    - **Recommendation**: Add `android.enableR8.fullMode=true` to `gradle.properties`.

4.  **Remove Unused Resources (Low Impact)**
    - **Recommendation**: Delete `app/src/main/res/ic_launcher-web.png` and `app/src/main/res/playstore-icon.png`.

## Estimated APK File Sizes

| Configuration | Estimated Size |
| :--- | :--- |
| **Current Universal APK** | **~16.4 MB** |
| **Optimized Universal APK** | **~15.6 MB** |
| **Recommended: arm64-v8a APK** | **~9.5 MB** |
| **Max Optimization: arm64 APK (No Tor)** | **~4.2 MB** |
