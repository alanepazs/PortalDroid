# Third-Party Notices

PortalDroid uses the following open-source libraries, each with its own license:

## Windows Application

### Production Dependencies

- **@nut-tree-fork/nut-js** (4.2.0)
  - License: MIT
  - Purpose: Cursor positioning and mouse input automation
  - Repository: https://github.com/nut-tree/nut.js

- **qrcode** (1.5.4)
  - License: MIT
  - Purpose: QR code generation for device pairing
  - Repository: https://github.com/davidshimjs/qrcodejs

- **uiohook-napi** (1.5.4)
  - License: MIT
  - Purpose: Global mouse and keyboard event listening
  - Repository: https://github.com/uiohook/uiohook-napi

### Development Dependencies

- **electron** (31.0.0)
  - License: MIT
  - Purpose: Desktop application framework
  - Repository: https://github.com/electron/electron

- **electron-builder** (26.15.3)
  - License: MIT
  - Purpose: Building and packaging Electron applications
  - Repository: https://github.com/electron-userland/electron-builder

## Android Application

### Gradle Dependencies

- **androidx.core:core-ktx** (1.13.1)
  - License: Apache License 2.0
  - Purpose: Android extension functions for Kotlin
  - Repository: https://source.android.com/

- **androidx.appcompat:appcompat** (1.7.0)
  - License: Apache License 2.0
  - Purpose: Backward compatibility for Android UI components
  - Repository: https://source.android.com/

- **com.journeyapps:zxing-android-embedded** (4.3.0)
  - License: Apache License 2.0
  - Purpose: QR code scanning for device pairing
  - Repository: https://github.com/journeyapps/zxing-android-embedded
  - Includes: ZXing Core (Apache License 2.0)

## License Compatibility

All dependencies are compatible with PortalDroid's MIT License. There are no GPL, AGPL, or other restrictive licenses in use.

For detailed license information, see individual package repositories or LICENSE files within node_modules and gradle dependencies.

---

*Last updated: 2026-09-09*
