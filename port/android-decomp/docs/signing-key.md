# The builds' signing key (one-time setup)

Android only installs an APK **over** an existing one when both carry the same signature. Without a fixed key every
GitHub build got a fresh throwaway key, so a new test build had to be installed by uninstalling the old one first.
This is set up once; afterwards each build installs straight over the previous one.

## What to do (once)

You need a computer with Java for this one step — a phone cannot run `keytool`. Any Windows/Linux machine will do;
the key is then stored in the repository's secrets and never needs the computer again.

1. Create the key. Pick your own store password and key password (they may be the same) and keep them somewhere safe:

       keytool -genkeypair -v -keystore re4.jks -alias re4 \
         -keyalg RSA -keysize 2048 -validity 10000 \
         -storepass YOUR_STORE_PASSWORD -keypass YOUR_KEY_PASSWORD \
         -dname "CN=CubeEvil4 fork, O=personal, C=US"

2. Turn it into one line of text for GitHub (Linux/macOS; on Windows use `certutil -encode` or WSL):

       base64 -w0 re4.jks > re4.jks.b64

3. Add four repository secrets at
   `github.com/Tim-ui-boop/CE4-fork/settings/secrets/actions` → **New repository secret**:

   | Secret name | Value |
   | --- | --- |
   | `RE4_KEYSTORE_BASE64` | the whole contents of `re4.jks.b64` (one long line) |
   | `RE4_KEYSTORE_PASSWORD` | the store password from step 1 |
   | `RE4_KEY_ALIAS` | `re4` |
   | `RE4_KEY_PASSWORD` | the key password from step 1 |

4. Push anything, or run the workflow by hand from the Actions tab. The run says
   `signing with the permanent key (re4)` in its "Signing key" step.

Until those secrets exist the workflow prints a warning and keeps the throwaway key — nothing breaks, the APK just
will not install over an older one.

## Important

- **Keep `re4.jks` and the passwords.** Without them no future build can update an installed app; you would have to
  uninstall again. A backup in two places is worth the minute.
- **Do not commit `re4.jks`** to the repository. It belongs in the secrets only. The `.gitignore` does not know it, so
  keep it outside the checkout.
- **The first install after setting this up still needs one uninstall**, because the app currently on the phone was
  signed with a throwaway key. From then on updates install normally.
- If the app on the phone came from the official CubeEvil4 release rather than a build of this fork, its signature can
  never match; those builds will always need their own installation path.
