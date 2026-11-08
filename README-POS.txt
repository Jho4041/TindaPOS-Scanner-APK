TindaPOS + scanners
===================
1) Built-in camera scanner (same device): Sell tab > "Scan with camera" (native CameraX + ML Kit plugin).
2) Scanner phone -> main POS (this is the separate "TindaPOS Scanner" app in android-scanner/):
   - Android tablet / phone as main POS: TindaPOS now listens on Wi-Fi (port 8765).
       Settings > "Wireless scanner" > Start receiving scans. It shows the address and a 6-digit pairing code.
       In the Scanner app choose Wi-Fi, enter that address (http://IP:8765/scan) and the code, tap Start scanning.
       Keep TindaPOS open on the tablet while scanning. Both devices on the same trusted Wi-Fi.
   - Windows PC as main POS: open Settings > Wireless scanner > Start receiving scans in the TindaPOS Windows app,
       then enter the PC address and pairing code in the Scanner app. The Windows app supports barcode and image scans.
       The separate windows-helper\START_TINDAPOS_HELPER.bat supports barcode scans only.
   - Bluetooth between two Android devices still works too (Settings > "Use this phone as a scanner").
Build (GitHub > Actions):
   "Build TindaPOS APK"          -> artifact TindaPOS-debug-apk   (main POS app)
   "Build TindaPOS Scanner APK"  -> artifact TindaPOS-Scanner-debug-apk (scanner phone app)
Local: node setup.js, then open android/ in Android Studio.
New native code: android-java/com/tindapos/app/NativeScannerPlugin.java and ScannerReceiverPlugin.java
(registered in MainActivity.java). Nothing here has been compiled or tested on a device.

Windows EXE (PC version of TindaPOS)
   electron-pc\build-exe.bat  (needs Node.js installed) -> electron-pc\dist\ has "TindaPOS Setup x.x.x.exe" (installer) and a portable .exe.
   Or GitHub > Actions > "Build TindaPOS EXE" > artifact TindaPOS-Windows-EXE.
   Data is stored per PC app; move products with Export / Import.

PC as main POS + Android as customer monitor
   1. PC: install/run TindaPOS.ph (Windows app). Settings > Customer monitor > turn it on.
      It shows the address for the Android device, like http://192.168.1.10:8780/customer
      First run: allow TindaPOS.ph through Windows Firewall (Private networks).
   2. Android tablet/phone on the same Wi-Fi:
      - In the TindaPOS.ph Android app: Settings > "Use this device as a customer monitor", type the PC IP, Open.
        (tick "Open automatically" for a dedicated monitor)
      - or open the address in Chrome and tap the screen once for full screen.
   Tip: set the Android screen timeout to "never" / keep awake while charging.

Scanner phone -> Windows TindaPOS.ph (by IP address, no Python helper needed)
   PC: Settings > Wireless scanner > Start receiving scans. It shows http://PC-IP:8765/scan and a 6-digit pairing code.
   Android scanner app: choose Wi-Fi, type that address (or just the PC IP) and the code, tap Start scanning.
   First time allow TindaPOS.ph through Windows Firewall (Private networks). Same Wi-Fi on both devices.

Online license check (revoke / expire): see ONLINE-LICENSE-CHECK.md. Set it with:  node set-license-url.js https://your-address
