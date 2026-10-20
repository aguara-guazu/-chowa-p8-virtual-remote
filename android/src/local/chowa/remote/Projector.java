package local.chowa.remote;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Projector actions executed as the adb shell user through {@link AdbClient}.
 *
 * <p>Keys are injected as raw EV_KEY events on the IR receiver input device so the
 * vendor focus overlay (com.rjpj.function) receives real key down/up pairs. Held
 * keys carry a watchdog that releases them after {@link #HOLD_LIMIT_MS} in case the
 * client never sends the release.
 */
final class Projector {

    static final String IR_DEVICE = "/dev/input/event0";
    static final long HOLD_LIMIT_MS = 8000;

    private static final String FOCUS_SHOW_ACTION = "com.rjpj.intent.action.focus.show";
    private static final String FOCUS_WINDOW_OWNER = "com.rjpj.function";
    private static final long FOCUS_SHOW_DELAY_MS = 800;
    private static final int MEDIA_STREAM_MUSIC = 3;

    private static final Pattern VOLUME_RE = Pattern.compile("volume is (\\d+) in range \\[(\\d+)\\.\\.(\\d+)\\]");
    private static final Pattern WINDOW_PKG_RE = Pattern.compile("\\su0\\s([\\w.]+)");
    private static final Pattern COMPONENT_RE = Pattern.compile("^[\\w.]+/[\\w.$]+$");

    static final Map<String, Integer> SCANCODES = new HashMap<>();
    static final Map<String, Integer> FOCUS_SCANCODES = new HashMap<>();
    static final Map<String, Integer> TEXT_KEYCODES = new HashMap<>();
    static final Map<String, String> SCREENS = new HashMap<>();
    static final Map<String, String> APP_NAMES = new HashMap<>();

    static {
        SCANCODES.put("up", 103);
        SCANCODES.put("down", 108);
        SCANCODES.put("left", 105);
        SCANCODES.put("right", 106);
        SCANCODES.put("ok", 28);
        SCANCODES.put("back", 158);
        SCANCODES.put("home", 102);
        SCANCODES.put("menu", 139);
        SCANCODES.put("settings", 249);
        SCANCODES.put("vol_up", 115);
        SCANCODES.put("vol_down", 114);
        SCANCODES.put("mute", 113);
        SCANCODES.put("power", 116);
        SCANCODES.put("input", 79);
        SCANCODES.put("play_pause", 164);
        SCANCODES.put("rewind", 168);
        SCANCODES.put("forward", 159);
        SCANCODES.put("prev", 165);
        SCANCODES.put("next", 163);

        FOCUS_SCANCODES.put("front", 106);
        FOCUS_SCANCODES.put("back", 105);

        TEXT_KEYCODES.put("enter", 66);
        TEXT_KEYCODES.put("delete", 67);

        SCREENS.put("settings", "com.android.tv.settings/.uicustom.MainActivity");
        SCREENS.put("keystone", "com.android.tv.settings/.trapezoid.TrapezoidActivity");
        SCREENS.put("keystone4", "com.example.trapezoid/.MainActivity");
        SCREENS.put("wifi", "com.android.tv.settings/.uicustom.func.net.wireless.WifiActivity");
        SCREENS.put("bluetooth", "com.android.tv.settings/.accessories.AccessoriesActivity");
        SCREENS.put("sound", "com.android.tv.settings/.device.sound.SoundActivity");
        SCREENS.put("apps", "com.android.tv.settings/.device.apps.AppsActivity");
        SCREENS.put("language", "com.android.tv.settings/.system.LanguageActivity");
        SCREENS.put("datetime", "com.android.tv.settings/.system.DateTimeActivity");
        SCREENS.put("keyboard", "com.android.tv.settings/.inputmethod.KeyboardActivity");
        SCREENS.put("about", "com.android.tv.settings/.about.AboutActivity");
        SCREENS.put("developer", "com.android.tv.settings/.system.development.DevelopmentActivity");

        APP_NAMES.put("com.google.android.youtube.tv", "YouTube");
        APP_NAMES.put("com.spotify.tv.android", "Spotify");
        APP_NAMES.put("com.disney.disneyplus", "Disney+");
        APP_NAMES.put("com.disney.starplus", "Star+");
        APP_NAMES.put("com.hbo.hbonow", "HBO Max");
        APP_NAMES.put("com.wbd.stream", "Max");
        APP_NAMES.put("com.amazon.amazonvideo.livingroom", "Prime Video");
        APP_NAMES.put("com.cbs.ca", "Paramount+");
        APP_NAMES.put("tv.pluto.android", "Pluto TV");
        APP_NAMES.put("ar.com.flow.androidtv", "Flow");
        APP_NAMES.put("com.univision.prendetv", "ViX");
        APP_NAMES.put("com.android.vending", "Play Store");
        APP_NAMES.put("com.estrongs.android.pop", "ES File Explorer");
        APP_NAMES.put("com.ionitech.airscreen", "AirScreen");
        APP_NAMES.put("com.mediatek.wwtv.tvcenter", "Entradas / TV");
        APP_NAMES.put("com.zeasn.whale.open.launcher.technical", "Launcher");
        APP_NAMES.put("zeasn.open.technical.tv.store.ruijiang.mtk9255", "App Store");
        APP_NAMES.put("de.szalkowski.activitylauncher", "Activity Launcher");
        APP_NAMES.put("local.chowa.remote", "Control remoto");
    }

    private final AdbClient adb;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private final Map<Integer, ScheduledFuture<?>> held = new HashMap<>();
    private List<JSONObject> appsCache;

    Projector(AdbClient adb) {
        this.adb = adb;
    }

    private static String keyCmd(int code, int value) {
        return "sendevent " + IR_DEVICE + " 1 " + code + " " + value
                + "; sendevent " + IR_DEVICE + " 0 0 0";
    }

    void tap(int code) throws IOException {
        adb.shell(keyCmd(code, 1) + "; " + keyCmd(code, 0));
    }

    synchronized void keyDown(int code) throws IOException {
        ScheduledFuture<?> timer = held.remove(code);
        if (timer != null) {
            timer.cancel(false);
        } else {
            adb.shell(keyCmd(code, 1));
        }
        held.put(code, watchdog.schedule(() -> autoRelease(code), HOLD_LIMIT_MS, TimeUnit.MILLISECONDS));
    }

    synchronized void keyUp(int code) throws IOException {
        ScheduledFuture<?> timer = held.remove(code);
        if (timer != null) {
            timer.cancel(false);
        }
        adb.shell(keyCmd(code, 0));
    }

    private void autoRelease(int code) {
        try {
            keyUp(code);
        } catch (IOException ignored) {
        }
    }

    void releaseAll() {
        List<Integer> codes;
        synchronized (this) {
            codes = new ArrayList<>(held.keySet());
        }
        for (int code : codes) {
            autoRelease(code);
        }
        watchdog.shutdownNow();
    }

    void hold(int code, String action) throws IOException {
        switch (action) {
            case "down":
                keyDown(code);
                break;
            case "up":
                keyUp(code);
                break;
            case "tap":
                tap(code);
                break;
            default:
                throw new IllegalArgumentException("unknown action: " + action);
        }
    }

    String currentWindow() throws IOException {
        String out = adb.shell("dumpsys window | grep -m1 mCurrentFocus").trim();
        return out.startsWith("mCurrentFocus=") ? out.substring("mCurrentFocus=".length()) : out;
    }

    private void ensureFocusOverlay() throws IOException {
        if (currentWindow().contains(FOCUS_WINDOW_OWNER)) {
            return;
        }
        adb.shell("am broadcast -a " + FOCUS_SHOW_ACTION);
        try {
            Thread.sleep(FOCUS_SHOW_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void focus(String direction, String action) throws IOException {
        Integer code = FOCUS_SCANCODES.get(direction);
        if (code == null) {
            throw new IllegalArgumentException("unknown focus direction: " + direction);
        }
        if (!"up".equals(action)) {
            ensureFocusOverlay();
        }
        hold(code, action);
    }

    JSONObject getVolume() throws IOException, JSONException {
        Matcher m = VOLUME_RE.matcher(adb.shell("media volume --stream " + MEDIA_STREAM_MUSIC + " --get"));
        if (!m.find()) {
            return null;
        }
        return new JSONObject()
                .put("level", Integer.parseInt(m.group(1)))
                .put("min", Integer.parseInt(m.group(2)))
                .put("max", Integer.parseInt(m.group(3)));
    }

    void setVolume(int level) throws IOException {
        adb.shell("media volume --stream " + MEDIA_STREAM_MUSIC + " --set " + level);
    }

    void sendText(String text) throws IOException {
        if (text.isEmpty()) {
            return;
        }
        String escaped = text.replace("%", "\\%").replace(" ", "%s");
        adb.shell("input text " + shellQuote(escaped));
    }

    void textKey(String name) throws IOException {
        Integer code = TEXT_KEYCODES.get(name);
        if (code == null) {
            throw new IllegalArgumentException("unknown text key: " + name);
        }
        adb.shell("input keyevent " + code);
    }

    void openScreen(String name) throws IOException {
        String component = SCREENS.get(name);
        if (component == null) {
            throw new IllegalArgumentException("unknown screen: " + name);
        }
        start(component);
    }

    synchronized List<JSONObject> apps(boolean refresh) throws IOException, JSONException {
        if (appsCache == null || refresh) {
            String out = adb.shell("cmd package query-activities --brief"
                    + " -a android.intent.action.MAIN"
                    + " -c android.intent.category.LEANBACK_LAUNCHER");
            List<JSONObject> apps = new ArrayList<>();
            for (String line : out.split("\n")) {
                String component = line.trim();
                if (!component.contains("/")) {
                    continue;
                }
                String pkg = component.substring(0, component.indexOf('/'));
                String name = APP_NAMES.containsKey(pkg) ? APP_NAMES.get(pkg) : pkg.substring(pkg.lastIndexOf('.') + 1);
                apps.add(new JSONObject().put("component", component).put("package", pkg).put("name", name));
            }
            Collections.sort(apps, (a, b) -> a.optString("name").compareToIgnoreCase(b.optString("name")));
            appsCache = apps;
        }
        return appsCache;
    }

    void launch(String component) throws IOException, JSONException {
        Set<String> known = new HashSet<>();
        for (JSONObject app : apps(false)) {
            known.add(app.getString("component"));
        }
        if (!known.contains(component)) {
            throw new IllegalArgumentException("unknown component: " + component);
        }
        start(component);
    }

    private void start(String component) throws IOException {
        if (!COMPONENT_RE.matcher(component).matches()) {
            throw new IllegalArgumentException("invalid component: " + component);
        }
        String out = adb.shell("am start -n " + shellQuote(component));
        if (out.contains("Error") || out.contains("Exception")) {
            String[] lines = out.trim().split("\n");
            throw new IOException(lines[lines.length - 1]);
        }
    }

    private static String appLabel(String window) {
        Matcher m = WINDOW_PKG_RE.matcher(window);
        if (!m.find()) {
            return null;
        }
        String pkg = m.group(1);
        if (FOCUS_WINDOW_OWNER.equals(pkg)) {
            return "Ajuste de foco";
        }
        if ("com.android.tv.settings".equals(pkg) || "com.example.trapezoid".equals(pkg)) {
            return "Ajustes";
        }
        return APP_NAMES.containsKey(pkg) ? APP_NAMES.get(pkg) : pkg;
    }

    JSONObject status() throws JSONException {
        JSONObject data = new JSONObject().put("serial", "local");
        try {
            String window = currentWindow();
            data.put("state", "device");
            data.put("window", window);
            data.put("app", appLabel(window));
            data.put("volume", getVolume());
        } catch (IOException e) {
            data.put("state", "error: " + e.getMessage());
        }
        return data;
    }

    boolean ping() {
        try {
            return adb.shell("echo ok").trim().equals("ok");
        } catch (IOException e) {
            return false;
        }
    }

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    static JSONArray toArray(List<JSONObject> items) {
        JSONArray array = new JSONArray();
        for (JSONObject item : items) {
            array.put(item);
        }
        return array;
    }
}
