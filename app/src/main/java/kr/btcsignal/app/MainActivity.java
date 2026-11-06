package kr.btcsignal.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** 차트 화면(WebView) + 백그라운드 알림 서비스를 켜고 끄는 다리 역할 */
public class MainActivity extends Activity {

    private WebView web;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);   // 업비트 시세 API 호출 허용
        s.setMediaPlaybackRequiresUserGesture(false);

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
        web.loadUrl("file:///android_asset/index.html");

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        // 이전에 알림을 켜 두었다면 앱을 열 때 서비스도 다시 시작
        if (getSharedPreferences(SignalService.PREFS, MODE_PRIVATE).getBoolean("on", false) && !SignalService.running) {
            SignalService.start(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);   // 뒤로가기로 앱이 꺼지지 않게 (서비스는 계속 동작)
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** 웹 화면의 JavaScript 에서 window.AndroidBridge 로 호출 */
    public class Bridge {
        @JavascriptInterface
        public void start() {
            getSharedPreferences(SignalService.PREFS, MODE_PRIVATE).edit().putBoolean("on", true).apply();
            SignalService.start(MainActivity.this);
        }

        @JavascriptInterface
        public void stop() {
            getSharedPreferences(SignalService.PREFS, MODE_PRIVATE).edit().putBoolean("on", false).apply();
            stopService(new Intent(MainActivity.this, SignalService.class));
        }

        @JavascriptInterface
        public boolean isOn() {
            return SignalService.running
                    || getSharedPreferences(SignalService.PREFS, MODE_PRIVATE).getBoolean("on", false);
        }

        @JavascriptInterface
        public void testNotify() {
            SignalService.showSignal(MainActivity.this, "buy", 0, "알림 시험");
        }

        @JavascriptInterface
        public void batteryWhitelist() {
            runOnUiThread(() -> {
                try {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    Intent i;
                    if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                        i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName()));
                    } else {
                        i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                    }
                    startActivity(i);
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) { }
                }
            });
        }
    }
}
