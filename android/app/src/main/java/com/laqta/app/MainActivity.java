package com.laqta.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int FILE_REQ = 1, PERM_REQ = 2;
    private static final String HOST = "appassets.androidplatform.net";
    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private PermissionRequest pendingPerm;

    // In-app updates: download the new APK with DownloadManager, then hand it to the system installer.
    private long dlId = -1;
    private File pendingApk;
    private boolean waitingInstallPerm = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver dlDone = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id == dlId && id != -1) {
                dlId = -1;
                installApk();
            }
        }
    };

    private void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    private void installApk() {
        if (pendingApk == null || !pendingApk.exists() || pendingApk.length() == 0) {
            js("window.laqtaUpdate&&laqtaUpdate('fail')");
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            waitingInstallPerm = true;
            js("window.laqtaUpdate&&laqtaUpdate('perm')");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        Uri u = FileProvider.getUriForFile(this, getPackageName() + ".files", pendingApk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(u, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        js("window.laqtaUpdate&&laqtaUpdate('install')");
    }

    private void pollDownload() {
        handler.postDelayed(() -> {
            if (dlId < 0) return;
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(dlId))) {
                if (c != null && c.moveToFirst()) {
                    long so = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long tot = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (st == DownloadManager.STATUS_FAILED) {
                        dlId = -1;
                        js("window.laqtaUpdate&&laqtaUpdate('fail')");
                        return;
                    }
                    if (tot > 0) js("window.laqtaUpdate&&laqtaUpdate('progress'," + (so * 100 / tot) + ")");
                }
            } catch (Exception ignored) { }
            pollDownload();
        }, 500);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingInstallPerm && getPackageManager().canRequestPackageInstalls()) {
            waitingInstallPerm = false;
            installApk();
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(dlDone); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);

        // Serve the bundled page from an https origin so IndexedDB, media capture and
        // the microphone behave like they do on the website.
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                ArrayList<String> mimes = new ArrayList<>();
                boolean byExtension = false;
                for (String a : p.getAcceptTypes()) {
                    for (String t : a.split(",")) {
                        t = t.trim();
                        if (t.startsWith(".")) byExtension = true;
                        else if (t.contains("/")) mimes.add(t);
                    }
                }
                // Font files rarely carry a reliable MIME type on Android, so show every file then.
                if (!byExtension && !mimes.isEmpty()) i.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "اختر ملف"), FILE_REQ);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest r) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        r.grant(r.getResources());
                    } else {
                        pendingPerm = r;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERM_REQ);
                    }
                });
            }
        });

        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(dlDone, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(dlDone, f);

        web.addJavascriptInterface(new Bridge(), "LaqtaAndroid");
        if (state != null) web.restoreState(state);
        else web.loadUrl("https://" + HOST + "/assets/index.html");
    }

    @Override
    protected void onActivityResult(int req, int code, Intent data) {
        super.onActivityResult(req, code, data);
        if (req != FILE_REQ || fileCb == null) return;
        Uri[] res = null;
        if (code == RESULT_OK && data != null) {
            ClipData c = data.getClipData();
            if (c != null) {
                res = new Uri[c.getItemCount()];
                for (int k = 0; k < c.getItemCount(); k++) res[k] = c.getItemAt(k).getUri();
            } else if (data.getData() != null) {
                res = new Uri[]{data.getData()};
            }
        }
        fileCb.onReceiveValue(res);
        fileCb = null;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req != PERM_REQ || pendingPerm == null) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) pendingPerm.grant(pendingPerm.getResources());
        else pendingPerm.deny();
        pendingPerm = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    /** Lets the page save an exported video to the gallery (Movies/Laqta) in chunks. */
    private class Bridge {
        private OutputStream out;

        @JavascriptInterface
        public int versionCode() {
            try {
                return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
            } catch (Exception e) {
                return 0;
            }
        }

        @JavascriptInterface
        public void downloadUpdate(String url) {
            runOnUiThread(() -> {
                try {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    pendingApk = new File(dir, "laqta-update.apk");
                    if (pendingApk.exists()) pendingApk.delete();
                    DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                    r.setTitle("تحديث لقطة");
                    r.setMimeType("application/vnd.android.package-archive");
                    r.setDestinationUri(Uri.fromFile(pendingApk));
                    r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    dlId = dm.enqueue(r);
                    pollDownload();
                } catch (Exception e) {
                    js("window.laqtaUpdate&&laqtaUpdate('fail')");
                }
            });
        }

        @JavascriptInterface
        public boolean begin(String name, String mime) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Laqta");
                Uri uri = getContentResolver().insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                out = getContentResolver().openOutputStream(uri);
                return out != null;
            } catch (Exception e) {
                return false;
            }
        }

        /** Same as begin(), but into Download/Laqta (used for project backups). */
        @JavascriptInterface
        public boolean beginDownload(String name, String mime) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Laqta");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                out = getContentResolver().openOutputStream(uri);
                return out != null;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean chunk(String b64) {
            try {
                out.write(Base64.decode(b64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean end() {
            try {
                out.close();
                out = null;
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "تم الحفظ ✓", Toast.LENGTH_SHORT).show());
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
