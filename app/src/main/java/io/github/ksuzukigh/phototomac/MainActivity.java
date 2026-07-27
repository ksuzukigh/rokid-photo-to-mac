package io.github.ksuzukigh.phototomac;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** A deliberately small proof of concept: one tap captures a JPEG and POSTs it to the Mac. */
public final class MainActivity extends Activity {
    private static final String TAG = "RokidPhotoBridge";
    private static final int DISCOVERY_PORT = 8766;
    private static final String PREFERENCES = "bridge";
    private static final String TOKEN_KEY = "token";
    private static final String DISCOVERY_REQUEST_PREFIX = "ROKID_PHOTO_BRIDGE_DISCOVER 2 ";
    private static final int CAMERA_PERMISSION = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TextView status;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private volatile boolean busy = false;
    private boolean waitingForWifi = false;
    private CameraDevice openCamera;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Surface previewSurface;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        saveSetupToken();
        makeUi();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }
    }

    private void saveSetupToken() {
        String setupToken = getIntent().getStringExtra("setup_token");
        if (setupToken != null && setupToken.matches("[0-9a-f]{32}")) {
            getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putString(TOKEN_KEY, setupToken)
                    .apply();
            android.util.Log.i(TAG, "Pairing token saved");
        }
    }

    private void makeUi() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(30, 60, 30, 40);
        panel.setBackgroundColor(Color.BLACK);
        panel.setClickable(true);
        panel.setFocusable(true);
        panel.setOnClickListener(v -> capture());

        TextView title = new TextView(this);
        title.setText("写真をMacへ");
        title.setTextColor(Color.rgb(140, 255, 140));
        title.setTextSize(26);
        panel.addView(title);

        status = new TextView(this);
        status.setText("タップして撮影します");
        status.setTextColor(Color.WHITE);
        status.setTextSize(28);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 24, 0, 24);
        panel.addView(status);

        TextView note = new TextView(this);
        note.setText("いま見ている景色を撮り、Macに保存します");
        note.setTextColor(Color.LTGRAY);
        note.setTextSize(14);
        note.setPadding(0, 28, 0, 0);
        panel.addView(note);
        setContentView(panel);
        panel.requestFocus();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_PERMISSION && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            setStatus("カメラの許可が必要です");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (!busy && status != null) {
            if (waitingForWifi && isWifiEnabled()) {
                android.util.Log.i(TAG, "Returned from Wi-Fi settings; Wi-Fi is enabled");
                waitingForWifi = false;
                setStatus("Wi-Fiをオンにしました\nタップして撮影します");
            } else if (waitingForWifi) {
                android.util.Log.i(TAG, "Returned from Wi-Fi settings; Wi-Fi is still off");
                setStatus("Wi-Fiはオフです\nタップして設定");
            } else {
                setStatus("タップして撮影します");
            }
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            capture();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void capture() {
        if (busy) return;
        if (!isWifiEnabled()) {
            if (!waitingForWifi) recoverWifi();
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            return;
        }
        busy = true;
        setStatus("1/3\n撮影中…");
        cameraThread = new HandlerThread("rokid-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            android.util.Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
            android.util.Size photoSize = Arrays.stream(sizes)
                    .filter(s -> s.getWidth() <= 1920)
                    .max(Comparator.comparingInt(s -> s.getWidth() * s.getHeight()))
                    .orElse(sizes[0]);
            imageReader = ImageReader.newInstance(photoSize.getWidth(), photoSize.getHeight(), ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(r -> {
                try (Image image = r.acquireNextImage()) {
                    if (image == null) return;
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    java.nio.ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);
                    bytes.write(data);
                    closeCamera();
                    upload(bytes.toByteArray());
                } catch (IOException error) {
                    done("写真を読めませんでした");
                } finally {
                    r.close();
                }
            }, cameraHandler);
            // The RV101 needs a short preview phase for automatic exposure to settle.
            SurfaceTexture texture = new SurfaceTexture(10);
            texture.setDefaultBufferSize(photoSize.getWidth(), photoSize.getHeight());
            previewSurface = new Surface(texture);
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    openCamera = camera;
                    try {
                        Surface jpegSurface = imageReader.getSurface();
                        camera.createCaptureSession(Arrays.asList(previewSurface, jpegSurface), new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession session) {
                                try {
                                    captureSession = session;
                                    CaptureRequest.Builder preview = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                    preview.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                                    preview.addTarget(previewSurface);
                                    session.setRepeatingRequest(preview.build(), null, cameraHandler);
                                    cameraHandler.postDelayed(() -> {
                                        try {
                                            CaptureRequest.Builder still = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                            still.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                                            // The camera is mounted sideways in the glasses frame.
                                            still.set(CaptureRequest.JPEG_ORIENTATION, 270);
                                            still.addTarget(jpegSurface);
                                            session.capture(still.build(), null, cameraHandler);
                                        } catch (Exception error) { done("撮影できませんでした"); }
                                    }, 1200);
                                } catch (Exception error) { done("撮影できませんでした"); }
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession session) { done("カメラを準備できませんでした"); }
                        }, cameraHandler);
                    } catch (Exception error) { done("カメラを開けませんでした"); }
                }
                @Override public void onDisconnected(CameraDevice camera) { closeCamera(); done("カメラ接続が切れました"); }
                @Override public void onError(CameraDevice camera, int error) { closeCamera(); done("カメラエラー " + error); }
            }, cameraHandler);
        } catch (Exception error) {
            done("カメラが見つかりませんでした");
        }
    }

    private boolean isWifiEnabled() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        return wifi != null && wifi.isWifiEnabled();
    }

    private void recoverWifi() {
        waitingForWifi = true;
        android.util.Log.i(TAG, "Wi-Fi is off; opening Wi-Fi settings");
        setStatus("Wi-Fiをオンにします");
        Toast.makeText(this, "右つるタップでWi-Fiをオン", Toast.LENGTH_LONG).show();
        openWifiSettings();
    }

    private void openWifiSettings() {
        try {
            startActivity(new android.content.Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (Exception error) {
            android.util.Log.e(TAG, "Could not open Wi-Fi settings", error);
            waitingForWifi = false;
            setStatus("Wi-Fi設定を開けませんでした");
        }
    }

    private void upload(byte[] jpeg) {
        setStatus("2/3\nMacへ送信中…");
        network.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String token = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                        .getString(TOKEN_KEY, "");
                if (!token.matches("[0-9a-f]{32}")) {
                    done("初期設定が必要です\nMacから入れ直してください");
                    return;
                }
                String uploadUrl = discoverUploadUrl(token);
                String name = "rokid-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".jpg";
                connection = (HttpURLConnection) new URL(uploadUrl + "?filename=" + name).openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(jpeg.length);
                connection.setRequestProperty("Content-Type", "image/jpeg");
                connection.setRequestProperty("X-Photo-Token", token);
                try (OutputStream out = connection.getOutputStream()) { out.write(jpeg); }
                int code = connection.getResponseCode();
                done(code >= 200 && code < 300 ? "3/3\n✓ Macに保存しました\nタップでもう一枚" : "Macが受け取りませんでした (" + code + ")");
            } catch (Exception error) {
                android.util.Log.e(TAG, "Upload failed", error);
                done("Macへ送れませんでした");
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    /** Finds the receiver on the current Wi-Fi without storing its IP address. */
    private String discoverUploadUrl(String token) throws IOException {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String nonceHex = toHex(nonce);
        byte[] discoveryRequest =
                (DISCOVERY_REQUEST_PREFIX + nonceHex).getBytes(StandardCharsets.US_ASCII);
        byte[] expectedProof;
        try {
            expectedProof = hmacSha256(token, nonceHex);
        } catch (GeneralSecurityException error) {
            throw new IOException("Could not authenticate receiver discovery", error);
        }

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);

            Set<String> destinations = new HashSet<>();
            sendDiscoveryBestEffort(
                    socket,
                    InetAddress.getByName("255.255.255.255"),
                    destinations,
                    discoveryRequest
            );
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
                    InetAddress broadcast = address.getBroadcast();
                    if (broadcast != null) {
                        sendDiscoveryBestEffort(socket, broadcast, destinations, discoveryRequest);
                    }
                }
            }

            String found = receiveDiscovery(socket, 700, expectedProof);
            if (found != null) return found;
            android.util.Log.i(TAG, "Broadcast discovery timed out; probing local network");

            // Some Android Wi-Fi implementations do not pass broadcast packets.
            // Probe each address in the device's local /24 as a dependable fallback.
            interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress local = interfaceAddress.getAddress();
                    if (!(local instanceof Inet4Address)) continue;
                    byte[] candidate = local.getAddress().clone();
                    for (int host = 1; host <= 254; host++) {
                        candidate[3] = (byte) host;
                        sendDiscoveryBestEffort(
                                socket,
                                InetAddress.getByAddress(candidate),
                                destinations,
                                discoveryRequest
                        );
                    }
                }
            }

            found = receiveDiscovery(socket, 3000, expectedProof);
            if (found != null) return found;
            throw new IOException("Mac receiver was not found");
        }
    }

    private String receiveDiscovery(
            DatagramSocket socket,
            int timeoutMs,
            byte[] expectedProof
    ) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            int remaining = (int) (deadline - System.currentTimeMillis());
            if (remaining <= 0) return null;
            socket.setSoTimeout(remaining);
            try {
                byte[] responseBytes = new byte[256];
                DatagramPacket response = new DatagramPacket(responseBytes, responseBytes.length);
                socket.receive(response);
                String message = new String(
                        response.getData(),
                        response.getOffset(),
                        response.getLength(),
                        StandardCharsets.US_ASCII
                );
                String[] fields = message.trim().split(" ");
                if (
                        fields.length != 4
                        || !"ROKID_PHOTO_BRIDGE".equals(fields[0])
                        || !"2".equals(fields[1])
                ) {
                    continue;
                }
                int port;
                byte[] suppliedProof;
                try {
                    port = Integer.parseInt(fields[2]);
                    suppliedProof = fromHex(fields[3]);
                } catch (IllegalArgumentException error) {
                    continue;
                }
                if (
                        port < 1
                        || port > 65535
                        || !MessageDigest.isEqual(expectedProof, suppliedProof)
                ) {
                    continue;
                }
                String uploadUrl =
                        "http://" + response.getAddress().getHostAddress() + ":" + port + "/upload";
                android.util.Log.i(TAG, "Authenticated receiver found at " + uploadUrl);
                return uploadUrl;
            } catch (SocketTimeoutException timeout) {
                return null;
            }
        }
    }

    private void sendDiscoveryBestEffort(
            DatagramSocket socket,
            InetAddress address,
            Set<String> destinations,
            byte[] requestBytes
    ) {
        try {
            sendDiscovery(socket, address, destinations, requestBytes);
        } catch (IOException ignored) {
            // Continue with the other local addresses.
        }
    }

    private void sendDiscovery(
            DatagramSocket socket,
            InetAddress address,
            Set<String> destinations,
            byte[] requestBytes
    ) throws IOException {
        if (!destinations.add(address.getHostAddress())) return;
        DatagramPacket request =
                new DatagramPacket(requestBytes, requestBytes.length, address, DISCOVERY_PORT);
        socket.send(request);
    }

    private static byte[] hmacSha256(String token, String nonce)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
        return mac.doFinal(nonce.getBytes(StandardCharsets.US_ASCII));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.US, "%02x", value & 0xff));
        return result.toString();
    }

    private static byte[] fromHex(String value) {
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid proof");
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < value.length(); index += 2) {
            result[index / 2] = (byte) Integer.parseInt(value.substring(index, index + 2), 16);
        }
        return result;
    }

    private void setStatus(String message) { runOnUiThread(() -> status.setText(message)); }

    private void done(String message) {
        closeCamera();
        runOnUiThread(() -> {
            status.setText(message);
            busy = false;
        });
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
        }
    }

    private void closeCamera() {
        if (captureSession != null) { captureSession.close(); captureSession = null; }
        if (openCamera != null) { openCamera.close(); openCamera = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
    }

    @Override protected void onDestroy() {
        network.shutdownNow();
        if (cameraThread != null) cameraThread.quitSafely();
        super.onDestroy();
    }
}
