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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
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
    private final Handler watchdog = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;
    private volatile boolean awaitingImage = false;
    private final Runnable captureTimeout = () -> {
        if (busy && awaitingImage) {
            done("撮影できませんでした\nもう一度タップしてください");
        }
    };
    private boolean waitingForWifi = false;
    private volatile CameraDevice openCamera;
    private volatile CameraCaptureSession captureSession;
    private volatile ImageReader imageReader;
    private volatile Surface previewSurface;
    private volatile SurfaceTexture previewTexture;

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
            if (waitingForWifi && isWifiConnected()) {
                android.util.Log.i(TAG, "Returned from Wi-Fi settings; Wi-Fi is connected");
                waitingForWifi = false;
                setStatus("Wi-Fiに接続しました\nタップして撮影します");
            } else if (waitingForWifi) {
                android.util.Log.i(TAG, "Returned from Wi-Fi settings; Wi-Fi is not connected");
                setStatus(isWifiEnabled()
                        ? "Wi-Fiに未接続です\nタップして設定"
                        : "Wi-Fiはオフです\nタップして設定");
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
        if (!isWifiEnabled() || !isWifiConnected()) {
            recoverWifi();
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            return;
        }
        busy = true;
        awaitingImage = true;
        watchdog.removeCallbacks(captureTimeout);
        watchdog.postDelayed(captureTimeout, 10000);
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
            Integer sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int jpegOrientation = sensorOrientation != null ? sensorOrientation : 270;
            android.util.Log.i(TAG, "Using JPEG orientation " + jpegOrientation);
            android.util.Size photoSize = Arrays.stream(sizes)
                    .filter(s -> s.getWidth() <= 1920)
                    .max(Comparator.comparingInt(s -> s.getWidth() * s.getHeight()))
                    .orElse(sizes[0]);
            imageReader = ImageReader.newInstance(photoSize.getWidth(), photoSize.getHeight(), ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(r -> {
                if (!busy || !awaitingImage) return;
                try (Image image = r.acquireNextImage()) {
                    if (image == null) {
                        done("写真を取得できませんでした");
                        return;
                    }
                    awaitingImage = false;
                    watchdog.removeCallbacks(captureTimeout);
                    java.nio.ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] data = new byte[buffer.remaining()];
                    // Copy the JPEG before releasing the camera resources.
                    buffer.get(data);
                    closeCamera();
                    upload(data);
                } catch (Exception error) {
                    android.util.Log.e(TAG, "Could not read captured photo", error);
                    done("写真を読めませんでした");
                }
            }, cameraHandler);
            // The RV101 needs a short preview phase for automatic exposure to settle.
            previewTexture = new SurfaceTexture(10);
            previewTexture.setDefaultBufferSize(photoSize.getWidth(), photoSize.getHeight());
            previewSurface = new Surface(previewTexture);
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    if (!busy || !awaitingImage) {
                        camera.close();
                        return;
                    }
                    openCamera = camera;
                    try {
                        Surface jpegSurface = imageReader.getSurface();
                        camera.createCaptureSession(Arrays.asList(previewSurface, jpegSurface), new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession session) {
                                if (!busy || !awaitingImage) {
                                    session.close();
                                    return;
                                }
                                try {
                                    captureSession = session;
                                    CaptureRequest.Builder preview = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                    preview.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                                    preview.addTarget(previewSurface);
                                    session.setRepeatingRequest(preview.build(), null, cameraHandler);
                                    cameraHandler.postDelayed(() -> {
                                        if (!busy || !awaitingImage) return;
                                        try {
                                            CaptureRequest.Builder still = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                            still.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                                            still.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);
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
                @Override public void onDisconnected(CameraDevice camera) {
                    openCamera = camera;
                    done("カメラ接続が切れました");
                }
                @Override public void onError(CameraDevice camera, int error) {
                    openCamera = camera;
                    done("カメラエラー " + error);
                }
            }, cameraHandler);
        } catch (Exception error) {
            done("カメラが見つかりませんでした");
        }
    }

    private boolean isWifiEnabled() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        return wifi != null && wifi.isWifiEnabled();
    }

    private boolean isWifiConnected() {
        ConnectivityManager connectivity =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) return false;
        Network network = connectivity.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private void recoverWifi() {
        waitingForWifi = true;
        boolean enabled = isWifiEnabled();
        android.util.Log.i(TAG, "Wi-Fi is not connected; opening Wi-Fi settings");
        setStatus(enabled ? "Wi-Fiに接続します" : "Wi-Fiをオンにします");
        Toast.makeText(
                this,
                enabled ? "接続するWi-Fiを選んでください" : "右つるタップでWi-Fiをオン",
                Toast.LENGTH_LONG
        ).show();
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
        String name = "rokid-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + ".jpg";
        network.execute(() -> {
            String token = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .getString(TOKEN_KEY, "");
            if (!token.matches("[0-9a-f]{32}")) {
                finishUploadFailure(
                        "初期設定が必要です",
                        name,
                        jpeg,
                        new IOException("Pairing token is missing")
                );
                return;
            }
            try {
                String uploadUrl = discoverUploadUrl(token);
                sendPhoto(uploadUrl, name, jpeg, token);
                int resent = sendPendingPhotos(uploadUrl, token);
                boolean pendingRemains = hasPendingPhotos();
                if (resent > 0) {
                    done("3/3\n✓ Macに保存しました\n未送信の写真も"
                            + resent + "枚保存しました");
                } else if (pendingRemains) {
                    done("3/3\n✓ Macに保存しました\n未送信の写真は眼鏡に残っています");
                } else {
                    done("3/3\n✓ Macに保存しました\nタップでもう一枚");
                }
            } catch (ReceiverNotFoundException error) {
                finishUploadFailure(
                        "Macが見つかりません\n同じWi-Fiか確認してください",
                        name,
                        jpeg,
                        error
                );
            } catch (Exception error) {
                finishUploadFailure("Macへ送れませんでした", name, jpeg, error);
            }
        });
    }

    private void sendPhoto(String uploadUrl, String name, byte[] jpeg, String token)
            throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(uploadUrl + "?filename=" + name).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(jpeg.length);
            connection.setRequestProperty("Content-Type", "image/jpeg");
            connection.setRequestProperty("X-Photo-Token", token);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(jpeg);
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("Mac receiver returned HTTP " + code);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void finishUploadFailure(
            String message,
            String name,
            byte[] jpeg,
            Exception error
    ) {
        android.util.Log.e(TAG, "Upload failed", error);
        if (savePendingPhoto(name, jpeg)) {
            done(message + "\n写真は眼鏡に保存しました");
        } else {
            done(message + "\nこの写真は保存されていません");
        }
    }

    private boolean savePendingPhoto(String name, byte[] jpeg) {
        try {
            File pendingDirectory = new File(getFilesDir(), "pending");
            if (!pendingDirectory.exists() && !pendingDirectory.mkdirs()) {
                throw new IOException("Could not create pending photo directory");
            }
            File destination = uniqueFile(pendingDirectory, name);
            File temporary = new File(pendingDirectory, destination.getName() + ".part");
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(jpeg);
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete();
                throw new IOException("Could not finalize pending photo");
            }
            android.util.Log.i(TAG, "Saved pending photo " + destination.getName());
            return true;
        } catch (Exception error) {
            android.util.Log.e(TAG, "Could not save pending photo", error);
            return false;
        }
    }

    private int sendPendingPhotos(String uploadUrl, String token) {
        File pendingDirectory = new File(getFilesDir(), "pending");
        File[] pending = pendingDirectory.listFiles(
                (directory, filename) -> filename.endsWith(".jpg")
        );
        if (pending == null || pending.length == 0) return 0;
        Arrays.sort(pending, Comparator.comparing(File::getName));
        int sent = 0;
        for (File photo : pending) {
            try {
                byte[] jpeg = java.nio.file.Files.readAllBytes(photo.toPath());
                sendPhoto(uploadUrl, photo.getName(), jpeg, token);
                if (!photo.delete()) {
                    android.util.Log.w(TAG, "Could not remove sent pending photo " + photo.getName());
                    break;
                }
                sent++;
            } catch (Exception error) {
                android.util.Log.e(TAG, "Could not resend pending photo " + photo.getName(), error);
                break;
            }
        }
        return sent;
    }

    private boolean hasPendingPhotos() {
        File pendingDirectory = new File(getFilesDir(), "pending");
        File[] pending = pendingDirectory.listFiles(
                (directory, filename) -> filename.endsWith(".jpg")
        );
        return pending != null && pending.length > 0;
    }

    private static File uniqueFile(File directory, String name) {
        File destination = new File(directory, name);
        String stem = name.endsWith(".jpg") ? name.substring(0, name.length() - 4) : name;
        int counter = 2;
        while (destination.exists()) {
            destination = new File(directory, stem + "-" + counter + ".jpg");
            counter++;
        }
        return destination;
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
                    if (interfaceAddress.getNetworkPrefixLength() < 24) {
                        android.util.Log.i(
                                TAG,
                                "Skipping /24 fallback on wider network prefix /"
                                        + interfaceAddress.getNetworkPrefixLength()
                        );
                        continue;
                    }
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
            throw new ReceiverNotFoundException();
        }
    }

    private static final class ReceiverNotFoundException extends IOException {
        ReceiverNotFoundException() {
            super("Mac receiver was not found");
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
        runOnUiThread(() -> {
            if (!busy && !awaitingImage) return;
            watchdog.removeCallbacks(captureTimeout);
            awaitingImage = false;
            closeCameraOnMainThread();
            status.setText(message);
            busy = false;
            stopCameraThread();
        });
    }

    private void closeCamera() {
        runOnUiThread(this::closeCameraOnMainThread);
    }

    private void closeCameraOnMainThread() {
        if (captureSession != null) { captureSession.close(); captureSession = null; }
        if (openCamera != null) { openCamera.close(); openCamera = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
        if (previewTexture != null) { previewTexture.release(); previewTexture = null; }
    }

    private void stopCameraThread() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
    }

    @Override protected void onDestroy() {
        watchdog.removeCallbacks(captureTimeout);
        awaitingImage = false;
        busy = false;
        closeCameraOnMainThread();
        network.shutdownNow();
        stopCameraThread();
        super.onDestroy();
    }
}
