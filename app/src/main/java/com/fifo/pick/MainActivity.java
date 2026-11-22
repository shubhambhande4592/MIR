package com.fifo.pick;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Hosts the FIFO Pick Scanner web app (assets/index.html) inside a WebView and adds the native
 * things a web page cannot do reliably on every phone (same approach as the RM Stock app):
 *  - Camera permission (asked at first start and again whenever scanning is started)
 *  - Native QR / barcode scanner (ZXing, no Google Play Services needed)
 *  - Photo scan: take a picture (or pick one from the gallery) and decode it natively with ZXing
 *  - Hardware scanner guns / handheld terminals (broadcast-intent mode) - kept from the old FIFO app
 *  - Saving CSV / Excel into the Downloads folder, sharing, clipboard
 *  - File picker (plan upload / restore), native alert + confirm dialogs, Back button
 */
public class MainActivity extends AppCompatActivity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";

    /** Intent actions used by the common handheld-scanner services (same list as the old FIFO app). */
    private static final String[] SCAN_ACTIONS = {
            "com.fifo.pick.SCAN",
            "android.intent.ACTION_DECODE_DATA",
            "nlscan.action.SCANNER_RESULT",
            "scan.rcv.message"
    };
    /** String extras that carry the scanned text on those devices (Zebra DataWedge, Honeywell, Newland, Urovo ...). */
    private static final String[] SCAN_EXTRAS = {
            "com.symbol.datawedge.data_string",
            "SCAN_BARCODE1",
            "barcode_string",
            "barcode",
            "barocode",
            "scannerdata",
            "code",
            "data"
    };

    /** Barcode types read from photos. Same family as the live scanner. */
    private static final Map<DecodeHintType, Object> PHOTO_HINTS = new EnumMap<>(DecodeHintType.class);
    static {
        PHOTO_HINTS.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(
                BarcodeFormat.QR_CODE, BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.DATA_MATRIX));
        PHOTO_HINTS.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
    }

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private Runnable onCameraGranted;
    private Runnable onCameraDenied;
    private File photoFile;
    private boolean rxRegistered = false;

    // ---------------------------------------------------------------- activity result launchers
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionResult);

    private final ActivityResultLauncher<ScanOptions> scanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                final String code = result.getContents();
                if (code != null) {
                    js("window.onNativeScan && window.onNativeScan(" + JSONObject.quote(code) + ")");
                } else {
                    js("window.onNativeScanCancel && window.onNativeScanCancel()");
                }
            });

    private final ActivityResultLauncher<Intent> fileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
                if (filePathCallback == null) return;
                Uri[] uris = WebChromeClient.FileChooserParams.parseResult(res.getResultCode(), res.getData());
                filePathCallback.onReceiveValue(uris);
                filePathCallback = null;
            });

    private final ActivityResultLauncher<Uri> takePhotoLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
                if (Boolean.TRUE.equals(ok) && photoFile != null && photoFile.exists()) {
                    decodePhotoAsync(photoFile, null);
                } else {
                    photoResult("", "cancel");
                }
            });

    private final ActivityResultLauncher<String> pickPhotoLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    decodePhotoAsync(null, uri);
                } else {
                    photoResult("", "cancel");
                }
            });

    // ---------------------------------------------------------------- hardware scanner receiver
    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String code = extractScan(intent);
            if (code != null && !code.trim().isEmpty()) {
                js("window.onScan && window.onScan(" + JSONObject.quote(code) + ")");
            }
        }
    };

    private static String extractScan(Intent i) {
        if (i == null) return null;
        for (String key : SCAN_EXTRAS) {
            String v = null;
            try {
                v = i.getStringExtra(key);
            } catch (Exception ignored) { }
            if (v != null && !v.trim().isEmpty()) return v;
        }
        // Urovo style: raw bytes in "barocode" + "length"
        try {
            byte[] b = i.getByteArrayExtra("barocode");
            if (b != null && b.length > 0) {
                int len = i.getIntExtra("length", b.length);
                if (len <= 0 || len > b.length) len = b.length;
                return new String(b, 0, len, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ---------------------------------------------------------------- lifecycle
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        setContentView(web);

        // Serves assets/index.html from the same secure https origin the old FIFO app used, so the
        // scans already saved on the phone (localStorage) are still there after updating.
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage = where picks are saved
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);                    // ignore phone font-size setting so the layout stays intact

        web.addJavascriptInterface(new Bridge(), "AndroidApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean wantsVideo = false;
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) wantsVideo = true;
                    }
                    if (!wantsVideo) { request.deny(); return; }
                    withCamera(
                            () -> request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}),
                            request::deny);
                });
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    fileLauncher.launch(params.createIntent());
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    toast("No file manager found on this phone");
                    return false;
                }
                return true;
            }

            @Override
            public boolean onJsAlert(WebView v, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message).setCancelable(false)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message).setCancelable(false)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                        .show();
                return true;
            }
        });

        // Hardware / gesture Back: let the page close a sheet or go Home first; only then offer to exit
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                web.evaluateJavascript(
                        "(function(){return window.onAndroidBack?window.onAndroidBack():false})()",
                        value -> {
                            if ("true".equals(value)) return;
                            new AlertDialog.Builder(MainActivity.this)
                                    .setMessage("Exit FIFO Pick? Your scans stay saved on this phone.")
                                    .setPositiveButton("Exit", (d, w) -> finish())
                                    .setNegativeButton("Stay", null)
                                    .show();
                        });
            }
        });

        web.loadUrl(START_URL);

        // Ask for the permissions right at first start (camera + storage on Android 9 and older)
        requestStartupPermissions();
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
        unregisterScanReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        registerScanReceiver();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("AndroidApp");
            web.destroy();
        }
        super.onDestroy();
    }

    private void registerScanReceiver() {
        if (rxRegistered) return;
        IntentFilter f = new IntentFilter();
        for (String a : SCAN_ACTIONS) f.addAction(a);
        try {
            // Exported: the scanner service of the handheld sends the broadcast from outside this app.
            ContextCompat.registerReceiver(this, scanReceiver, f, ContextCompat.RECEIVER_EXPORTED);
            rxRegistered = true;
        } catch (Exception ignored) { }
    }

    private void unregisterScanReceiver() {
        if (!rxRegistered) return;
        try {
            unregisterReceiver(scanReceiver);
        } catch (Exception ignored) { }
        rxRegistered = false;
    }

    // ---------------------------------------------------------------- permissions
    private boolean hasCamera() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean needsLegacyStorage() {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.P;
    }

    private boolean hasLegacyStorage() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStartupPermissions() {
        if (needsLegacyStorage() && !hasLegacyStorage()) {
            if (hasCamera()) {
                permissionLauncher.launch(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE});
            } else {
                permissionLauncher.launch(new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE});
            }
        } else if (!hasCamera()) {
            permissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
        }
    }

    /** Runs onGranted when the camera permission is available, asking the user first if needed. */
    private void withCamera(Runnable granted, Runnable denied) {
        if (hasCamera()) {
            granted.run();
            return;
        }
        onCameraGranted = granted;
        onCameraDenied = denied;
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            new AlertDialog.Builder(this)
                    .setTitle("Camera permission")
                    .setMessage("FIFO Pick needs the camera only to scan the QR code on each bag / label.")
                    .setCancelable(false)
                    .setPositiveButton("Allow", (d, w) -> permissionLauncher.launch(new String[]{Manifest.permission.CAMERA}))
                    .setNegativeButton("Not now", (d, w) -> {
                        Runnable r = onCameraDenied;
                        onCameraGranted = null;
                        onCameraDenied = null;
                        if (r != null) r.run();
                    })
                    .show();
        } else {
            permissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
        }
    }

    private void onPermissionResult(Map<String, Boolean> result) {
        if (!result.containsKey(Manifest.permission.CAMERA)) return; // storage-only result
        boolean ok = Boolean.TRUE.equals(result.get(Manifest.permission.CAMERA));
        Runnable g = onCameraGranted;
        Runnable d = onCameraDenied;
        onCameraGranted = null;
        onCameraDenied = null;
        if (ok) {
            if (g != null) g.run();
            return;
        }
        if (d != null) d.run();
        if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            // "Don't ask again" was chosen -> only the phone's Settings screen can enable it now
            new AlertDialog.Builder(this)
                    .setTitle("Camera is blocked")
                    .setMessage("Scanning needs the camera. Open Settings > Permissions > Camera and choose Allow.")
                    .setPositiveButton("Open Settings", (dlg, w) -> openAppSettings())
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            toast("Camera permission denied - scanning will not work");
        }
    }

    private void openAppSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    // ---------------------------------------------------------------- native live scanner
    private void launchScanner() {
        ScanOptions o = new ScanOptions();
        o.setDesiredBarcodeFormats(Arrays.asList(ScanOptions.QR_CODE, ScanOptions.CODE_128, ScanOptions.CODE_39, ScanOptions.DATA_MATRIX));
        o.setPrompt("Point at the QR code  |  Volume up = torch  |  Back = stop");
        o.setBeepEnabled(false);          // the web page plays its own OK / error beep
        o.setOrientationLocked(false);
        o.setBarcodeImageEnabled(false);
        scanLauncher.launch(o);
    }

    // ---------------------------------------------------------------- photo scan
    private void showPhotoChooser() {
        new AlertDialog.Builder(this)
                .setTitle("Photo scan")
                .setItems(new CharSequence[]{"Take a photo of the tag", "Choose a photo from gallery"}, (d, which) -> {
                    if (which == 0) {
                        takePhoto();
                    } else {
                        try {
                            pickPhotoLauncher.launch("image/*");
                        } catch (Exception e) {
                            toast("No gallery app found");
                            photoResult("", "cancel");
                        }
                    }
                })
                .setNegativeButton("Cancel", (d, w) -> photoResult("", "cancel"))
                .setOnCancelListener(d -> photoResult("", "cancel"))
                .show();
    }

    private void takePhoto() {
        withCamera(() -> {
            try {
                File dir = new File(getCacheDir(), "photos");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                photoFile = new File(dir, "scan_" + System.currentTimeMillis() + ".jpg");
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
                takePhotoLauncher.launch(uri);
            } catch (Exception e) {
                toast("Could not open the camera: " + e.getMessage());
                photoResult("", "cancel");
            }
        }, () -> photoResult("", "cancel"));
    }

    /** Decodes the picture on a background thread and returns the text to the page. */
    private void decodePhotoAsync(final File file, final Uri uri) {
        toast("Reading photo...");
        new Thread(() -> {
            String code = null;
            String status = "none";
            try {
                byte[] data;
                if (file != null) {
                    try (InputStream in = new FileInputStream(file)) {
                        data = readAll(in);
                    }
                } else {
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IOException("cannot open photo");
                        data = readAll(in);
                    }
                }
                code = decodePhoto(data);
                if (code != null && !code.trim().isEmpty()) status = "ok";
                else code = null;
            } catch (Throwable t) {
                status = "error: " + t.getMessage();
            } finally {
                if (file != null) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
            photoResult(code == null ? "" : code, status);
        }).start();
    }

    private void photoResult(String code, String status) {
        js("window.onNativePhoto && window.onNativePhoto(" + JSONObject.quote(code) + "," + JSONObject.quote(status) + ")");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    /**
     * Tries several versions of the picture (full frame at different sizes, centre crop) with
     * several binarizers and also inverted, so a normal phone photo of a tag is read reliably.
     */
    private static String decodePhoto(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        int max = Math.max(bounds.outWidth, bounds.outHeight);
        if (max <= 0) return null;
        int sample = 1;
        while (max / sample > 3000) sample *= 2;
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        Bitmap full = BitmapFactory.decodeByteArray(data, 0, data.length, opt);
        if (full == null) return null;

        List<Bitmap> tries = new ArrayList<>();
        tries.add(fit(full, 2000));
        tries.add(fit(full, 1400));
        int cw = (int) (full.getWidth() * 0.7f), ch = (int) (full.getHeight() * 0.7f);
        if (cw > 50 && ch > 50) {
            try {
                Bitmap crop = Bitmap.createBitmap(full, (full.getWidth() - cw) / 2, (full.getHeight() - ch) / 2, cw, ch);
                tries.add(fit(crop, 1400));
            } catch (Throwable ignored) { }
        }
        tries.add(fit(full, 1000));
        tries.add(fit(full, 700));

        for (Bitmap b : tries) {
            String r = decodeBitmap(b);
            if (r != null) return r;
        }
        return null;
    }

    private static Bitmap fit(Bitmap src, int maxSide) {
        int w = src.getWidth(), h = src.getHeight(), m = Math.max(w, h);
        if (m <= maxSide) return src;
        float sc = (float) maxSide / m;
        return Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * sc)), Math.max(1, Math.round(h * sc)), true);
    }

    private static String decodeBitmap(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        LuminanceSource src = new RGBLuminanceSource(w, h, px);
        String r = tryReader(new BinaryBitmap(new HybridBinarizer(src)));
        if (r == null) r = tryReader(new BinaryBitmap(new GlobalHistogramBinarizer(src)));
        if (r == null) r = tryReader(new BinaryBitmap(new HybridBinarizer(src.invert())));
        return r;
    }

    private static String tryReader(BinaryBitmap bb) {
        MultiFormatReader reader = new MultiFormatReader();
        reader.setHints(PHOTO_HINTS);
        try {
            return reader.decodeWithState(bb).getText();
        } catch (Exception e) {
            return null;
        } finally {
            reader.reset();
        }
    }

    // ---------------------------------------------------------------- helpers
    private void js(final String script) {
        // small delay: the result callback can arrive just before the WebView is resumed
        web.postDelayed(() -> web.evaluateJavascript(script, null), 150);
    }

    private void toast(String m) {
        runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
    }

    private static String safeName(String name) {
        String n = name == null ? "file" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        return n.isEmpty() ? "file" : n;
    }

    // ---------------------------------------------------------------- JS bridge (window.AndroidApp)
    private class Bridge {

        /** Opens the full-screen scanner. Result comes back through window.onNativeScan(code). */
        @JavascriptInterface
        public void scanQR() {
            runOnUiThread(() -> withCamera(
                    MainActivity.this::launchScanner,
                    () -> js("window.onNativeScanError && window.onNativeScanError("
                            + JSONObject.quote("Camera permission is needed to scan. Tap 'Camera / app permissions' on the Home screen.") + ")")));
        }

        /** Photo scan: asks take-photo / gallery, decodes it and calls window.onNativePhoto(code, status). */
        @JavascriptInterface
        public void scanPhoto() {
            runOnUiThread(MainActivity.this::showPhotoChooser);
        }

        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(MainActivity.this::openAppSettings);
        }

        /** Copies text to the phone clipboard (more reliable than the web clipboard inside a WebView). */
        @JavascriptInterface
        public boolean copyText(String text) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) return false;
                cm.setPrimaryClip(ClipData.newPlainText("FIFO Pick", text));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Saves a file (sent as base64) into the phone's Downloads folder. Returns "OK: ..." or "ERR: ...". */
        @JavascriptInterface
        public String saveFile(String name, String mime, String base64) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                String fn = safeName(name);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, fn);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return "ERR: could not create file in Downloads";
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os == null) return "ERR: could not open file";
                        os.write(data);
                    }
                    return "OK: Saved in Downloads / " + fn;
                } else {
                    File dir = hasLegacyStorage()
                            ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            : getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) return "ERR: storage not available";
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File f = new File(dir, fn);
                    try (FileOutputStream os = new FileOutputStream(f)) {
                        os.write(data);
                    }
                    return "OK: Saved: " + f.getAbsolutePath();
                }
            } catch (Exception e) {
                return "ERR: " + e.getMessage();
            }
        }

        /** Opens the Android share sheet with the file attached. */
        @JavascriptInterface
        public void shareFile(String name, final String mime, String base64) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                File dir = new File(getCacheDir(), "exports");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File f = new File(dir, safeName(name));
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(data);
                }
                final Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", f);
                runOnUiThread(() -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType(mime == null || mime.isEmpty() ? "*/*" : mime);
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_SUBJECT, "FIFO Pick Log");
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Share file"));
                });
            } catch (Exception e) {
                toast("Share failed: " + e.getMessage());
            }
        }
    }
}
