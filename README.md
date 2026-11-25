# AutoPass X

Android password manager for POCO X7 Pro / HyperOS 3 / Android 16.

## Design
- Android Autofill Service is the supported system integration for suggesting saved accounts.
- Local credential records are encrypted with AES-GCM using an Android Keystore key.
- A libxposed API 102 bridge is provided as an opt-in extension point for app-specific success detection; no global keystroke or password-field harvesting is implemented.
- Android's public Autofill save flow may show a system confirmation. Universal silent detection of successful login is not available to third-party apps.

## Build
GitHub Actions builds the debug APK on push. Download it from the Actions run artifacts.

## Install
1. Install the APK.
2. Open AutoPass X and configure the vault.
3. Android Settings → Passwords, passkeys & accounts → Autofill service → AutoPass X.
4. Test using a non-critical account first.

This is an early development build, not yet audited for production use.