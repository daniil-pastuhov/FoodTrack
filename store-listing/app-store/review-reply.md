1. SCREEN RECORDING
Attached: a recording from a physical iPhone on the latest iOS, starting from launching the app. BestBefore has no account registration or login, no user-generated content shared with other users, and no paid content or in-app purchases, so there are no such flows to show.

2. PURPOSE AND AUDIENCE
BestBefore helps households waste less food. People forget what is in the fridge, freezer and cupboards until it has gone off. The app keeps a list of the food at home with each item's expiry date and reminds the user a few days before something expires, so it gets eaten in time. Items that are used up go onto a shopping list.
Audience: anyone who buys groceries, typically adults managing a household. The app is free, with no account, ads or tracking. All data stays on the device.

3. SETUP AND MAIN FEATURES
No login, demo account or sample files are needed; the app is ready on first launch.
- Add food: tap the scan button and scan any food barcode. The product name and picture are filled in from Open Food Facts. Then point the camera at the printed date to read the expiry date, or pick a date. You can also tap "+" to add an item by hand.
- Home: everything at home, sorted by expiry, with "Expired / Expiring soon / Fresh" counts.
- Calendar: what expires today, tomorrow and later. Swipe an item to mark it used or tossed.
- Shopping List: items marked "essential" move here when used up. It can be shared as text or a QR code.
- Settings → Notifications: reminder days and time.
- Settings → Shopping reminders (optional): turn on "Remind me near a shop", name a shop and tap the location button to save it. With "Always" location access, the app shows the shopping list when you arrive at that shop.
- Widgets: expiring items, shopping list and overview widgets for the Home Screen.

4. EXTERNAL SERVICES
- Open Food Facts (https://world.openfoodfacts.org), a free, open product database. When a barcode is scanned, the app requests that product's name, picture and nutrition data. Only the barcode number is sent: no account, key, user data or device identifier.
- Everything else runs on the device with Apple frameworks: AVFoundation (camera, barcodes), Vision (reading printed dates and receipts, recognising fruit and vegetables in photos), Core Location region monitoring (optional shop reminders), UserNotifications, WidgetKit and SwiftData.
- There are no authentication services, payment processors, analytics or ads, and no AI services. A "recipe ideas" button builds a text prompt from the expiring items and opens the standard share sheet, so the user can paste it into any app they choose. The app itself calls no AI service.

5. REGIONAL DIFFERENCES
The app works the same in all regions. It is available in English, Russian and Norwegian. Open Food Facts covers some countries' products better than others; when a barcode isn't found, the user types the name and everything else works the same.

6. REGULATED INDUSTRY / THIRD-PARTY MATERIAL
Not a regulated industry; the app gives no medical or dietary advice. The only third-party material is product data and pictures from Open Food Facts, which are openly licensed for reuse: database under the Open Database License (ODbL), pictures under Creative Commons Attribution-ShareAlike. See https://world.openfoodfacts.org/terms-of-use
