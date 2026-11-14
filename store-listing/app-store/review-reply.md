1. SCREEN RECORDING
Attached: a recording from a physical iPhone on the latest iOS, starting from launching the app. It shows the main features, the Recipe tab, a sandbox purchase of a recipe pack, the optional Sign in with Apple and the account deletion in Recipe settings. BestBefore has no user-generated content shared with other users.

2. PURPOSE AND AUDIENCE
BestBefore helps households waste less food. People forget what is in the fridge, freezer and cupboards until it has gone off. The app keeps a list of the food at home with each item's expiry date and reminds the user a few days before something expires, so it gets eaten in time. The Recipe tab suggests what to cook with the items closest to their date. Items that are used up go onto a shopping list.
Audience: anyone who buys groceries, typically adults managing a household. The app is free, with no ads or tracking, and needs no account. The pantry stays on the device. Recipe packs are optional consumable in-app purchases (see 3 and 4).

3. SETUP AND MAIN FEATURES
No login, demo account or sample files are needed; the app is ready on first launch.
- Add food: tap the scan button and scan any food barcode. The product name and picture are filled in from Open Food Facts. Then point the camera at the printed date to read the expiry date, or pick a date. You can also tap "+" to add an item by hand.
- Home: everything at home, sorted by expiry, with "Expired / Expiring soon / Fresh" counts.
- Recipe: the items closest to their date are ticked; servings, time, diet and other settings can be changed. A recipe can be written three ways:
  - "Share prompt" / "Copy prompt" (free): a text prompt for any app the user chooses, through the standard share sheet or the clipboard.
  - "Generate on this phone" (free): Apple's on-device Foundation Models, on devices with Apple Intelligence and iOS 26 or later. Nothing is sent.
  - Recipe packs (paid): consumable in-app purchases of 10, 50 or 100 recipes. One recipe is one dish written by our server; "Menu for 3 days" takes several recipes (currently 4 with two courses a day, 5 with three; the number comes from our server and the app shows it before generating). Sandbox purchases are accepted for the review, within a daily limit shared by all sandbox testers. To test: add an item, open Recipe, tap "Buy recipes", choose "On this phone, no account" (or sign in with Apple), buy the 10-recipe pack with a sandbox account, then tap "Generate recipe". A recipe is taken from the balance only when it arrives. The Buy sheet says, before the purchase, that if the buyer asks Apple for a refund we tell Apple how many of the recipes were used.
- Account (optional, only for recipe packs): Sign in with Apple or Google keeps the recipes in an account so they can be used on another device. We use the account ID and email address, not the name. Recipe settings > Account has Sign out and Delete account, which deletes the account on our server and revokes the Apple sign-in. (Our Android app offers Google sign-in only; an account made with Apple here is used on Android after adding Google to it in the iPhone app.)
- Shopping List: items marked "essential" move here when used up. It can be shared as text or a QR code.
- Settings → Notifications: reminder days and time.
- Settings → Shopping reminders (optional): turn on "Remind me near a shop", name a shop and tap the location button to save it. With "Always" location access, the app shows the shopping list when you arrive at that shop.
- Widgets: expiring items, shopping list and overview widgets for the Home Screen.

4. EXTERNAL SERVICES
- Open Food Facts (https://world.openfoodfacts.org), a free, open product database. When a barcode is scanned, the app requests that product's name, picture and nutrition data. Only the barcode number is sent: no account, key, user data or device identifier.
- Firebase Crashlytics (Google), in release builds only. If the app crashes, it sends a crash report: the stack trace, device model, OS and app version, free memory and disk space, and a random Firebase installation ID. No pantry data, name or account. It is not linked to the user and not used for tracking.
- Recipe packs, only once the user starts a purchase. Nothing below is contacted by someone who never does:
  - StoreKit (Apple) for the purchase itself.
  - Firebase Authentication (Google): an anonymous user ID, or Sign in with Apple / Google if the user chooses an account. It holds the recipe balance. Sign in with Apple asks for the email only; the name and photo Google supplies are cleared from the user record by our server and not used, although Firebase keeps a copy in the Google sign-in record until the account is deleted (declared as Name in App Privacy).
  - Firebase App Check (Google) with App Attest, on every call to our server, to limit calls to genuine copies of the app. Our server also limits calls per minute by IP address, in memory only; Google Cloud's request logs record the address and keep it for 30 days.
  - Firebase Cloud Functions and Cloud Firestore (Google, EU region europe-west1), our server: it verifies each purchase with the App Store Server API, keeps the balance and purchase records, and writes recipes. Each purchase carries an appAccountToken derived from the user ID, so it is credited to the account that bought it. A recipe or menu is kept on the server for 7 days, so the app can collect it after a lost connection, then deleted.
  - App Store Server Notifications: refunds are taken off the balance. If a buyer who saw the line on the Buy sheet asks Apple for a refund, our server answers Apple's consumption request with the share of the purchase already used. Buying is turned off for a buyer whose purchases are repeatedly refunded after the recipes were used; for that the server keeps a record of those refunds under Apple's app transaction ID.
  - Google Gemini on Vertex AI, called by our server (not by the app) to write a recipe. It receives the names, expiry dates and quantities of the ticked items, the recipe settings and the user's allergen list. No name, email or device identifier is sent to it. Requests and answers are checked automatically (for example for links, contact details and the user's allergens) before anything is shown.
- Everything else runs on the device with Apple frameworks: AVFoundation (camera, barcodes), Vision (reading printed dates and receipts, recognising fruit and vegetables in photos), Foundation Models (free on-device recipes), Core Location region monitoring (optional shop reminders), UserNotifications, WidgetKit and SwiftData.
- There are no analytics or ads.

5. REGIONAL DIFFERENCES
The app works the same in all regions. It is available in English, Russian and Norwegian. Open Food Facts covers some countries' products better than others; when a barcode isn't found, the user types the name and everything else works the same. "Generate on this phone" appears only where Apple Intelligence is available on the device.

6. REGULATED INDUSTRY / THIRD-PARTY MATERIAL
Not a regulated industry; the app gives no medical or dietary advice. Recipes are suggestions; the allergen list is passed on as an instruction to leave those ingredients out, and the app does not guarantee that a recipe is free of them. The only third-party material is product data and pictures from Open Food Facts, which are openly licensed for reuse: database under the Open Database License (ODbL), pictures under Creative Commons Attribution-ShareAlike. See https://world.openfoodfacts.org/terms-of-use
