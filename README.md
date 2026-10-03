# 🛡️ UpdateBlocker

An open-source **LSPosed (Xposed)** module for Android that reliably prevents the **Google Play Store** from automatically updating (or notifying updates for) specific selected applications.

---

## ✨ Features

- **🎯 Selective App Blocking:** Pick individual apps to freeze from receiving updates, while allowing other apps to update normally.
- **🔄 Ghost Version Spoofing:** Intercepts Google Play Store's package queries (`getPackageInfo` & `getInstalledPackages`) and dynamically reports that the installed app is already on a future/maximum version (`versionCode 999999999`). 
  - Google Play servers conclude the local app is up-to-date.
  - No background downloads, no auto-update jobs, and no notifications.
  - The Play Store page displays **Open** instead of **Update**.
- **🚫 PackageInstaller Session Blocker:** Acts as a secondary safeguard by intercepting `PackageInstaller.createSession()`. Even if an installation is forcefully triggered, it is instantly rejected before any APK files can be staged.
- **💳 License & In-App Purchase Friendly:** Because the app remains visible as installed with a valid signature, Google Play license verification and Google Play In-App Billing (IAP) continue to work seamlessly.
- **🔒 Play Integrity & SafetyNet Safe:** Does not modify or tamper with Play Services databases or system mount namespaces, avoiding root detection or integrity tripping.
- **🎨 Material 3 UI:** Clean, intuitive interface with instant search, category filtering (All, Blocked, System), and an option to manually enter custom package names (for sideloaded or uninstalled apps).
- **⚡ Root-Assisted Quick Restart:** Includes a one-tap action to force-stop Google Play Store so changes apply immediately.

---

## 📱 Requirements

- Android 8.0 (Oreo) – Android 15+
- Root solution: [Magisk](https://github.com/topjohnwu/Magisk), [KernelSU](https://github.com/tiann/KernelSU), or [APatch](https://github.com/bmax121/APatch)
- LSPosed Framework: [LSPosed](https://github.com/LSPosed/LSPosed) (or Zygisk Next + LSPosed)

---

## 🚀 Installation & Setup

1. Download the latest **`UpdateBlocker-vX.X.X.apk`** from [GitHub Releases](https://github.com/<YOUR_USER>/UpdateBlocker/releases).
2. Install the APK on your device.
3. Open **LSPosed Manager**:
   - Go to the **Modules** tab.
   - Enable **UpdateBlocker**.
   - Ensure the recommended scope is checked:
     - **Google Play Store** (`com.android.vending`) *(Required)*
     - **System Framework** (`android`) *(Optional secondary guard)*
4. Force stop the Google Play Store (tap the refresh icon in UpdateBlocker or use App Info).
5. Open **UpdateBlocker**, toggle the apps you want to protect, and you're done!

---

## 🏗️ Architecture & How It Works

```
┌─────────────────────────────────────────────────────────────┐
│                       UpdateBlocker                         │
│   (Material 3 UI: select apps to block & store in prefs)    │
└──────────────────────────────┬──────────────────────────────┘
                               │
            ContentProvider / XSharedPreferences
                               │
            ┌──────────────────┴──────────────────┐
            ▼                                     ▼
 ┌───────────────────────┐             ┌─────────────────────┐
 │  com.android.vending  │             │   System Framework  │
 │  (Google Play Store)  │             │      (android)      │
 ├───────────────────────┤             ├─────────────────────┤
 │ • ApplicationPM hook: │             │ • PackageInstaller  │
 │   Spoofs versionCode  │             │   Session hook:     │
 │   to 999999999        │             │   Blocks install if │
 │ • PackageInstaller    │             │   caller is Play    │
 │   createSession hook: │             │   Store and package │
 │   Rejects sessions    │             │   is blacklisted.   │
 └───────────────────────┘             └─────────────────────┘
```

---

## 🤖 Automated GitHub Actions Pipeline (CI/CD)

This repository includes a fully configured automated GitHub Actions workflow (`.github/workflows/build-and-release.yml`):

1. **Auto Compile on Push:**
   - Every push to `main` or `master` compiles the release APK and attaches it as a downloadable GitHub Actions artifact.
2. **Auto Release on Tag:**
   - When you push a git tag like `v1.0.0`, GitHub Actions will:
     - Compile the project using JDK 17.
     - Automatically zipalign and sign the APK (using your custom keystore secret or an auto-generated CI key).
     - Publish a official **GitHub Release** with auto-generated changelog and the signed APK attached.
3. **Manual Trigger (`workflow_dispatch`):**
   - You can also trigger a release directly from the GitHub Actions web UI under **Run workflow**.

### (Optional) Configuring Custom Keystore Secrets
If you wish to sign releases with your personal release keystore instead of the CI key, add these secrets to your GitHub repository (**Settings > Secrets and variables > Actions**):
- `KEYSTORE_BASE64`: Base64 encoded `.jks` file (`base64 -w 0 your_keystore.jks`)
- `KEYSTORE_PASSWORD`: Keystore password
- `KEY_ALIAS`: Key alias name
- `KEY_PASSWORD`: Key password

---

## 🛠️ Local Development & Build

To build the APK locally using Gradle:

```bash
# Clone the repository
git clone https://github.com/<YOUR_USER>/UpdateBlocker.git
cd UpdateBlocker

# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```

The compiled APK will be located at:
`app/build/outputs/apk/release/app-release-unsigned.apk`

---

## 📄 License

This project is licensed under the [Apache License 2.0](LICENSE).
