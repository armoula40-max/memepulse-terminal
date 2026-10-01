# MemePulse Android release signing

This guide applies to the root **MemePulse Terminal Expo app**, not the separate `native-android` module. The current Expo configuration identifies the app as `com.app.meme.pulse.terminal`, version `1.0.3`, with a default Android version code of `1` and supported ABIs `armeabi-v7a` and `arm64-v8a`.

## Debug-signed test APK releases (no production secrets)

The Android test workflow sets `MEMEPULSE_DEBUG_TEST_BUILD=1` only for Expo prebuild. In that mode, `app.config.ts` omits the secret-backed release-signing plugin, allowing the generated Gradle release variant to use the established Android Debug signer. The workflow does not read any production signing secret. Before uploading or publishing, it verifies the APK package ID and the audited debug certificate fingerprint `fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c`, then writes a SHA-256 checksum and non-secret verification metadata.

This APK is for test distribution only; it is not a production or Play Store release. The workflow runs on pushes to `main` and on manual dispatch. Pushes to `main` publish a prerelease automatically after verification. Manual dispatch defaults to artifact-only; publishing requires the explicit `publish_test_release` input and is limited to `main` or `feat/engine-b-paper-trading`. Published test releases use the established `apk-${GITHUB_RUN_ID}` tag and contain only the APK, its checksum, and verification metadata. Pull requests do not trigger public releases.

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

Do not create or rotate a signing key as part of this task or CI setup. First confirm the established release key and signing owner through the app team's existing secure process. Reusing the same key is required for seamless updates to already distributed MemePulse APKs. A production-signed build through the release-signing plugin intentionally fails closed until approved secrets are configured; the separate debug-test path above bypasses that plugin only when its explicit test-mode environment flag is set.

For an existing PKCS12 or JKS keystore, the owner should use its actual store password, key alias, and key password. The workflow detects JKS versus PKCS12 on the runner and passes that type to Gradle; it does not convert or rotate the key. Local base64 encodings are covered by the repository's `.gitignore`; enter approved values directly into GitHub's secret form, never chat, source control, logs, or artifacts.

## Production signing flow

The secret-backed plugin remains registered for builds that do not set `MEMEPULSE_DEBUG_TEST_BUILD=1`. It requires the four repository secrets above, checks the release APK against the configured production certificate, and fails closed before upload or publication if signing inputs are missing or invalid. The debug-test workflow does not decode, stage, or remove a production keystore because it never consumes one.
