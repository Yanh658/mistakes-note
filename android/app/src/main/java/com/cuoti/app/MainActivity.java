package com.cuoti.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;

/**
 * 错题集的壳：把打包进 assets 的网页当成本地站点跑。
 * 用 appassets.androidplatform.net 这个 https 域名，IndexedDB / Service Worker 才可用。
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE = 1001;
    private static final int REQ_CAMERA = 1002;
    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraOutputUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            String u = savedInstanceState.getString("cameraOutputUri");
            if (u != null) cameraOutputUri = Uri.parse(u);
        }

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        // 原生 HTTP 通道：网页里用 window.NativeHTTP 判断是否可用
        web.addJavascriptInterface(new NativeHTTP(web), "NativeHTTP");
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String host = u.getHost();
                if (host != null && host.equals("appassets.androidplatform.net")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // 关键：WebView 默认不实现 alert/confirm，confirm 会直接返回 false，
            // 网页里所有「确定要删除吗」都会变成静默取消。
            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("好", (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("确定", (d, w) -> result.confirm())
                        .setNegativeButton("取消", (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;

                // <input capture> 在 WebView 里默认被忽略：params.createIntent()
                // 永远给的是相册的 ACTION_GET_CONTENT，于是「拍照」和「相册」两个按钮
                // 表现一模一样。要真的调起相机，只能自己发 ACTION_IMAGE_CAPTURE，
                // 并给它一个可写的 FileProvider 地址用来存照片。
                if (params.isCaptureEnabled()) {
                    try {
                        File dir = new File(getCacheDir(), "camera");
                        if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdirs 失败");
                        File f = File.createTempFile("shot_", ".jpg", dir);
                        cameraOutputUri = FileProvider.getUriForFile(MainActivity.this,
                                getPackageName() + ".fileprovider", f);
                        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        i.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri);
                        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        // 有些国产 ROM 只认 clipData 里的授权，光加 flag 会写不进去（拍完是 0 字节）
                        i.setClipData(ClipData.newRawUri("", cameraOutputUri));
                        startActivityForResult(i, REQ_CAMERA);
                        return true;
                    } catch (Exception e) {
                        // 没相机 / 没相机 App：退回相册，别让按钮点了没反应
                        cameraOutputUri = null;
                    }
                }

                try {
                    Intent i = params.createIntent();
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(i, REQ_FILE);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
            }
        });

        setContentView(web);
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_CAMERA) {
            Uri[] out = null;
            if (resultCode == RESULT_OK && cameraOutputUri != null) out = new Uri[]{cameraOutputUri};
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(out);
                filePathCallback = null;
            }
            cameraOutputUri = null;
        } else if (requestCode == REQ_FILE) {
            Uri[] out = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    out = new Uri[n];
                    for (int i = 0; i < n; i++) out[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    out = new Uri[]{data.getData()};
                }
            }
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(out);
                filePathCallback = null;
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (cameraOutputUri != null) outState.putString("cameraOutputUri", cameraOutputUri.toString());
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /**
     * 给网页用的原生 HTTP 通道。
     *
     * 网页版要靠 /proxy 转发 AI 请求，因为浏览器有跨域限制；装进 APP 后这层限制没了，
     * 直接在 Java 里发请求就行，用户不用再跑本地服务、也不用依赖别人的 Worker。
     *
     * 用的是「异步 + 回调」而不是同步返回：识图一次可能要几十秒，
     * 同步接口会把 JS 线程卡住，转圈的动画也跟着停，体验很差。
     *
     * JS 侧用法（见 index.html 的 nativePost）：
     *   window.NativeHTTP.post(url, headersJson, body, id)
     *   Java 发完请求会回调 window.__nativeHTTPDone(id, raw)
     *   raw = {"ok":true,"status":200,"body":"..."} 或 {"ok":false,"error":"..."}
     */
    public static class NativeHTTP {

        private final WebView view;

        NativeHTTP(WebView v) {
            this.view = v;
        }

        @JavascriptInterface
        public void post(final String url, final String headersJson, final String body, final String callbackId) {
            final WebView w = view;
            if (w == null) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String result = doPost(url, headersJson, body);
                    final String js = "window.__nativeHTTPDone(" + JSONObject.quote(callbackId)
                            + "," + JSONObject.quote(result) + ")";
                    w.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                w.evaluateJavascript(js, null);
                            } catch (Exception ignored) {
                                // 页面已经退了，回调丢了就算了
                            }
                        }
                    });
                }
            }, "native-http").start();
        }

        private static String doPost(String url, String headersJson, String body) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(180000);   // 识图 + 长题解，给足时间
                conn.setDoOutput(true);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                if (headersJson != null && headersJson.length() > 0) {
                    JSONObject h = new JSONObject(headersJson);
                    Iterator<String> it = h.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        try {
                            conn.setRequestProperty(k, h.optString(k, ""));
                        } catch (Exception ignored) {
                            // 个别 header 被平台禁止设置，跳过即可
                        }
                    }
                }
                byte[] payload = (body == null ? "" : body).getBytes("UTF-8");
                conn.setFixedLengthStreamingMode(payload.length);
                OutputStream os = conn.getOutputStream();
                os.write(payload);
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
                String text = readAll(is);

                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("status", code);
                out.put("body", text);
                return out.toString();
            } catch (Exception e) {
                try {
                    JSONObject out = new JSONObject();
                    out.put("ok", false);
                    out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                    return out.toString();
                } catch (Exception ignored) {
                    return "{\"ok\":false,\"error\":\"unknown\"}";
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        private static String readAll(InputStream is) throws IOException {
            if (is == null) return "";
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            return new String(bo.toByteArray(), "UTF-8");
        }
    }
}
