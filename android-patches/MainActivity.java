package com.ddms.ratio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebChromeClient;

import java.io.OutputStream;

/**
 * The web app scans QR/barcodes and takes photos using getUserMedia()
 * directly in the WebView (via the html5-qrcode library and plain
 * MediaDevices calls) — it does not use a native Capacitor camera plugin.
 *
 * Android's WebView denies live camera (getUserMedia) access to web content
 * unless the host app explicitly grants it, so this activity:
 *  1. requests the runtime CAMERA permission on first launch, and
 *  2. grants the WebView's own camera permission requests — but ONLY once the
 *     real OS-level CAMERA permission is actually held. Granting the WebView
 *     request while the OS permission is still pending/denied crashes the
 *     render process on many devices, which looks like "nothing happens" when
 *     the scan or photo button is tapped.
 *
 * It also exposes a small JS interface ("AndroidSaveFile") so the web app's
 * Excel export can trigger Android's real, native "Save As" dialog (the
 * Storage Access Framework document picker) — the same system dialog any
 * app uses to let the user choose exactly where a file is saved (Downloads,
 * Google Drive, an SD card, etc.) and under what name. Plain <a download>
 * links and silent writes to app-private storage don't give the user that
 * choice, which is why this bridge exists.
 *
 * IMPORTANT: we extend Capacitor's own BridgeWebChromeClient (instead of the
 * plain android.webkit.WebChromeClient) and only override onPermissionRequest.
 * BridgeWebChromeClient already implements everything else Capacitor needs,
 * so nothing else in the app (plugins, file choosers, etc.) is affected.
 */
public class MainActivity extends BridgeActivity {

    private static final String TAG = "DdmsRatioSaveFile";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1001;
    private static final int SAVE_FILE_REQUEST_CODE = 2001;

    // Holds the pending export between launching the system "Save As" dialog
    // and receiving the user's chosen destination in onActivityResult.
    private byte[] pendingSaveBytes;
    private EmbeddedSyncServer embeddedSyncServer;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST_CODE
            );
        }

        WebView webView = this.bridge.getWebView();
        embeddedSyncServer = new EmbeddedSyncServer(this);

        webView.setWebChromeClient(new BridgeWebChromeClient(this.bridge) {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean wantsVideo = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                            wantsVideo = true;
                        }
                    }
                    if (!wantsVideo) {
                        request.deny();
                        return;
                    }
                    if (hasCameraPermission()) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        // Don't grant without the real OS permission — that can crash the
                        // WebView's render process. Deny this attempt and ask the user
                        // for the permission again so the next tap succeeds.
                        request.deny();
                        ActivityCompat.requestPermissions(
                                MainActivity.this,
                                new String[]{Manifest.permission.CAMERA},
                                CAMERA_PERMISSION_REQUEST_CODE
                        );
                    }
                });
            }
        });

        webView.addJavascriptInterface(new SaveFileInterface(), "AndroidSaveFile");
        webView.addJavascriptInterface(new SyncServerInterface(), "AndroidSyncServer");
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private class SyncServerInterface {
        @JavascriptInterface
        public String startServer() {
            try { return embeddedSyncServer.start(); }
            catch (Exception e) { Log.e(TAG, "Nie można uruchomić serwera synchronizacji", e); return ""; }
        }

        @JavascriptInterface
        public void stopServer() { embeddedSyncServer.stop(); }

        @JavascriptInterface
        public boolean isRunning() { return embeddedSyncServer.isRunning(); }

        @JavascriptInterface
        public String getAddress() { return embeddedSyncServer.getAddress(); }
    }

    /**
     * JS-facing bridge: window.AndroidSaveFile.saveFile(base64Data, filename, mimeType)
     * Opens Android's native "Save As" document picker. The actual write to
     * the chosen location happens in onActivityResult once the user confirms.
     */
    private class SaveFileInterface {
        @JavascriptInterface
        public void saveFile(final String base64Data, final String filename, final String mimeType) {
            try {
                pendingSaveBytes = Base64.decode(base64Data, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid base64 payload for export", e);
                notifyJsFailure("invalid_data");
                return;
            }

            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(mimeType != null ? mimeType
                        : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                intent.putExtra(Intent.EXTRA_TITLE,
                        filename != null ? filename : "DDMS_Ratio_Export.xlsx");
                try {
                    startActivityForResult(intent, SAVE_FILE_REQUEST_CODE);
                } catch (Exception e) {
                    Log.e(TAG, "No activity available to handle ACTION_CREATE_DOCUMENT", e);
                    notifyJsFailure("no_picker_available");
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != SAVE_FILE_REQUEST_CODE) {
            return;
        }

        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            pendingSaveBytes = null;
            notifyJsFailure("cancelled");
            return;
        }

        Uri targetUri = data.getData();

        if (pendingSaveBytes == null) {
            notifyJsFailure("no_pending_data");
            return;
        }

        try (OutputStream out = getContentResolver().openOutputStream(targetUri)) {
            if (out == null) {
                notifyJsFailure("could_not_open_output_stream");
                return;
            }
            out.write(pendingSaveBytes);
            out.flush();
            notifyJsSuccess(targetUri.toString());
            runOnUiThread(() -> Toast.makeText(this, "Plik zapisany", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            Log.e(TAG, "Failed writing export to chosen location", e);
            notifyJsFailure("write_failed");
        } finally {
            pendingSaveBytes = null;
        }
    }

    private void notifyJsSuccess(String uriString) {
        final WebView webView = this.bridge.getWebView();
        final String safeUri = uriString.replace("\\", "\\\\").replace("'", "\\'");
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.__ddmsSaveFileResolve && window.__ddmsSaveFileResolve('" + safeUri + "');",
                null
        ));
    }

    private void notifyJsFailure(String reason) {
        final WebView webView = this.bridge.getWebView();
        final String safeReason = reason.replace("\\", "\\\\").replace("'", "\\'");
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.__ddmsSaveFileReject && window.__ddmsSaveFileReject('" + safeReason + "');",
                null
        ));
    }
}
