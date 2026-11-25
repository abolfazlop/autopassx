# AutoPass X

AutoPass X is an on-device password vault and Android Autofill provider, with a separate modern LibXposed API 102 module entry.

## Current implementation
- Jetpack Compose dark interface with animated page transitions.
- Encrypted local records using AES-GCM and Android Keystore.
- Multiple credentials per Android package.
- Android Autofill datasets for saved credentials.
- Autofill provider selection is checked from the system setting; the setup intent is not launched again once AutoPass X is selected.
- Modern LibXposed entry metadata under META-INF/xposed targeting API 102.

## Important limitations
- The LSPosed entry currently verifies module/process loading only. It does not hook login flows or capture passwords.
- Android Autofill saving follows the platform SaveInfo / SaveRequest contract. Android or the target app decides whether to present a save prompt; a third-party app cannot universally prove that every arbitrary app login succeeded.
- The current UI is an early functional rebuild, not a security-audited password manager. Biometric app lock, encrypted export/import, account editing, password generator UI, and per-app integration hooks are not complete yet.
- Use a test account first. Avoid storing high-value credentials in development builds.

## Build
Pushes to main trigger the Android debug APK workflow. The workflow artifact is named AutoPassX-debug.

## Device target
Android 8+; developed for POCO X7 Pro / Android 16 / KernelSU / LSPosed with modern LibXposed API 102.
