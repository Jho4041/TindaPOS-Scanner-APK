package com.tindapos.scanner;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.Gravity;
import android.graphics.Color;
import android.widget.*;
import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final int CAMERA_PERMISSION = 44;
    private PreviewView previewView;
    private EditText hostInput, codeInput, bluetoothMacInput;
    private Spinner connectionMode;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private TextView status;
    private Button scanButton;
    private ExecutorService cameraExecutor;
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private volatile String lastBarcode = "";
    private volatile long lastScanAt = 0;
    private boolean scanning = false;
    private Button imageButton;
    private volatile boolean captureRequested = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraExecutor = Executors.newSingleThreadExecutor();
        buildUi();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 18, 18, 18);
        root.setBackgroundColor(Color.rgb(246,248,247));
        TextView title = new TextView(this);
        title.setText("TindaPOS.ph Scanner");
        title.setTextColor(Color.rgb(20,55,37));
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 8, 0, 16);
        title.setTypeface(null, 1);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        ImageView logo = new ImageView(this);
        logo.setImageResource(com.tindapos.scanner.R.drawable.tindapos_scanner_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(112, 112);
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        logoParams.setMargins(0, 0, 0, 10);
        root.addView(logo, logoParams);

        TextView hostLabel = new TextView(this); hostLabel.setText("TindaPOS.ph address (PC or tablet), example: 192.168.1.10");
        root.addView(hostLabel);
        hostInput = new EditText(this); hostInput.setSingleLine(true); hostInput.setText("192.168.1.10"); hostInput.setHint("PC or tablet IP, e.g. 192.168.1.10");
        root.addView(hostInput, new LinearLayout.LayoutParams(-1, -2));

        TextView codeLabel = new TextView(this); codeLabel.setText("Pairing code shown on the Windows helper");
        root.addView(codeLabel);
        codeInput = new EditText(this); codeInput.setSingleLine(true); codeInput.setHint("8-character pairing code");
        root.addView(codeInput, new LinearLayout.LayoutParams(-1, -2));

        TextView modeLabel = new TextView(this); modeLabel.setText("Connection mode");
        root.addView(modeLabel);
        connectionMode = new Spinner(this);
        connectionMode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Wi-Fi (Windows helper)", "Bluetooth Classic (paired SPP device)"}));
        root.addView(connectionMode, new LinearLayout.LayoutParams(-1, -2));

        TextView btLabel = new TextView(this); btLabel.setText("Bluetooth PC MAC address (Bluetooth mode only)");
        root.addView(btLabel);
        bluetoothMacInput = new EditText(this); bluetoothMacInput.setSingleLine(true); bluetoothMacInput.setHint("Example: AA:BB:CC:DD:EE:FF");
        root.addView(bluetoothMacInput, new LinearLayout.LayoutParams(-1, -2));

        previewView = new PreviewView(this);
        LinearLayout.LayoutParams camParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        camParams.setMargins(0, 14, 0, 12);
        root.addView(previewView, camParams);

        scanButton = new Button(this); scanButton.setText("Start scanning");
        root.addView(scanButton, new LinearLayout.LayoutParams(-1, -2));
        scanButton.setOnClickListener(v -> {
            if (!scanning) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    scanning = true; scanButton.setText("Stop scanning"); startCamera();
                } else ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            } else {
                scanning = false; scanButton.setText("Start scanning");
                try { ProcessCameraProvider.getInstance(this).get().unbindAll(); } catch (Exception ignored) {}
            }
        });
        imageButton = new Button(this); imageButton.setText("Scan product image (pick from suggestions)");
        root.addView(imageButton, new LinearLayout.LayoutParams(-1, -2));
        imageButton.setOnClickListener(v -> requestImageScan());
        status = new TextView(this); status.setText("1. On the PC or tablet open TindaPOS.ph Settings > Wireless scanner > Start receiving scans. 2. Enter its address and pairing code here. 3. Scan barcodes automatically, or tap \"Scan product image\" for items without a barcode (centre the product in the camera).");
        status.setTextColor(Color.rgb(30,70,47)); status.setPadding(2, 10, 2, 4);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    if (captureRequested && scanning) {            // "Scan product image": take this frame
                        captureRequested = false;
                        final String dataUrl = frameToDataUrl(imageProxy);
                        imageProxy.close();
                        if (dataUrl == null) runOnUiThread(() -> status.setText("Could not read the camera image. Try again."));
                        else sendImageQuery(dataUrl);
                        return;
                    }
                    if (!scanning || processing.get()) { imageProxy.close(); return; }
                    @SuppressWarnings("UnsafeOptInUsageError")
                    android.media.Image mediaImage = imageProxy.getImage();
                    if (mediaImage == null) { imageProxy.close(); return; }
                    InputImage input = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
                    processing.set(true);
                    BarcodeScanning.getClient().process(input)
                        .addOnSuccessListener(barcodes -> {
                            long now = System.currentTimeMillis();
                            for (Barcode barcode : barcodes) {
                                String value = barcode.getRawValue();
                                if (value != null && !value.trim().isEmpty() &&
                                    (!value.equals(lastBarcode) || now - lastScanAt > 1800)) {
                                    lastBarcode = value; lastScanAt = now;
                                    runOnUiThread(() -> sendBarcode(value));
                                    break;
                                }
                            }
                        })
                        .addOnCompleteListener(task -> { processing.set(false); imageProxy.close(); });
                });
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                status.setText("Camera ready. Aim at a product barcode.");
            } catch (Exception e) {
                status.setText("Camera error: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void sendBarcode(String barcode) {
        if (connectionMode != null && connectionMode.getSelectedItemPosition() == 1) {
            sendBluetoothBarcode(barcode);
            return;
        }
        String endpoint = hostInput.getText().toString().trim();
        // accept a bare IP ("192.168.1.10"), IP:port, or a full http://IP:8765/scan address
        String scheme = endpoint.startsWith("https://") ? "https://" : "http://";
        String rest = endpoint.replaceFirst("^https?://", "");
        String path = "";
        int slash = rest.indexOf('/');
        if (slash >= 0) { path = rest.substring(slash); rest = rest.substring(0, slash); }
        if (!rest.contains(":")) rest = rest + ":8765";
        endpoint = scheme + rest + path;
        if (!endpoint.endsWith("/scan")) endpoint = endpoint.replaceAll("/+$", "") + "/scan";
        String pairCode = codeInput.getText().toString().trim();
        final String finalEndpoint = endpoint;
        status.setText("Sending " + barcode + " over Wi-Fi …");
        Executors.newSingleThreadExecutor().execute(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject();
                body.put("barcode", barcode);
                body.put("pairCode", pairCode);
                conn = (HttpURLConnection) new URL(finalEndpoint).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }
                int response = conn.getResponseCode();
                runOnUiThread(() -> status.setText(response == 200 ? "Sent: " + barcode : "Connection rejected (HTTP " + response + "). Check PC IP and pairing code."));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Could not reach Windows helper. Check same Wi-Fi, PC address, and firewall."));
            } finally { if (conn != null) conn.disconnect(); }
        });
    }


    // ---------------------------------------------------------------- image scan (photo -> suggestions -> pick -> add to cart)
    private void requestImageScan() {
        if (connectionMode != null && connectionMode.getSelectedItemPosition() == 1) {
            status.setText("Image scan works over Wi-Fi only. Switch the connection mode to Wi-Fi.");
            return;
        }
        if (!scanning) {
            status.setText("Tap Start scanning first, centre the product in the camera, then tap Scan product image.");
            return;
        }
        status.setText("Hold steady … taking a photo of the product");
        captureRequested = true;
    }

    private String frameToDataUrl(ImageProxy proxy) {
        try {
            Bitmap bmp = proxy.toBitmap();
            int rot = proxy.getImageInfo().getRotationDegrees();
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            }
            int side = Math.min(bmp.getWidth(), bmp.getHeight());
            Bitmap square = Bitmap.createBitmap(bmp, (bmp.getWidth() - side) / 2, (bmp.getHeight() - side) / 2, side, side);
            Bitmap small = Bitmap.createScaledBitmap(square, 320, 320, true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 75, bos);
            return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    // scheme + host:port of the POS (no path)
    private String baseUrl() {
        String endpoint = hostInput.getText().toString().trim();
        String scheme = endpoint.startsWith("https://") ? "https://" : "http://";
        String rest = endpoint.replaceFirst("^https?://", "");
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        if (!rest.contains(":")) rest = rest + ":8765";
        return scheme + rest;
    }

    private JSONObject postJson(String path, JSONObject body, int readTimeoutMs) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl() + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }
            int code = conn.getResponseCode();
            java.io.InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = "{}";
            if (is != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                is.close();
                text = bo.toString("UTF-8");
            }
            JSONObject out = new JSONObject(text.isEmpty() ? "{}" : text);
            if (code >= 400) throw new Exception(out.optString("error", "HTTP " + code));
            return out;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void sendImageQuery(final String dataUrl) {
        final String pairCode = codeInput.getText().toString().trim();
        runOnUiThread(() -> status.setText("Looking for matching products …"));
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("pairCode", pairCode);
                body.put("image", dataUrl);
                JSONObject res = postJson("/image", body, 25000);
                final String queryId = res.optString("queryId", "");
                final JSONArray list = res.optJSONArray("suggestions");
                final String hint = res.optString("hint", "");
                runOnUiThread(() -> showSuggestions(list == null ? new JSONArray() : list, queryId, hint));
            } catch (Exception e) {
                final String m = e.getMessage();
                runOnUiThread(() -> status.setText("Image scan failed: " + (m == null ? "no answer" : m) + ". It needs TindaPOS.ph on the PC or tablet (the Windows helper does not support it)."));
            }
        });
    }

    private Bitmap decodeThumb(String dataUrl) {
        try {
            int i = dataUrl.indexOf(',');
            if (i < 0) return null;
            byte[] b = Base64.decode(dataUrl.substring(i + 1), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(b, 0, b.length);
        } catch (Exception e) {
            return null;
        }
    }

    private void showSuggestions(JSONArray list, final String queryId, String hint) {
        status.setText(list.length() == 0 ? "No matches found. " + hint : "Tap the product that matches.");
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(12, 8, 12, 8);
        sv.addView(col);
        final androidx.appcompat.app.AlertDialog[] dlg = new androidx.appcompat.app.AlertDialog[1];
        if (list.length() == 0 && hint != null && !hint.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(hint);
            col.addView(t);
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject s = list.optJSONObject(i);
            if (s == null) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(8, 10, 8, 10);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap bm = s.isNull("thumb") ? null : decodeThumb(s.optString("thumb", ""));
            if (bm != null) iv.setImageBitmap(bm);
            row.addView(iv, new LinearLayout.LayoutParams(160, 160));
            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.setPadding(16, 0, 0, 0);
            TextView n = new TextView(this);
            n.setText(s.optString("name", ""));
            n.setTextSize(17);
            n.setTypeface(null, 1);
            TextView p = new TextView(this);
            p.setText("\u20B1" + String.format(java.util.Locale.US, "%.2f", s.optDouble("price", 0)));
            txt.addView(n);
            txt.addView(p);
            row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
            final String id = s.optString("id", "");
            final String vid = s.isNull("vid") ? "" : s.optString("vid", "");
            final String name = s.optString("name", "");
            row.setOnClickListener(v -> {
                if (dlg[0] != null) dlg[0].dismiss();
                sendAdd(id, vid, queryId, name);
            });
            col.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        androidx.appcompat.app.AlertDialog.Builder b = new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Which product is this?")
            .setView(sv)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Search by name", (d, w) -> askSearch(queryId));
        dlg[0] = b.create();
        dlg[0].show();
    }

    private void askSearch(final String queryId) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Type part of the product name");
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Search by name")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Search", (d, w) -> sendFind(input.getText().toString().trim(), queryId))
            .show();
    }

    private void sendFind(final String q, final String queryId) {
        if (q.isEmpty()) return;
        final String pairCode = codeInput.getText().toString().trim();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("pairCode", pairCode);
                body.put("q", q);
                if (queryId != null && !queryId.isEmpty()) body.put("queryId", queryId);
                JSONObject res = postJson("/find", body, 10000);
                final JSONArray list = res.optJSONArray("suggestions");
                runOnUiThread(() -> showSuggestions(list == null ? new JSONArray() : list, queryId, "Nothing with that name."));
            } catch (Exception e) {
                final String m = e.getMessage();
                runOnUiThread(() -> status.setText("Search failed: " + (m == null ? "no answer" : m)));
            }
        });
    }

    private void sendAdd(final String id, final String vid, final String queryId, final String name) {
        final String pairCode = codeInput.getText().toString().trim();
        status.setText("Adding " + name + " …");
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("pairCode", pairCode);
                body.put("id", id);
                if (vid != null && !vid.isEmpty()) body.put("vid", vid);
                if (queryId != null && !queryId.isEmpty()) body.put("queryId", queryId);
                JSONObject res = postJson("/add", body, 10000);
                final boolean learned = res.optBoolean("learned", false);
                runOnUiThread(() -> status.setText("Added to cart: " + name + (learned ? " (the POS learned this photo)" : "")));
            } catch (Exception e) {
                final String m = e.getMessage();
                runOnUiThread(() -> status.setText("Could not add: " + (m == null ? "no answer" : m)));
            }
        });
    }

    private void sendBluetoothBarcode(String barcode) {
        String mac = bluetoothMacInput.getText().toString().trim();
        if (mac.isEmpty()) {
            status.setText("Enter the paired Windows Bluetooth device MAC address.");
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            status.setText("This Android device does not support Bluetooth.");
            return;
        }
        if (!adapter.isEnabled()) {
            status.setText("Turn on Bluetooth and pair with the Windows PC first.");
            return;
        }
        status.setText("Connecting over Bluetooth …");
        Executors.newSingleThreadExecutor().execute(() -> {
            BluetoothSocket socket = null;
            try {
                BluetoothDevice device = adapter.getRemoteDevice(mac);
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                adapter.cancelDiscovery();
                socket.connect();
                socket.getOutputStream().write((barcode + "\\n").getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                runOnUiThread(() -> status.setText("Sent over Bluetooth: " + barcode));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Bluetooth connection failed. Pair the PC and phone, and configure the Windows helper's Bluetooth SPP receiver."));
            } finally {
                if (socket != null) try { socket.close(); } catch (Exception ignored) {}
            }
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            status.setText("Camera permission granted. Tap Start scanning.");
        } else status.setText("Camera permission is required to scan barcodes.");
    }

    @Override protected void onDestroy() {
        scanning = false;
        if (cameraExecutor != null) cameraExecutor.shutdown();
        super.onDestroy();
    }
}
