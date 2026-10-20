package local.chowa.remote;

import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Self-updater: compares the installed version with the latest GitHub release and,
 * when a newer APK is published, downloads it and installs it through the local
 * adbd ({@code pm install -r}), reusing the same shell transport as the control path.
 *
 * <p>The replacement APK must be signed with the same key as the installed app, so
 * the GitHub Actions build needs the {@code KEYSTORE_BASE64} secret configured.
 * After {@code pm install -r} the system delivers MY_PACKAGE_REPLACED, which
 * {@link BootReceiver} uses to restart {@link RemoteService}.
 */
final class Updater {

    static final String REPO = "aguara-guazu/-chowa-p8-virtual-remote";
    private static final String LATEST_URL =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String TAG = "ChowaRemote";
    private static final String UPDATE_FILE = "chowa-remote-update.apk";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_APK_BYTES = 64 * 1024 * 1024;

    private final AdbClient adb;
    private final String currentVersion;

    Updater(AdbClient adb, String currentVersion) {
        this.adb = adb;
        this.currentVersion = currentVersion == null ? "0" : currentVersion;
    }

    static final class Result {
        boolean updateAvailable;
        boolean installed;
        String currentVersion;
        String latestVersion;
        String message;

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("current", currentVersion)
                    .put("latest", latestVersion == null ? JSONObject.NULL : latestVersion)
                    .put("updateAvailable", updateAvailable)
                    .put("installed", installed)
                    .put("message", message == null ? JSONObject.NULL : message);
        }
    }

    /** Queries the latest release without downloading anything. */
    Result check() {
        Result r = new Result();
        r.currentVersion = currentVersion;
        try {
            JSONObject release = new JSONObject(httpGet(LATEST_URL));
            String tag = release.optString("tag_name", "");
            r.latestVersion = normalize(tag);
            r.updateAvailable = compare(r.latestVersion, normalize(currentVersion)) > 0;
            r.message = r.updateAvailable ? "Hay una versión nueva disponible." : "Ya estás en la última versión.";
        } catch (IOException | JSONException e) {
            r.message = "No se pudo consultar actualizaciones: " + e.getMessage();
            Log.w(TAG, "update check failed", e);
        }
        return r;
    }

    /** Checks, and if a newer release exists, downloads and installs it. */
    Result run() {
        Result r = new Result();
        r.currentVersion = currentVersion;
        try {
            JSONObject release = new JSONObject(httpGet(LATEST_URL));
            r.latestVersion = normalize(release.optString("tag_name", ""));
            if (compare(r.latestVersion, normalize(currentVersion)) <= 0) {
                r.message = "Ya estás en la última versión.";
                return r;
            }
            r.updateAvailable = true;

            String assetUrl = apkAssetUrl(release);
            if (assetUrl == null) {
                r.message = "El release no tiene un APK adjunto.";
                return r;
            }

            File target = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    UPDATE_FILE);
            ensureStoragePermission();
            long bytes = download(assetUrl, target);
            Log.i(TAG, "downloaded " + bytes + " bytes to " + target);

            String out = adb.shell("pm install -r " + shellQuote(target.getAbsolutePath()), 120000);
            if (out.contains("Success")) {
                r.installed = true;
                r.message = "Actualizado a " + r.latestVersion + ". La app se reinicia.";
            } else {
                r.message = "La instalación falló: " + out.trim();
            }
        } catch (IOException | JSONException e) {
            r.message = "Actualización fallida: " + e.getMessage();
            Log.w(TAG, "update failed", e);
        }
        return r;
    }

    private void ensureStoragePermission() {
        try {
            adb.shell("pm grant local.chowa.remote android.permission.WRITE_EXTERNAL_STORAGE");
        } catch (IOException ignored) {
        }
    }

    private static String apkAssetUrl(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && asset.optString("name", "").endsWith(".apk")) {
                return asset.optString("browser_download_url", null);
            }
        }
        return null;
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection conn = open(url);
        try (InputStream in = conn.getInputStream()) {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " en " + url);
            }
            return readAll(in);
        } finally {
            conn.disconnect();
        }
    }

    private long download(String url, File target) throws IOException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection conn = open(current);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) {
                    throw new IOException("redirección sin destino");
                }
                current = location;
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                throw new IOException("HTTP " + code + " al descargar");
            }
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_APK_BYTES) {
                        throw new IOException("el APK excede el tamaño máximo");
                    }
                    out.write(buf, 0, n);
                }
                return total;
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("demasiadas redirecciones");
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "chowa-remote-updater");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        return conn;
    }

    private static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Strips a leading "v" and keeps the dotted-number core of a tag. */
    static String normalize(String version) {
        String v = version == null ? "" : version.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        return v;
    }

    /** Compares dotted-integer versions; non-numeric parts fall back to string order. */
    static int compare(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            long va = part(pa, i);
            long vb = part(pb, i);
            if (va != vb) {
                return Long.compare(va, vb);
            }
        }
        return 0;
    }

    private static long part(String[] parts, int i) {
        if (i >= parts.length) {
            return 0;
        }
        try {
            return Long.parseLong(parts[i].replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
