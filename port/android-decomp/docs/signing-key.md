# The builds' signing key (already set up)

Android only installs an APK **over** an existing one when both carry the same signature. Without a fixed key every
GitHub build got a fresh throwaway key, so a new test build had to be installed by uninstalling the old one first.

**This is already done for this repository** (16.11.2026): a permanent key exists, its four values are in the
repository's Actions secrets, and every build signs with it. Nothing needs to be created again.

## The one unavoidable uninstall

The app on the phone right now was signed with a throwaway key from an earlier build. Its signature can never be
matched, so installing the first build that uses the new key still needs one uninstall. **Every build after that
installs normally, over the previous one.**

## Backing the key up

The key lives in the repository secrets only. A copy with the key, both passwords and its fingerprint is in
`SIGNING-KEY-BACKUP.txt` in the workspace root (`port/android-decomp/docs/SIGNING-KEY-BACKUP.txt` when copied into the
repository); it is listed in `.gitignore` and must never be committed or shared.

Losing it means no future build can update an installed app, and every user would have to uninstall once again.

## How it is wired

`app/build.gradle` defines `signingConfigs.re4` when `RE4_KEYSTORE_FILE` is set and uses it for debug builds. The
workflow's "Signing key" step decodes `RE4_KEYSTORE_BASE64` into a temporary file and exports the four variables.
Without the secrets the build still works but falls back to the throwaway debug key, and the run prints a warning.

| Secret | Value |
| --- | --- |
| `RE4_KEYSTORE_BASE64` | the keystore, base64 encoded on one line |
| `RE4_KEYSTORE_PASSWORD` | the store password |
| `RE4_KEY_ALIAS` | `re4` |
| `RE4_KEY_PASSWORD` | the key password |

