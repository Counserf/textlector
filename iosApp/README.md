# iOS Frameworks

This folder contains native xcframework libraries for the iOS build.

## ⚠️ Important

The binaries are not stored in Git due to their large size (~80MB+).
After cloning the repository, the build will not work until the frameworks are restored.

## How to restore

### Option 1 — from a local build (preferred, if `build-ios/` folder exists)

```bash
cd iosApp/Frameworks
cp -r build-ios/sherpa-onnx.xcframework .
cp -r build-ios/ios-onnxruntime/onnxruntime.xcframework .
```

> ⚠️ The archive from GitHub releases (`sherpa-onnx-v1.12.34-ios.tar.bz2`) does **not** include headers —
> only `libsherpa-onnx.a`. Always prefer Option 1 if `build-ios/` is available.

### Option 2 — Download from GitHub releases (fallback, no headers)

Only use this if `build-ios/` is missing. After downloading you will still need to copy headers from `build-ios/`.

```bash
cd iosApp/Frameworks

curl -L -o sherpa-onnx-ios.tar.bz2 \
"https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.34/sherpa-onnx-v1.12.34-ios.tar.bz2"

tar -xjf sherpa-onnx-ios.tar.bz2
rm sherpa-onnx-ios.tar.bz2

# Then restore headers from build-ios/:
cp -r build-ios/sherpa-onnx.xcframework/ios-arm64/Headers \
      sherpa-onnx.xcframework/ios-arm64/Headers
cp -r build-ios/sherpa-onnx.xcframework/ios-arm64_x86_64-simulator/Headers \
      sherpa-onnx.xcframework/ios-arm64_x86_64-simulator/Headers
```

After restoring, clean DerivedData:

```bash
rm -rf ~/Library/Developer/Xcode/DerivedData
```

## SPM dependencies

The project uses Swift Package Manager dependencies — add them in Xcode via
**File → Add Package Dependencies** if they are missing:

| Package     | URL                                                              | Version | Product             |
|-------------|------------------------------------------------------------------|---------|---------------------|
| onnxruntime | `https://github.com/microsoft/onnxruntime-swift-package-manager` | 1.20.0+ | OnnxRuntimeBindings |

## SherpaOnnx.swift

`iosApp/iosApp/SherpaOnnx.swift` must match the sherpa-onnx library version.
If you encounter compilation errors after restoring the frameworks, re-download it:

```bash
curl -L -o iosApp/iosApp/SherpaOnnx.swift \
"https://github.com/k2-fsa/sherpa-onnx/raw/v1.12.34/swift-api-examples/SherpaOnnx.swift"
```

## Supertonic

`SupertonicBridge.swift` is copied from the `supertonic-kmp` library repo and must be present in the Xcode project.

The KMP framework must be built before Xcode build so that `SupertonicBridgeProtocol` is available:

```bash
./gradlew :composeApp:linkDebugFrameworkIosArm64
```

Voice style JSON files are bundled inside the `SupertonicKMP.framework` and copied into the app bundle via a Run Script Phase in Xcode. The script path points to the `supertonic-kmp` local build output — adjust it if the library is located elsewhere on your machine.

### Important: model loading

`SupertonicBridge.load(storageDir:)` must be called before any generation. It is called automatically in `IosSupertonicEngine.loadVoice()`. The models are downloaded to:

```
Documents/supertonic/
```

And loaded from:

```
Documents/supertonic/onnx/
```

If generation silently fails (`bridge.generate returned nil`), verify that:
1. Models are downloaded via the Settings → Supertonic → Download button
2. `bridge.isLoaded` is `true` after `loadVoice()` is called
3. The `storageDir` path matches where `SupertonicTts` downloaded the models

> ⚠️ iOS reassigns the app's Documents path UUID on every reinstall. If you're debugging file paths, always read the path from logs rather than hardcoding it.