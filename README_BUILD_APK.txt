FIFO PICK SCANNER - ANDROID APP (APK) BUILD GUIDE
==================================================

WHAT THIS IS
  Updated FIFO Pick app (package com.fifo.pick, name "FIFO Pick Scanner"), rebuilt on the same
  design as the RM Stock app: same look, same native camera scanner. Works fully OFFLINE.
  Android 5.0 (API 21) and newer: phones, tablets, rugged handheld scanners.

WHAT CHANGED vs THE OLD FIFO APK
  1. SCANNING FIXED. The old app had no camera permission and used a web camera API that Android
     WebView does not support. It now uses the same native ZXing scanner as RM Stock
     (QR, Code128, Code39, DataMatrix; Volume-up = torch; reopens after each pick).
  2. TAG MATCHING IS TOLERANT. Lower/upper case, spaces, line-breaks and scanner prefixes
     (e.g. ]Q1) no longer break a match. Old build compared the text exactly.
  3. PHOTO SCAN. "Photo scan" button: take a photo of the tag (or choose one from the gallery);
     it is decoded natively (several sizes / crops / inverted) and works offline.
  4. MATERIAL NOT IN LIST. If a scanned tag is not in the plan, a form opens (description,
     material code, batch, UM, storage loc, bags, qty) and it is saved as NEW ITEM. Also available
     any time with the "Material not in list? Add it as NEW item" button and from the search box.
     NEW items show in the log, report, CSV and in a separate "Not in plan" sheet of the Excel.
  5. Same UI as RM Stock: Home / Scan / Report screens, bottom entry sheet, search box,
     dashboard, Excel export + Share, Backup/Restore.
  6. Plan can be loaded from .xlsx as well as .csv / .tsv / .txt.
  7. Kept from the old app: FIFO check (wrong batch / earlier batch pending / extra qty),
     bags x qty-per-bag entry, Copy for Excel (same 5 columns), CSV, Undo, hardware scanner guns
     (broadcast intents: com.fifo.pick.SCAN, DataWedge, Honeywell, Newland, Urovo, keyboard wedge).
  Scans saved by the old app are picked up automatically (same storage) if the app is updated
  in place (see UPDATING below).

BUILD OPTION A - ANDROID STUDIO (recommended)
  1. Install Android Studio (free): https://developer.android.com/studio
  2. File > Open > choose this folder (FIFO_Pick_Android_Project). Wait for Gradle sync.
  3. Build > Build Bundle(s) / APK(s) > Build APK(s)  ->  app/build/outputs/apk/debug/app-debug.apk
  4. Copy the APK to the phone and tap it. If blocked: allow "Install unknown apps" for the app
     you opened it from.

BUILD OPTION B - GITHUB (no Android Studio)
  1. Create a free GitHub repository and upload ALL files of this folder (including the hidden
     folder .github).
  2. Actions tab > "Build APK" > Run workflow. After ~4 minutes download the artifact
     "FIFO-Pick-APK" (zip containing app-debug.apk).

UPDATING FROM THE OLD FIFO APK
  The APK from this project is signed with YOUR build machine's debug key. If Android says
  "App not installed" the old APK was signed with a different key. Then:
    1. In the OLD app press "Download CSV" (or "Copy for Excel") to keep your scans.
    2. Uninstall the old app, install the new one.
  (Installing over the old app only keeps data if both APKs were signed with the same key.)

PERMISSIONS
  CAMERA - asked at first start and again when scanning. If you tap "Don't allow" twice, Android
           blocks it; the app then shows "Open Settings" (also Home > Camera / app permissions).
  STORAGE - only on Android 9 or older, to save files into Downloads.
  VIBRATE - beep/vibrate feedback. No internet permission is used.

HOW SCANNING WORKS
  - Start camera scan: full-screen scanner. After each scan the quantity sheet opens; after Save
    the scanner reopens. Press Back in the scanner to stop.
  - Photo scan: choose "Take a photo" or "Gallery". Keep the tag flat, in focus, well lit.
  - Scanner guns / handheld terminals: either configure the scanner to send broadcast intent
    (Zebra DataWedge: action com.fifo.pick.SCAN, intent delivery = Broadcast), or use keyboard
    wedge mode: tap the scan box once (use the keyboard button next to it to type / search).
  - Search box: type material / description / batch, tap the line to pick it.

PLAN FILE COLUMNS
  Material, Batch, Qty (kg)  - required.   Material Description, Storage Loc, Std Pkg, Packs - optional.
  Keep lines in FIFO order (oldest batch first) within each material. Built-in plan = the one in
  the old app (62 lines); upload a new one from Home > Load plan.

WHERE THINGS ARE SAVED
  Scans: inside the app (survive restarts). Uninstalling deletes them -> download the Excel /
  CSV / Backup (JSON) first. Files go to the phone's Downloads folder; "Share Excel" opens the
  WhatsApp / e-mail / Drive chooser.
