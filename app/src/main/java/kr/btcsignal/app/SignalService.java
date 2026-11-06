package kr.btcsignal.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * 앱을 닫거나 화면이 꺼져도 5분봉 신호를 감시하는 포그라운드 서비스.
 * 5초마다 업비트 5분봉(진행 중인 봉 포함)을 받아 스토캐스틱 슬로우 3종을 계산하고,
 * 조건이 맞으면 봉당 1회 알림을 보낸다.
 *
 * 매수: S(5,3,3) %K가 20 이하에서 %D 상향돌파 + S(10,3,3)·S(20,6,6) %K 30 이하
 * 매도: S(5,3,3) %K가 80 이상에서 %D 하향돌파 + S(10,3,3)·S(20,6,6) %K 70 이상
 */
public class SignalService extends Service {

    static final String PREFS = "btc_signal";
    static final String CH_RUN = "run";
    static final String CH_SIG = "signal_v1";
    static final String ACTION_STOP = "kr.btcsignal.app.STOP";
    static final int ID_RUN = 1;
    static final long POLL_MS = 5000;
    static volatile boolean running = false;

    private static final String URL_5M =
            "https://api.upbit.com/v1/candles/minutes/5?market=KRW-BTC&count=80";
    private static final int[][] STOCHS = {{5, 3, 3}, {10, 3, 3}, {20, 6, 6}};

    private HandlerThread thread;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private int failCount = 0;

    static void start(Context ctx) {
        Intent i = new Intent(ctx, SignalService.class);
        ctx.startForegroundService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels(this);
        thread = new HandlerThread("btc-poll");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("on", false).apply();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(ID_RUN, ongoing("5분봉 신호 감시 시작…"));
        running = true;
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "btcsignal:poll");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        }
        handler.removeCallbacks(poll);
        handler.post(poll);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            try {
                check();
                failCount = 0;
            } catch (Exception e) {
                failCount++;
                updateOngoing("연결 재시도 중 (" + failCount + "회)…");
            } finally {
                if (running) handler.postDelayed(this, failCount > 3 ? 15000 : POLL_MS);
            }
        }
    };

    private void check() throws Exception {
        JSONArray arr = new JSONArray(httpGet(URL_5M));
        int n = arr.length();
        if (n < 32) return;
        double[] hi = new double[n], lo = new double[n], cl = new double[n];
        String[] times = new String[n];
        for (int i = 0; i < n; i++) {
            JSONObject c = arr.getJSONObject(n - 1 - i);   // 응답은 최신순 → 시간순으로 뒤집기
            hi[i] = c.getDouble("high_price");
            lo[i] = c.getDouble("low_price");
            cl[i] = c.getDouble("trade_price");
            times[i] = c.getString("candle_date_time_kst");
        }
        double[][] k = new double[3][], d = new double[3][];
        for (int j = 0; j < 3; j++) {
            double[] fast = fastK(hi, lo, cl, STOCHS[j][0]);
            k[j] = sma(fast, STOCHS[j][1]);
            d[j] = sma(k[j], STOCHS[j][2]);
        }
        int i = n - 1;
        double price = cl[i];
        String dir = null;
        double K = k[0][i], D = d[0][i], pK = k[0][i - 1], pD = d[0][i - 1], K2 = k[1][i], K3 = k[2][i];
        if (!Double.isNaN(K + D + pK + pD + K2 + K3)) {
            if (pK <= pD && K > D && pK <= 20 && K2 <= 30 && K3 <= 30) dir = "buy";
            else if (pK >= pD && K < D && pK >= 80 && K2 >= 70 && K3 >= 70) dir = "sell";
        }

        String bar = times[i].length() >= 16 ? times[i].substring(11, 16) : times[i];
        updateOngoing(won(price) + "원 · " + bar + "봉 · S5 " + f1(K) + " · S10 " + f1(K2) + " · S20 " + f1(K3));

        if (dir != null) {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            String key = "fired_" + dir;
            if (!times[i].equals(p.getString(key, ""))) {      // 같은 봉에서는 한 번만
                p.edit().putString(key, times[i]).apply();
                showSignal(this, dir, price, bar + " 5분봉 · S(5,3,3) " + f1(K) + "/" + f1(D));
            }
        }
    }

    /* ---------- 지표 ---------- */
    static double[] fastK(double[] hi, double[] lo, double[] cl, int n) {
        double[] out = new double[cl.length];
        for (int i = 0; i < cl.length; i++) {
            if (i < n - 1) { out[i] = Double.NaN; continue; }
            double hh = Double.NEGATIVE_INFINITY, ll = Double.POSITIVE_INFINITY;
            for (int j = i - n + 1; j <= i; j++) {
                if (hi[j] > hh) hh = hi[j];
                if (lo[j] < ll) ll = lo[j];
            }
            out[i] = hh == ll ? 50 : (cl[i] - ll) / (hh - ll) * 100;
        }
        return out;
    }

    static double[] sma(double[] a, int n) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = Double.NaN;
            if (i < n - 1) continue;
            double sum = 0;
            boolean ok = true;
            for (int j = i - n + 1; j <= i; j++) {
                if (Double.isNaN(a[j])) { ok = false; break; }
                sum += a[j];
            }
            if (ok) out[i] = sum / n;
        }
        return out;
    }

    /* ---------- 네트워크 ---------- */
    private static String httpGet(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    /* ---------- 알림 ---------- */
    static void createChannels(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel run = new NotificationChannel(CH_RUN, "감시 상태", NotificationManager.IMPORTANCE_LOW);
        run.setDescription("백그라운드에서 신호를 감시하는 동안 표시");
        run.setShowBadge(false);
        nm.createNotificationChannel(run);

        NotificationChannel sig = new NotificationChannel(CH_SIG, "매수·매도 신호", NotificationManager.IMPORTANCE_HIGH);
        sig.setDescription("5분봉 스토캐스틱 3종 합의 신호");
        sig.enableVibration(true);
        sig.setVibrationPattern(new long[]{0, 500, 200, 500, 200, 500});
        sig.enableLights(true);
        sig.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        sig.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(sig);
    }

    static void showSignal(Context ctx, String dir, double price, String detail) {
        createChannels(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        boolean buy = "buy".equals(dir);
        String title = (buy ? "▲ BTC 매수 신호" : "▼ BTC 매도 신호") + (price > 0 ? " · " + won(price) + "원" : "");
        Notification n = new Notification.Builder(ctx, CH_SIG)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(detail)
                .setColor(buy ? 0xFFD6283A : 0xFF1F5FD6)
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setContentIntent(openApp(ctx))
                .build();
        nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), n);
    }

    private Notification ongoing(String text) {
        Intent stop = new Intent(this, SignalService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CH_RUN)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("BTC 5분봉 신호 감시 중")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(this))
                .addAction(new Notification.Action.Builder(null, "감시 중지", stopPi).build())
                .build();
    }

    private void updateOngoing(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && running) nm.notify(ID_RUN, ongoing(text));
    }

    private static PendingIntent openApp(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static String won(double v) {
        return NumberFormat.getIntegerInstance(Locale.KOREA).format(Math.round(v));
    }

    static String f1(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.US, "%.1f", v);
    }
}
