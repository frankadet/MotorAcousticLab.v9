package es.motoracousticlab.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Envoltorio WebView de Motor Acoustic Lab.
 * La app web vive en assets/index.html y se sirve desde https://appassets.androidplatform.net
 * (contexto seguro: necesario para el micrófono y el AudioWorklet).
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_MIC = 1;

    private WebView web;
    private PermissionRequest pendingRequest;

    // Voz nativa (el WebView no suele traer síntesis de voz propia)
    private TextToSpeech tts;
    private boolean ttsReady = false, ttsFailed = false;
    private String pendingText = null, curUtt = "";
    private int uttCounter = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // La pantalla no se apaga durante la prueba
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(0xFFEEF3F9);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

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
                    startActivity(new Intent(Intent.ACTION_VIEW, u));   // enlaces externos: en el navegador
                } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> handlePermission(request));
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingRequest == request) pendingRequest = null;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("Aceptar", (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("Aceptar", (d, w) -> result.confirm())
                        .setNegativeButton("Cancelar", (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }
        });

        initTts();
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.loadUrl("https://" + HOST + "/assets/index.html");
    }

    private void js(final String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    private void ttsFail() {
        pendingText = null;
        js("window.__ttsFail&&window.__ttsFail()");
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) {
                ttsFailed = true;
                if (pendingText != null) ttsFail();
                return;
            }
            int r = tts.setLanguage(new Locale("es", "ES"));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                r = tts.setLanguage(new Locale("es"));
            }
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                ttsFailed = true;   // sin voz en español: la página sigue sin voz
                if (pendingText != null) ttsFail();
                return;
            }
            tts.setSpeechRate(0.92f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) {
                    if (id != null && id.equals(curUtt)) js("window.__ttsEnd&&window.__ttsEnd()");
                }
                @Override public void onError(String id) {
                    if (id != null && id.equals(curUtt)) js("window.__ttsFail&&window.__ttsFail()");
                }
            });
            ttsReady = true;
            if (pendingText != null) {
                String t = pendingText;
                pendingText = null;
                speakNow(t);
            }
        });
    }

    private void speakNow(String text) {
        curUtt = "u" + (++uttCounter);
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), curUtt);
        if (r != TextToSpeech.SUCCESS) ttsFail();
    }

    /** Concede solo el micrófono, y solo a la propia app. */
    private void handlePermission(PermissionRequest request) {
        boolean wantsAudio = false;
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsAudio = true;
        }
        if (!wantsAudio || !request.getOrigin().toString().contains(HOST)) {
            request.deny();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            if (pendingRequest != null) pendingRequest.deny();
            pendingRequest = request;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != REQ_MIC || pendingRequest == null) return;
        PermissionRequest req = pendingRequest;
        pendingRequest = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            req.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            req.deny();
            Toast.makeText(this, "Sin permiso de micrófono la prueba no puede funcionar.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        // El botón Atrás pide confirmación a través del propio programa (misma lógica que «Salir»)
        web.evaluateJavascript("if(typeof exitProgram==='function'){exitProgram()}", null);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (web != null) {
            web.removeJavascriptInterface("AndroidBridge");
            web.destroy();
        }
        super.onDestroy();
    }

    /** Puente para guardar archivos y cerrar la app desde la página. */
    private class Bridge {
        @JavascriptInterface
        public void speak(final String text) {
            runOnUiThread(() -> {
                if (ttsFailed || tts == null) { ttsFail(); return; }
                if (!ttsReady) { pendingText = text; return; }
                speakNow(text);
            });
        }

        @JavascriptInterface
        public void stopSpeak() {
            runOnUiThread(() -> {
                pendingText = null;
                curUtt = "";
                if (tts != null && ttsReady) tts.stop();
            });
        }

        @JavascriptInterface
        public String getVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(() -> {
                // borra todo lo que la página haya guardado en el móvil antes de cerrar
                try {
                    web.clearCache(true);
                    web.clearHistory();
                    WebStorage.getInstance().deleteAllData();
                } catch (Exception ignored) { }
                finishAndRemoveTask();
            });
        }

        @JavascriptInterface
        public void saveFile(String base64, String name, String mime) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
                String where = save(data, safe, mime);
                toast("Guardado en " + where);
            } catch (Exception e) {
                toast("No se pudo guardar el archivo: " + e.getMessage());
            }
        }
    }

    private String save(byte[] data, String name, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MotorAcousticLab");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("sin acceso a Descargas");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("no se pudo abrir el archivo");
                out.write(data);
            }
            return "Descargas/MotorAcousticLab/" + name;
        } else {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) throw new Exception("almacenamiento no disponible");
            File f = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(data);
            }
            return f.getAbsolutePath();
        }
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }
}
