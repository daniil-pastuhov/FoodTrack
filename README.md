# FoodTracker 🥫📅

A Kotlin Multiplatform (KMP) grocery and expiration date tracking app for **Android (Jetpack Compose)** and **iOS (Native SwiftUI)**.

## Features

- 📷 **Barcode Scanning**: Scan 1D and 2D barcodes (EAN-13, UPC, Code 128, DataBar, QR).
- 🏷️ **Automatic Product Lookup**: Queries the open [Open Food Facts API](https://world.openfoodfacts.org/) to fetch product names, brands, images, and categories.
- ⚡ **GS1 Barcode Expiration Extraction**: Decodes expiration dates directly from GS1 barcodes containing Application Identifiers `(17)` or `(15)`.
- 🤖 **On-Device Machine Learning Date OCR**:
  - Point your phone's camera at the printed expiration date stamp on any packaging (`EXP 24.11.2026`, `BEST BEFORE 15 NOV 2026`, `BB 05/12/26`).
  - Google ML Kit on Android and Apple Vision on iOS extract and parse the date in real time with 1-tap confirmation.
- 📸 **Product Photo Fallback**: Snap photos and manually name bakery, market produce, or custom items that lack barcodes.
- ⏱️ **Pantry Management Dashboard**:
  - Color-coded shelf-life urgency: **Expired** (Red), **Expiring Soon** (Amber, within 3 days), and **Fresh** (Green).
  - Filter by storage location (**Fridge**, **Pantry**, **Freezer**).
  - Search by name or brand.
  - Quick swipe actions to mark items as *Consumed* or *Delete*.

## Project Structure

- `shared/`: Kotlin Multiplatform shared domain, Open Food Facts Ktor client, GS1 parser, Date OCR parsing heuristics, and Pantry repositories.
- `androidApp/`: Native Android application built with Jetpack Compose (Material 3), CameraX, and Google ML Kit.
- `iosApp/`: Native iOS application built with SwiftUI, AVFoundation, and Apple Vision.

## Building and Running

### Run Unit Tests
```bash
./gradlew :shared:test
```

### Build Android App
```bash
./gradlew :androidApp:assembleDebug
```

### Build iOS App
```bash
xcodebuild -project iosApp/FoodTracker.xcodeproj -scheme FoodTracker -destination 'generic/platform=iOS Simulator' build
```
