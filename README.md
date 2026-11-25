# AutoPass X

**Offline-first password manager for Android.** Target device: POCO X7 Pro / Android 16 / KernelSU / LSPosed 2.x (LibXposed API 102).

AutoPass X is intended to be a local-only vault. There is no AutoPass account, cloud vault, telemetry backend, or background sync service. Data leaves the device only when the user explicitly copies it or invokes an export/share action.

## Product principles

- Local-only by default: no sign-up, cloud account, remote sync, or analytics.
- Encrypt vault data at rest with authenticated encryption and Android Keystore-backed key protection.
- Never silently collect credentials. Saving and filling must use Android Autofill/Credential Manager contracts and clear user consent.
- Keep password records separate from passkey private-key material. A passkey provider must use Android's credential-provider APIs and platform key protection; never export private keys as plaintext.
- Make import/export explicit, user-initiated, and visibly warn before creating plaintext exports.

## Planned Bitwarden-style feature set

### Vault item types
- Login entries (multiple usernames/passwords per app or website)
- Secure notes
- Payment cards and identities
- Custom fields, folders, favorites, tags, search and sorting
- TOTP authenticator entries

### Credentials and sign-in
- Android Autofill for apps and websites
- Passkey creation and sign-in through Android Credential Manager / credential-provider APIs where supported
- Password and passphrase generator
- Password reveal/copy controls and clipboard auto-clear
- Explicit save/update flow; no hidden password scraping

### Offline portability
- Import supported Bitwarden JSON/CSV and common password-manager CSV formats
- Export encrypted vault archive for backup and transfer
- Optional plaintext CSV export only after a clear warning and explicit confirmation
- Local backup/restore through Android document picker (SAF)
- No automatic multi-device sync. Move an encrypted backup manually between devices.

## Passkey limitations

Passkeys are not ordinary password strings. Android and the credential provider manage their cryptographic key material. AutoPass X must integrate through the platform credential-provider APIs and should not claim support until create/get flows are implemented and tested. Generic CSV export must never include passkey private keys. Some relying-party sign-in operations still require that website or app's online challenge even though the vault itself has no cloud service.

## Current implementation status

This repository is an early development build, not a production-ready Bitwarden replacement and not security-audited.

Implemented in the current branch:
- Jetpack Compose RTL interface and navigation shell
- Initial local encrypted-vault code using AES-GCM / Android Keystore
- Initial Android Autofill service
- LibXposed API 102 module entry and metadata (currently load-detection only)

Not yet complete / must not be advertised as implemented:
- Full Bitwarden-compatible vault item model and robust database migrations
- Biometric/PIN master unlock and auto-lock
- Passkey credential-provider create/get flows
- Import parsers, encrypted export format, restore and integrity validation
- TOTP, secure notes, cards, identities, attachments and custom fields
- Clipboard timeout, password history, breach checks (offline breach checks require a local dataset)
- Automated tests, threat model, independent security audit and real-device acceptance testing

**Do not store primary/high-value credentials in development builds.** Test with disposable accounts until security review and device testing are complete.

## Build

Pushes to `main` trigger the Android debug APK workflow. Download the APK only from a successful workflow artifact; a green workflow alone does not imply the app has passed security or device testing.
