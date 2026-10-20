package local.chowa.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Foreground service hosting {@link HttpServer} on {@link #PORT}. Started from
 * {@link BootReceiver} on boot and from {@link MainActivity}; START_STICKY so the
 * system restarts it after a low-memory kill.
 */
public final class RemoteService extends Service {

    static final int PORT = 8765;
    private static final String TAG = "ChowaRemote";
    private static final String CHANNEL_ID = "remote";
    private static final int NOTIFICATION_ID = 1;
    private static final String ADB_HOST = "127.0.0.1";
    private static final int ADB_PORT = 5555;
    private static final int ADB_TIMEOUT_MS = 15000;
    private static final long BOOT_UPDATE_DELAY_MS = 20000;

    private HttpServer server;
    private Projector projector;

    static void start(Context context) {
        context.startForegroundService(new Intent(context, RemoteService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Control remoto", NotificationManager.IMPORTANCE_MIN));
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_remote)
                .setContentTitle("Control remoto activo")
                .setContentText(url())
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        AdbClient adb = new AdbClient(ADB_HOST, ADB_PORT, ADB_TIMEOUT_MS);
        projector = new Projector(adb);
        String version = appVersion();
        Updater updater = new Updater(adb, version);
        try {
            server = new HttpServer(PORT, readAsset("index.html"), projector, updater, version);
            server.start();
        } catch (IOException e) {
            Log.e(TAG, "cannot load web UI", e);
            stopSelf();
            return;
        }
        autoUpdateInBackground(updater);
    }

    private String appVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    private void autoUpdateInBackground(final Updater updater) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(BOOT_UPDATE_DELAY_MS);
            } catch (InterruptedException e) {
                return;
            }
            Updater.Result r = updater.run();
            Log.i(TAG, "boot update: " + r.message);
        }, "auto-update");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
        }
        if (projector != null) {
            projector.releaseAll();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    static String lanAddress() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot enumerate interfaces", e);
        }
        return null;
    }

    static String url() {
        String ip = lanAddress();
        return ip == null ? "sin red" : "http://" + ip + ":" + PORT;
    }
}
