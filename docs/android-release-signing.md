# MemePulse Android release signing

This guide applies to the root **MemePulse Terminal Expo app**, not the separate `native-android` module. The current Expo configuration identifies the app as `com.app.meme.pulse.terminal`, version `1.0.3`, with a default Android version code of `1` and supported ABIs `armeabi-v7a` and `arm64-v8a`.

## Current access limitation

The GitHub integration returned `HTTP 403: Resource not accessible by integration` for the repository Actions-secrets API. The integration therefore could not inspect whether signing secrets already exist or configure them. No signing key was generated or changed. **Do not rotate a valid production key just to follow this guide.** If the existing production key is already stored in GitHub, reuse that exact key and credentials under the names below.

## Required repository Actions secrets

In `armoula40-max/memepulse-terminal`, open **Settings → Secrets and variables → Actions → Repository secrets → New repository secret** and add:

| Secret name                 | Value                                                                     |
| --------------------------- | ------------------------------------------------------------------------- |
| `MEMEPULSE_KEYSTORE_BASE64` | One-line base64 encoding of the existing production Android keystore file |
| `MEMEPULSE_STORE_PASSWORD`  | Keystore password                                                         |
| `MEMEPULSE_KEY_ALIAS`       | Alias for the release key entry                                           |
| `MEMEPULSE_KEY_PASSWORD`    | Password for that key entry                                               |

Enter each value directly into GitHub's secret form. Do not paste credentials into a chat, issue, commit, workflow output, or artifact. The workflow deliberately reports only missing secret names, never their values.

## If the existing production key is unavailable

Do not create or rotate a signing key as part of this task or CI setup. First confirm the established release key and signing owner through the app team's existing secure process. Reusing the same key is required for seamless updates to already distributed MemePulse APKs. Until the owner configures an approved production key and credentials in the repository secrets above, the workflow intentionally fails closed and will not upload or publish an APK.

For an existing PKCS12 or JKS keystore, the owner should use its actual store password, key alias, and key password. The workflow detects JKS versus PKCS12 on the runner and passes that type to Gradle; it does not convert or rotate the key. Local base64 encodings are covered by the repository's `.gitignore`; enter approved values directly into GitHub's secret form, never chat, source control, logs, or artifacts.

## Build and release flow

- **Actions → Build MemePulse Android APK → Run workflow** with the feature branch selected builds, verifies, and uploads the signed APK plus checksum/manifest artifact. GitHub requires the workflow file to exist on the default branch before manual dispatch is available.
- Pushing or manually dispatching the workflow at a matching `v<app-version>` tag builds the same APK and publishes it to GitHub Releases. For the current app config, the matching tag is `v1.0.3`.
- The workflow fails before Gradle release packaging if a required secret is missing or the keystore/alias cannot be opened. It also requires the APK signature certificate to match the configured key before upload or release.

The signing keystore is decoded only beneath the Actions runner's temporary directory and removed in an `always()` cleanup step. The build uploads only the verified application APK, its SHA-256 file, and non-secret build metadata.
