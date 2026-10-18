package io.github.kangweizhu.backupcallingfixer;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.LocaleManager;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final int SHIZUKU_REQUEST = 1;
    private static final long POLL_MS = 5000;
    private static final String IWLAN = PrivService.IWLAN;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout content;
    private TextView logView;
    private String carrierCsv = "";
    private IPrivService service;
    private boolean resumed;
    private String lastStatus;
    private volatile JSONObject lastStatusJson;

    private final Shizuku.UserServiceArgs serviceArgs = new Shizuku.UserServiceArgs(
            new ComponentName(BuildConfig.APPLICATION_ID, PrivService.class.getName()))
            .daemon(false)
            .processNameSuffix("priv")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE);

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder == null || !binder.pingBinder()) return;
            service = IPrivService.Stub.asInterface(binder);
            refresh();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            if (!isDestroyed()) main.postDelayed(MainActivity.this::connectShizuku, 1000);
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceived = this::connectShizuku;
    private final Shizuku.OnRequestPermissionResultListener permissionResult =
            (requestCode, grantResult) -> {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    connectShizuku();
                } else {
                    showMessage(getString(R.string.shizuku_denied));
                }
            };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (resumed) main.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().hide();
        setContentView(buildShell());
        showMessage(getString(R.string.shizuku_connecting));
        Shizuku.addRequestPermissionResultListener(permissionResult);
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        main.post(poll);
    }

    @Override
    protected void onPause() {
        resumed = false;
        main.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeRequestPermissionResultListener(permissionResult);
        // Only unbind; don't remove the service. Switching language recreates this activity, and
        // removing would kill the service the new activity has just bound. The service is not a
        // daemon, so it still exits together with the app process.
        try {
            Shizuku.unbindUserService(serviceArgs, connection, false);
        } catch (Throwable ignored) {
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    private void connectShizuku() {
        if (!Shizuku.pingBinder()) {
            showMessage(getString(R.string.shizuku_not_running));
            return;
        }
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 10) {
            showMessage(getString(R.string.shizuku_too_old));
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_REQUEST);
            return;
        }
        Shizuku.bindUserService(serviceArgs, connection);
    }

    // ---- service calls ----

    private interface ServiceCall {
        String call(IPrivService s) throws Exception;
    }

    private void run(ServiceCall call) {
        IPrivService s = service;
        if (s == null) {
            logView.setText(R.string.service_not_connected);
            connectShizuku();
            return;
        }
        logView.setText(R.string.running);
        executor.execute(() -> {
            String log;
            try {
                log = formatEvents(new JSONArray(call.call(s)));
            } catch (Exception e) {
                log = getString(R.string.failed, e.toString());
            }
            String finalLog = log;
            main.post(() -> {
                logView.setText(finalLog);
                lastStatus = null;
                refresh();
            });
        });
    }

    /** Turns the service's JSON events into localized log lines. */
    private String formatEvents(JSONArray events) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null) continue;
            String op = e.optString("op");
            String error = e.optString("error", null);
            String line;
            switch (op) {
                case "autoDataSwitch":
                case "crossSim": {
                    String sim = simName(e.optInt("subId"));
                    boolean on = e.optBoolean("enabled");
                    if (error != null) {
                        line = getString(R.string.log_setting_failed, sim, error);
                    } else if (op.equals("autoDataSwitch")) {
                        line = getString(on ? R.string.log_auto_switch_on : R.string.log_auto_switch_off, sim);
                    } else {
                        line = getString(on ? R.string.log_cross_sim_on : R.string.log_cross_sim_off, sim);
                    }
                    break;
                }
                case "kick": {
                    JSONArray subs = e.optJSONArray("subs");
                    if (error != null) {
                        line = getString(R.string.log_kick_failed, error);
                    } else if (subs == null || subs.length() == 0) {
                        line = getString(R.string.log_kick_none);
                    } else {
                        List<String> names = new ArrayList<>();
                        for (int j = 0; j < subs.length(); j++) names.add(simName(subs.optInt(j)));
                        line = getString(R.string.log_kick, String.join(", ", names));
                    }
                    break;
                }
                case "carrierList":
                    line = error != null ? getString(R.string.failed, error)
                            : getString(R.string.log_carrier_list, e.optString("packages"));
                    break;
                case "iwlanExcluded":
                    if (error != null) {
                        line = getString(R.string.log_iwlan_failed, error);
                    } else if (!e.optBoolean("ok")) {
                        line = getString(R.string.log_iwlan_rejected);
                    } else {
                        line = getString(e.optBoolean("excluded")
                                ? R.string.log_iwlan_added : R.string.log_iwlan_removed);
                    }
                    break;
                case "readSims":
                    line = getString(R.string.log_read_sims_failed, error);
                    break;
                default:
                    line = e.toString();
            }
            b.append(line).append('\n');
        }
        return b.toString().trim();
    }

    private String simName(int subId) {
        JSONArray subs = lastStatusJson == null ? null : lastStatusJson.optJSONArray("subs");
        for (int i = 0; subs != null && i < subs.length(); i++) {
            JSONObject o = subs.optJSONObject(i);
            if (o != null && o.optInt("subId") == subId) return o.optString("name");
        }
        return "subId " + subId;
    }

    private void refresh() {
        IPrivService s = service;
        if (s == null) return;
        executor.execute(() -> {
            String raw;
            JSONObject status;
            try {
                raw = s.getStatus();
                status = new JSONObject(raw);
            } catch (Exception e) {
                main.post(() -> showMessage(getString(R.string.status_failed, e.toString())));
                return;
            }
            main.post(() -> {
                // Redrawing rebuilds every view, so skip it when nothing changed or while typing.
                if (raw.equals(lastStatus) || getCurrentFocus() instanceof EditText) return;
                lastStatus = raw;
                render(status);
            });
        });
    }

    // ---- view helpers ----

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private int color(int id) {
        return getColor(id);
    }

    private GradientDrawable rounded(int fill, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private TextView text(CharSequence s, float sp, int colorId, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color(colorId));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(color(R.color.card), 16));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        if (title != null) {
            TextView t = text(title, 16, R.color.text, true);
            t.setPadding(0, 0, 0, dp(8));
            c.addView(t);
        }
        return c;
    }

    private TextView badge(String s, boolean ok) {
        TextView t = text(s, 12, ok ? R.color.ok : R.color.bad, true);
        t.setBackground(rounded(color(ok ? R.color.ok_bg : R.color.bad_bg), 10));
        t.setPadding(dp(8), dp(2), dp(8), dp(2));
        return t;
    }

    /** A label on the left and a status badge on the right. */
    private View statusRow(String label, String value, boolean ok) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView l = text(label, 14, R.color.text, false);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(badge(value, ok));
        return row;
    }

    private View switchRow(String label, String hint, boolean checked, SwitchAction action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(label, 14, R.color.text, false));
        if (hint != null) labels.addView(text(hint, 12, R.color.subtext, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states,
                new int[] {color(R.color.ok), color(R.color.switch_thumb_off)}));
        sw.setTrackTintList(new ColorStateList(states,
                new int[] {color(R.color.ok_track), color(R.color.switch_track_off)}));
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((b, isChecked) -> run(s -> action.apply(s, isChecked)));
        row.addView(sw);
        return row;
    }

    private interface SwitchAction {
        String apply(IPrivService s, boolean enabled) throws Exception;
    }

    private Button button(String label, boolean primary, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(primary ? color(R.color.card) : color(R.color.accent));
        b.setBackground(rounded(primary ? color(R.color.accent) : color(R.color.accent_bg), 20));
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(color(R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    // ---- layout ----

    private View buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(16));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(8), 0, dp(12));
        header.addView(text(getString(R.string.app_name), 22, R.color.text, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            TextView language = text(getString(R.string.language), 14, R.color.accent, true);
            language.setPadding(dp(12), dp(8), dp(12), dp(8));
            language.setOnClickListener(this::showLanguageMenu);
            header.addView(language);
        }
        TextView refresh = text(getString(R.string.refresh), 14, R.color.accent, true);
        refresh.setPadding(dp(12), dp(8), dp(4), dp(8));
        refresh.setOnClickListener(v -> refresh());
        header.addView(refresh);
        root.addView(header);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content);

        LinearLayout logCard = card(getString(R.string.log_title));
        logView = text("", 12, R.color.subtext, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logCard.addView(logView);
        root.addView(logCard);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(color(R.color.bg));
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        return scroll;
    }

    /**
     * Sets the per-app language. This is the same setting as Settings → Apps → Language, and the
     * system recreates the activity once it changes.
     */
    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private void showLanguageMenu(View anchor) {
        String[] tags = {"", "en", "zh-CN"};
        LocaleManager localeManager = getSystemService(LocaleManager.class);
        String current = localeManager.getApplicationLocales().toLanguageTags();

        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, R.string.language_system);
        menu.getMenu().add(0, 1, 1, "English");
        menu.getMenu().add(0, 2, 2, "中文");
        menu.getMenu().setGroupCheckable(0, true, true);
        int checked = current.isEmpty() ? 0 : current.startsWith("zh") ? 2 : 1;
        menu.getMenu().getItem(checked).setChecked(true);
        menu.setOnMenuItemClickListener(item -> {
            localeManager.setApplicationLocales(LocaleList.forLanguageTags(tags[item.getItemId()]));
            return true;
        });
        menu.show();
    }

    private void showMessage(String message) {
        lastStatus = null;
        content.removeAllViews();
        LinearLayout c = card(null);
        c.addView(text(message, 15, R.color.text, false));
        content.addView(c);
    }

    // ---- rendering ----

    private String imsTech(int tech) {
        switch (tech) {
            case 0: return getString(R.string.tech_lte);
            case 1: return getString(R.string.tech_wifi);
            case 2: return getString(R.string.tech_cross_sim);
            case 3: return getString(R.string.tech_nr);
            case 4: return getString(R.string.tech_3g);
            default: return getString(R.string.tech_none);
        }
    }

    private void render(JSONObject s) {
        lastStatusJson = s;
        content.removeAllViews();
        if (s.has("error")) {
            showMessage(getString(R.string.error, s.optString("error")));
            return;
        }
        JSONArray subs = s.optJSONArray("subs");
        if (subs == null) subs = new JSONArray();

        JSONObject target = null;
        String dataSimName = "?";
        for (int i = 0; i < subs.length(); i++) {
            JSONObject o = subs.optJSONObject(i);
            if (o == null) continue;
            if (o.optBoolean("isDefaultData")) {
                dataSimName = o.optString("name", dataSimName);
            } else if (target == null) {
                target = o;
            }
        }
        JSONArray vpns = s.optJSONArray("vpns");
        boolean vpnBlocksIwlan = false;
        for (int i = 0; vpns != null && i < vpns.length(); i++) {
            if (vpns.optJSONObject(i).optBoolean("coversUid1000")) vpnBlocksIwlan = true;
        }

        content.addView(buildBanner(target, dataSimName, vpnBlocksIwlan));
        for (int i = 0; i < subs.length(); i++) {
            content.addView(buildSimCard(subs.optJSONObject(i)));
        }
        content.addView(buildVpnCard(s, vpns));
    }

    private View buildBanner(JSONObject target, String dataSimName, boolean vpnBlocksIwlan) {
        int tech = target == null ? -1 : target.optInt("imsTech", -1);
        boolean ok = target != null && target.optBoolean("imsRegistered") && (tech == 1 || tech == 2);

        LinearLayout c = card(null);
        c.setBackground(rounded(color(ok ? R.color.ok_bg : R.color.bad_bg), 16));
        if (target == null) {
            c.addView(text("✗  " + getString(R.string.banner_no_sim), 16, R.color.bad, true));
            return c;
        }
        String simName = target.optString("name");
        String title = getString(ok ? R.string.banner_ok : R.string.banner_bad, simName);
        c.addView(text((ok ? "✓  " : "✗  ") + title, 17, ok ? R.color.ok : R.color.bad, true));
        if (ok) {
            String via = tech == 2 ? getString(R.string.via_cross_sim, dataSimName) : getString(R.string.via_wifi);
            TextView t = text(via, 13, R.color.text, false);
            t.setPadding(0, dp(4), 0, 0);
            c.addView(t);
        }

        if (!ok) {
            List<String> problems = new ArrayList<>();
            if (!target.optBoolean("autoDataSwitch")) problems.add(getString(R.string.problem_auto_switch));
            if (!target.optBoolean("crossSim")) problems.add(getString(R.string.problem_cross_sim));
            if (target.has("crossSimAvailable") && !target.optBoolean("crossSimAvailable")) {
                problems.add(getString(R.string.problem_carrier));
            }
            if (vpnBlocksIwlan) problems.add(getString(R.string.problem_vpn));
            JSONObject iw = target.optJSONObject("iwlan");
            if (iw != null && !iw.optBoolean("networkConnected")) problems.add(getString(R.string.problem_iwlan_network));
            if (iw != null && iw.optBoolean("networkConnected") && !iw.optBoolean("epdgConnected")) {
                problems.add(getString(R.string.problem_epdg));
            }
            if (problems.isEmpty()) problems.add(getString(R.string.problem_none));
            for (String p : problems) {
                TextView t = text("·  " + p, 13, R.color.text, false);
                t.setPadding(0, dp(4), 0, 0);
                c.addView(t);
            }
        }
        c.addView(button(getString(R.string.fix_all), true, () -> run(svc -> svc.fixAll())));
        c.addView(button(getString(R.string.kick), false, () -> run(IPrivService::kickIwlan)));
        return c;
    }

    private View buildSimCard(JSONObject o) {
        int sub = o.optInt("subId");
        boolean isData = o.optBoolean("isDefaultData");
        LinearLayout c = card(isData ? getString(R.string.sim_data, o.optString("name")) : o.optString("name"));
        TextView meta = text(getString(R.string.sim_meta, sub, o.optString("slot")), 12, R.color.subtext, false);
        meta.setPadding(0, 0, 0, dp(6));
        c.addView(meta);
        if (o.has("error")) c.addView(text(getString(R.string.error, o.optString("error")), 13, R.color.bad, false));

        if (!isData) {
            c.addView(switchRow(getString(R.string.auto_switch), getString(R.string.auto_switch_hint),
                    o.optBoolean("autoDataSwitch"), (svc, on) -> svc.setAutoDataSwitch(sub, on)));
        }
        c.addView(switchRow(getString(R.string.cross_sim), null,
                o.optBoolean("crossSim"), (svc, on) -> svc.setCrossSim(sub, on)));
        c.addView(divider());
        if (o.has("crossSimAvailable")) {
            boolean avail = o.optBoolean("crossSimAvailable");
            c.addView(statusRow(getString(R.string.carrier_allows), getString(avail ? R.string.yes : R.string.no), avail));
        }
        int tech = o.optInt("imsTech", -1);
        boolean reg = o.optBoolean("imsRegistered");
        c.addView(statusRow(getString(R.string.ims), reg ? imsTech(tech) : getString(R.string.tech_none), reg));
        // The data SIM registers IMS on its own cellular network, so its iwlan state is irrelevant.
        JSONObject iw = o.optJSONObject("iwlan");
        if (iw != null && !isData) {
            boolean net = iw.optBoolean("networkConnected");
            boolean epdg = iw.optBoolean("epdgConnected");
            c.addView(statusRow(getString(R.string.iwlan_network), getString(net ? R.string.available : R.string.unavailable), net));
            c.addView(statusRow(getString(R.string.epdg_tunnel), epdg ? getString(R.string.epdg_connected, iw.optString("ikeNetwork")) : getString(R.string.epdg_disconnected), epdg));
        }
        return c;
    }

    private View buildVpnCard(JSONObject s, JSONArray vpns) {
        LinearLayout c = card(getString(R.string.vpn));
        if (vpns == null || vpns.length() == 0) {
            c.addView(statusRow(getString(R.string.current_vpn), getString(R.string.none), true));
        } else {
            for (int i = 0; i < vpns.length(); i++) {
                JSONObject v = vpns.optJSONObject(i);
                String owner = v.optString("owner", "?");
                c.addView(text(appLabel(owner), 14, R.color.text, true));
                boolean bypass = !v.optBoolean("coversUid1000");
                c.addView(statusRow(getString(R.string.iwlan_bypasses_vpn), getString(bypass ? R.string.yes : R.string.no), bypass));
            }
        }
        c.addView(divider());

        TextView sub = text(getString(R.string.vpn_by_google_list), 15, R.color.text, true);
        c.addView(sub);
        if (s.has("vpnExclusionError")) {
            c.addView(text(getString(R.string.error, s.optString("vpnExclusionError")), 13, R.color.bad, false));
            return c;
        }
        c.addView(switchRow(getString(R.string.iwlan_excluded), getString(R.string.iwlan_excluded_hint),
                s.optBoolean("vpnByGoogleExcludesIwlan"), (svc, on) -> svc.setIwlanExcluded(on)));

        TextView carrierHint = text(getString(R.string.carrier_list_hint), 12, R.color.subtext, false);
        carrierHint.setPadding(0, dp(8), 0, 0);
        c.addView(carrierHint);
        EditText csv = new EditText(this);
        csv.setText(carrierCsv);
        csv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        csv.setTextColor(color(R.color.text));
        csv.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        csv.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) carrierCsv = csv.getText().toString();
        });
        c.addView(csv);
        c.addView(button(getString(R.string.carrier_list_button), false, () -> {
            carrierCsv = csv.getText().toString();
            run(svc -> svc.injectCarrierList(carrierCsv));
        }));

        JSONArray list = s.optJSONArray("exclusionList");
        int n = list == null ? 0 : list.length();
        TextView count = text(getResources().getQuantityString(R.plurals.excluded_count, n, n), 13, R.color.subtext, false);
        count.setPadding(0, dp(14), 0, dp(4));
        c.addView(count);
        List<String> pkgs = new ArrayList<>();
        for (int i = 0; i < n; i++) pkgs.add(list.optString(i));
        // iwlan first so it is easy to spot.
        if (pkgs.remove(IWLAN)) pkgs.add(0, IWLAN);
        for (String pkg : pkgs) c.addView(appRow(pkg));
        return c;
    }

    private String appLabel(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return ai.loadLabel(getPackageManager()).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private View appRow(String pkg) {
        boolean iwlan = IWLAN.equals(pkg);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        if (iwlan) row.setBackground(rounded(color(R.color.accent_bg), 10));

        ImageView icon = new ImageView(this);
        Drawable d = null;
        try {
            d = getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if (d != null) icon.setImageDrawable(d);
        row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, 0, 0);
        labels.addView(text(iwlan ? getString(R.string.iwlan_label) : appLabel(pkg), 14,
                iwlan ? R.color.accent : R.color.text, iwlan));
        labels.addView(text(pkg, 11, R.color.subtext, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }
}
