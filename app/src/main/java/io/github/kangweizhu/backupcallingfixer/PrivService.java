package io.github.kangweizhu.backupcallingfixer;

import android.os.IBinder;
import android.os.PersistableBundle;
import android.telephony.SubscriptionInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shizuku user service. Runs as the shell uid, which holds MODIFY_PHONE_STATE and
 * READ_PRIVILEGED_PHONE_STATE, and talks to hidden system services through reflection.
 */
public class PrivService extends IPrivService.Stub {

    static final String IWLAN = "com.google.android.iwlan";
    static final String VPN_BY_GOOGLE = "com.google.android.apps.privacy.wildlife";
    static final int MOBILE_DATA_POLICY_AUTO_DATA_SWITCH = 3;
    static final String CALLING_PACKAGE = "com.android.shell";

    public PrivService() {
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    // ---- reflection helpers ----

    private static Object service(String name, String itf) throws Exception {
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
        return Class.forName(itf + "$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static Object telephony() throws Exception {
        return service("phone", "com.android.internal.telephony.ITelephony");
    }

    private static Object isub() throws Exception {
        return service("isub", "com.android.internal.telephony.ISub");
    }

    private static Object call(Object target, String method, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(method) && m.getParameterCount() == args.length) {
                return m.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(method + "/" + args.length);
    }

    /** Calls a method whose exact signature varies across releases, filling arguments by type. */
    private static Object callFilled(Object target, String method) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(method)) continue;
            Class<?>[] pt = m.getParameterTypes();
            Object[] args = new Object[pt.length];
            boolean firstString = true;
            for (int i = 0; i < pt.length; i++) {
                if (pt[i] == String.class) {
                    args[i] = firstString ? CALLING_PACKAGE : null;
                    firstString = false;
                } else if (pt[i] == boolean.class) {
                    args[i] = true;
                } else if (pt[i] == int.class) {
                    args[i] = 0;
                }
            }
            return m.invoke(target, args);
        }
        throw new NoSuchMethodException(method);
    }

    private static String exec(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return "exec failed: " + e;
        }
    }

    private static String err(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private int defaultDataSub() throws Exception {
        return (Integer) call(isub(), "getDefaultDataSubId");
    }

    private int[] activeSubs() throws Exception {
        return (int[]) call(isub(), "getActiveSubIdList", false);
    }

    // ---- status ----

    @Override
    public String getStatus() {
        JSONObject s = new JSONObject();
        try {
            Object tel = telephony();
            int dds = defaultDataSub();
            s.put("defaultDataSub", dds);

            JSONObject names = new JSONObject();
            try {
                @SuppressWarnings("unchecked")
                List<SubscriptionInfo> infos =
                        (List<SubscriptionInfo>) callFilled(isub(), "getActiveSubscriptionInfoList");
                if (infos != null) {
                    for (SubscriptionInfo info : infos) {
                        names.put(String.valueOf(info.getSubscriptionId()),
                                info.getDisplayName() + "|" + info.getSimSlotIndex());
                    }
                }
            } catch (Throwable t) {
                s.put("namesError", err(t));
            }

            JSONObject iwlan = parseIwlan(exec("dumpsys activity service " + IWLAN));
            Object carrierConfig = null;
            try {
                carrierConfig = service("carrier_config",
                        "com.android.internal.telephony.ICarrierConfigLoader");
            } catch (Throwable ignored) {
            }

            JSONArray subs = new JSONArray();
            for (int sub : activeSubs()) {
                JSONObject o = new JSONObject();
                o.put("subId", sub);
                String nameSlot = names.optString(String.valueOf(sub), "?|?");
                o.put("name", nameSlot.substring(0, nameSlot.lastIndexOf('|')));
                o.put("slot", nameSlot.substring(nameSlot.lastIndexOf('|') + 1));
                o.put("isDefaultData", sub == dds);
                try {
                    o.put("autoDataSwitch", (Boolean) call(tel, "isMobileDataPolicyEnabled",
                            sub, MOBILE_DATA_POLICY_AUTO_DATA_SWITCH));
                    o.put("crossSim", (Boolean) call(tel, "isCrossSimCallingEnabledByUser", sub));
                    o.put("imsRegistered", (Boolean) call(tel, "isImsRegistered", sub));
                    o.put("imsTech", (Integer) call(tel, "getImsRegTechnologyForMmTel", sub));
                } catch (Throwable t) {
                    o.put("error", err(t));
                }
                if (carrierConfig != null) {
                    try {
                        PersistableBundle b = (PersistableBundle) call(carrierConfig,
                                "getConfigForSubIdWithFeature", sub, CALLING_PACKAGE, null);
                        o.put("crossSimAvailable",
                                b.getBoolean("carrier_cross_sim_ims_available_bool"));
                    } catch (Throwable ignored) {
                    }
                }
                JSONObject iw = iwlan.optJSONObject(String.valueOf(sub));
                if (iw != null) o.put("iwlan", iw);
                subs.put(o);
            }
            s.put("subs", subs);

            String conn = exec("dumpsys connectivity");
            Matcher m = Pattern.compile("Active default network: (\\S+)").matcher(conn);
            if (m.find()) s.put("defaultNetwork", m.group(1));
            s.put("vpns", parseVpns(conn));

            try {
                List<String> excluded = exclusionList();
                s.put("vpnByGoogleExcludesIwlan", excluded.contains(IWLAN));
                s.put("exclusionList", new JSONArray(excluded));
            } catch (Throwable t) {
                s.put("vpnExclusionError", err(t));
            }
        } catch (Throwable t) {
            try {
                s.put("error", err(t));
            } catch (Exception ignored) {
            }
        }
        return s.toString();
    }

    /** Parses per-slot provider sections of the iwlan data service dump, keyed by subId. */
    private static JSONObject parseIwlan(String dump) {
        JSONObject out = new JSONObject();
        String[] sections = dump.split("---- IwlanDataServiceProvider\\[");
        for (int i = 1; i < sections.length; i++) {
            String sec = sections[i];
            Matcher sub = Pattern.compile("subID: (-?\\d+)").matcher(sec);
            if (!sub.find()) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("networkConnected", sec.contains("isNetworkConnected: true"));
                o.put("epdgConnected", sec.contains("Has ePDG connected for normal session: true"));
                Matcher net = Pattern.compile("mIkeSessionNetwork: (\\S+)").matcher(sec);
                if (net.find()) o.put("ikeNetwork", net.group(1));
                out.put(sub.group(1), o);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Finds VPN networks in dumpsys connectivity and whether their uid ranges cover uid 1000. */
    private static JSONArray parseVpns(String conn) {
        JSONArray out = new JSONArray();
        for (String line : conn.split("\n")) {
            if (!line.contains("NetworkAgentInfo{") || !line.matches(".*Transports: [A-Z|]*VPN.*")) {
                continue;
            }
            try {
                JSONObject o = new JSONObject();
                Matcher owner = Pattern.compile("OwnerUid: (\\d+)").matcher(line);
                if (owner.find()) {
                    String uid = owner.group(1);
                    String pkgs = exec("pm list packages --uid " + uid).trim();
                    Matcher pkg = Pattern.compile("package:(\\S+)").matcher(pkgs);
                    o.put("owner", pkg.find() ? pkg.group(1) : "uid " + uid);
                }
                Matcher uids = Pattern.compile("Uids: <\\{([^}]*)\\}>").matcher(line);
                boolean covers = false;
                if (uids.find()) {
                    for (String range : uids.group(1).split(",")) {
                        String[] ab = range.trim().split("-");
                        if (ab.length != 2) continue;
                        long a = Long.parseLong(ab[0]);
                        long b = Long.parseLong(ab[1]);
                        if (a <= 1000 && 1000 <= b) covers = true;
                    }
                }
                o.put("coversUid1000", covers);
                out.put(o);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ---- VPN by Google exclusion list (the same list its "Exclude selected apps" page edits) ----

    private static Object vpnManager() throws Exception {
        return service("vpn_management", "android.net.IVpnManager");
    }

    private static List<String> exclusionList() throws Exception {
        @SuppressWarnings("unchecked")
        List<String> list = (List<String>) call(vpnManager(), "getAppExclusionList", 0, VPN_BY_GOOGLE);
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    // ---- actions ----
    //
    // Every action returns a JSON array of events ({"op": ..., ...}). This process has no access
    // to the app's resources, so the app turns the events into localized log lines.

    private static JSONObject event(String op, Object... keyValues) {
        JSONObject e = new JSONObject();
        try {
            e.put("op", op);
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                e.put((String) keyValues[i], keyValues[i + 1]);
            }
        } catch (Exception ignored) {
        }
        return e;
    }

    private JSONObject autoDataSwitchEvent(int subId, boolean enabled) {
        try {
            call(telephony(), "setMobileDataPolicyEnabled",
                    subId, MOBILE_DATA_POLICY_AUTO_DATA_SWITCH, enabled);
            return event("autoDataSwitch", "subId", subId, "enabled", enabled);
        } catch (Throwable t) {
            return event("autoDataSwitch", "subId", subId, "enabled", enabled, "error", err(t));
        }
    }

    private JSONObject crossSimEvent(int subId, boolean enabled) {
        try {
            call(telephony(), "setCrossSimCallingEnabled", subId, enabled);
            return event("crossSim", "subId", subId, "enabled", enabled);
        } catch (Throwable t) {
            return event("crossSim", "subId", subId, "enabled", enabled, "error", err(t));
        }
    }

    /**
     * Toggles cross-SIM off and on for the non-data SIMs. iwlan re-registers its network callback on
     * that event; without it, a callback registered while a VPN covered uid 1000 keeps tracking the
     * VPN even after uid 1000 is excluded, and cross-SIM stays unregistered until the VPN restarts.
     */
    private JSONObject kickEvent() {
        try {
            Object tel = telephony();
            int dds = defaultDataSub();
            List<Integer> targets = new ArrayList<>();
            for (int sub : activeSubs()) {
                if (sub != dds && (Boolean) call(tel, "isCrossSimCallingEnabledByUser", sub)) {
                    targets.add(sub);
                }
            }
            if (!targets.isEmpty()) {
                for (int sub : targets) call(tel, "setCrossSimCallingEnabled", sub, false);
                Thread.sleep(2000);
                for (int sub : targets) call(tel, "setCrossSimCallingEnabled", sub, true);
            }
            return event("kick", "subs", new JSONArray(targets));
        } catch (Throwable t) {
            return event("kick", "error", err(t));
        }
    }

    private JSONObject carrierListEvent(String packagesCsv) {
        StringBuilder csv = new StringBuilder();
        for (String p : packagesCsv.split("[,\\s]+")) {
            if (p.matches("[A-Za-z0-9_.]+") && !p.equals(IWLAN)) {
                csv.append(p).append(',');
            }
        }
        csv.append(IWLAN);
        String out = exec("am broadcast"
                + " -n com.android.settings/com.google.android.settings.vpn2.AppBypassBroadcastReceiver"
                + " -a com.google.android.settings.action.UPDATE_CARRIER_APP_LIST"
                + " --esal com.google.android.wildlife.extra.CARRIER_APP_LIST " + csv);
        return out.contains("Broadcast completed")
                ? event("carrierList", "packages", csv.toString())
                : event("carrierList", "packages", csv.toString(), "error", out.trim());
    }

    private JSONObject iwlanExcludedEvent(boolean excluded) {
        try {
            List<String> list = exclusionList();
            list.remove(IWLAN);
            if (excluded) list.add(IWLAN);
            boolean ok = (Boolean) call(vpnManager(), "setAppExclusionList", 0, VPN_BY_GOOGLE, list);
            return event("iwlanExcluded", "excluded", excluded, "ok", ok);
        } catch (Throwable t) {
            return event("iwlanExcluded", "excluded", excluded, "error", err(t));
        }
    }

    @Override
    public String setAutoDataSwitch(int subId, boolean enabled) {
        return new JSONArray().put(autoDataSwitchEvent(subId, enabled)).toString();
    }

    @Override
    public String setCrossSim(int subId, boolean enabled) {
        return new JSONArray().put(crossSimEvent(subId, enabled)).toString();
    }

    @Override
    public String kickIwlan() {
        return new JSONArray().put(kickEvent()).toString();
    }

    @Override
    public String injectCarrierList(String packagesCsv) {
        return new JSONArray().put(carrierListEvent(packagesCsv)).toString();
    }

    /** Adds or removes iwlan; after adding, re-registers so iwlan picks up the cellular network. */
    private void putIwlanExcluded(JSONArray events, boolean excluded) {
        events.put(iwlanExcludedEvent(excluded));
        if (!excluded) return;
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
        }
        events.put(kickEvent());
    }

    @Override
    public String setIwlanExcluded(boolean excluded) {
        JSONArray events = new JSONArray();
        putIwlanExcluded(events, excluded);
        return events.toString();
    }

    @Override
    public String fixAll() {
        JSONArray events = new JSONArray();
        try {
            int dds = defaultDataSub();
            for (int sub : activeSubs()) {
                if (sub != dds) events.put(autoDataSwitchEvent(sub, true));
                events.put(crossSimEvent(sub, true));
            }
        } catch (Throwable t) {
            events.put(event("readSims", "error", err(t)));
        }
        putIwlanExcluded(events, true);
        return events.toString();
    }
}
