# UpdateBlocker

An LSPosed module to prevent Google Play Store from auto-updating specific apps while keeping manual APK updates, license verification, and in-app purchases working.

## Features

- **Selective Blocking**: Freeze updates for chosen apps without affecting others.
- **Ghost Versioning**: Spoofs app versions to Play Store so updates are never queued or downloaded.
- **Install Guard**: Blocks Play Store installation sessions for blacklisted apps.
- **Manual Installs Allowed**: Sideloading and manual APK installs continue to work normally.
- **Licenses Intact**: In-app purchases and Play Store license checks remain fully functional.

## Requirements

- Android 8.0+
- Root (Magisk / KernelSU / APatch) + LSPosed

## Usage

1. Install the APK and enable **UpdateBlocker** in LSPosed.
2. Select **Google Play Store** (`com.android.vending`) in the module scope.
3. Force stop Google Play Store.
4. Open UpdateBlocker and toggle the apps you want to block.

## License

[Apache 2.0](LICENSE)
