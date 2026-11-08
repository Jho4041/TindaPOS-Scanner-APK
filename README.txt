TindaPOS.ph Scanner (scanner-only Android app, no license)
==========================================================
Turns an Android phone into a barcode scanner for TindaPOS.ph. No license, no login.
Sends each scan to the main POS (Windows PC app or Android tablet) over Wi-Fi, or by Bluetooth.

Build the APK (no Android Studio needed)
1. Upload this whole folder to a GitHub repo (keep the hidden .github folder).
2. GitHub > Actions > "Build TindaPOS Scanner APK" > Run workflow.
3. Download the artifact TindaPOS-Scanner-debug-apk (app-debug.apk) and install it on the scanner phone.
   Or open the android-scanner folder in Android Studio and build.

Use it
1. On the main POS (PC or tablet): TindaPOS.ph > Settings > Wireless scanner > Start receiving scans.
   It shows the address (IP) and a 6-digit pairing code.
2. On the scanner phone: choose Wi-Fi, type the IP address and the code, tap Start scanning.
3. Both devices on the same Wi-Fi. Allow TindaPOS.ph through Windows Firewall the first time.
Not compiled or tested on a device here.

Build with Command Prompt instead of GitHub
- Needs: JDK 17, Gradle 8.9+ (on PATH) and the Android SDK (installing Android Studio once gives you the SDK;
  in its SDK Manager install "Android 15 (API 35)"). Then double-click build-scanner-apk.bat
- The APK is saved next to the script as TindaPOS-Scanner.apk.

Image scan (items without a barcode)
In Wi-Fi mode, tap "Scan product image": the POS shows matching products with pictures, you tap the right one and it is added to the cart.
"Search by name" teaches the POS the photo for next time. Needs TindaPOS.ph (tablet, phone or Windows app) updated with the same release.
