package local.chowa.remote;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Starts {@link RemoteService} and shows the URL to open from a phone. */
public final class MainActivity extends Activity {

    private TextView adbStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RemoteService.start(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#0C0D10"));
        root.setPadding(48, 48, 48, 48);

        TextView title = text("Control remoto", 34, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView hint = text("Abrí esta dirección en el navegador del teléfono:", 22, Color.parseColor("#868C99"));
        TextView url = text(RemoteService.url(), 44, Color.parseColor("#4A8CFF"));
        url.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        adbStatus = text("Comprobando ADB…", 20, Color.parseColor("#868C99"));

        root.addView(title);
        root.addView(hint);
        root.addView(url);
        root.addView(adbStatus);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        new Thread(() -> {
            boolean ok = new Projector(new AdbClient("127.0.0.1", 5555, 5000)).ping();
            runOnUiThread(() -> {
                adbStatus.setText(ok ? "ADB local: conectado" : "ADB local: sin acceso. Activá la depuración por red.");
                adbStatus.setTextColor(Color.parseColor(ok ? "#3FB950" : "#EF5A50"));
            });
        }, "adb-check").start();
    }

    private TextView text(String value, float sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, 12, 0, 12);
        return view;
    }
}
