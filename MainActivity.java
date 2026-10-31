package com.threefmeatshop.kitchenbudget;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;

/** Home Kitchen Budget: the offline web app inside a WebView, with saving and sharing to Android. */
public class MainActivity extends Activity {

    private static final int PICK_FILE = 1001;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#2563eb"));

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // keeps budgets, expenses and menus on the phone
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(pick, "Choose backup file"), PICK_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_FILE && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{ data.getData() };
            fileCallback.onReceiveValue(result);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        // Back closes an open window in the app first; otherwise it leaves the app.
        web.evaluateJavascript(
            "(function(){var d=document.querySelector('dialog[open]');if(d){d.close();return 'closed';}return 'none';})()",
            value -> { if (value == null || value.contains("none")) finish(); });
    }

    private Uri saveToDownloads(String name, byte[] bytes, String mime) throws Exception {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/KitchenBudget");
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new Exception("storage not available");
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new Exception("cannot write file");
            os.write(bytes);
        }
        return uri;
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    /** Functions the web app can call: window.AndroidBridge.* */
    private class Bridge {
        @JavascriptInterface
        public void saveFile(String name, String base64, String mime) {
            try {
                saveToDownloads(name, Base64.decode(base64, Base64.DEFAULT), mime);
                toast("Saved to Downloads/KitchenBudget: " + name);
            } catch (Exception e) {
                toast("Could not save: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void shareFile(String name, String base64, String mime) {
            try {
                final Uri uri = saveToDownloads(name, Base64.decode(base64, Base64.DEFAULT), mime);
                final Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(send, "Send to the cook")));
            } catch (Exception e) {
                toast("Could not share: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void copyText(final String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Menu", text));
                Toast.makeText(MainActivity.this, "Copied – paste it in Messenger or Viber", Toast.LENGTH_SHORT).show();
            });
        }
    }
}
