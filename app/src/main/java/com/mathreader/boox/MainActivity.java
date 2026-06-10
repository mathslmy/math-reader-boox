package com.mathreader.boox;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    // https 同源加载，localStorage / IndexedDB / ServiceWorker 才能正常持久化
    private static final String START_URL = "https://appassets.androidplatform.net/www/index.html";
    private static final int REQUEST_FILE_CHOOSER = 1001;

    private WebView webView;
    private BooxPenBridge penBridge;
    private DownloadBridge downloadBridge;
    private ValueCallback<Uri[]> filePathCallback;
    private String adapterJs;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        // 系统字体缩放会破坏 PWA 布局
        settings.setTextZoom(100);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("appassets.androidplatform.net".equals(uri.getHost())) {
                    return false;
                }
                // 站外链接交给系统浏览器
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) {
                    Log.w(TAG, "open external url failed: " + uri, e);
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectAdapter();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQUEST_FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        downloadBridge = new DownloadBridge(this);
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url.startsWith("data:")) {
                downloadBridge.saveDataUrl(url);
            } else if (url.startsWith("http://") || url.startsWith("https://")) {
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimetype);
                    request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                            URLUtil.guessFileName(url, contentDisposition, mimetype));
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    dm.enqueue(request);
                    Toast.makeText(this, "开始下载", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.w(TAG, "download failed: " + url, e);
                }
            }
            // blob: URL 由 boox-pen.js 拦截走 DownloadBridge.saveBase64
        });

        penBridge = new BooxPenBridge(this, webView);
        webView.addJavascriptInterface(penBridge, "BooxPenNative");
        webView.addJavascriptInterface(downloadBridge, "BooxDownloadNative");

        webView.loadUrl(START_URL);
    }

    private void injectAdapter() {
        if (adapterJs == null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    getAssets().open("boox-pen.js"), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                adapterJs = sb.toString();
            } catch (Exception e) {
                Log.e(TAG, "read boox-pen.js failed", e);
                return;
            }
        }
        webView.evaluateJavascript(adapterJs, null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE_CHOOSER && filePathCallback != null) {
            filePathCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (penBridge != null) {
            penBridge.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (penBridge != null) {
            penBridge.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (penBridge != null) {
            penBridge.onDestroy();
        }
        super.onDestroy();
    }
}
