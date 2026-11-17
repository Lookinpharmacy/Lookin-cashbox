package com.lookin.cashbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * แอปบนจอตู้เก็บเงิน
 *  หน้าแรก: ฝากเงิน / เปิดตู้ (ผู้จัดการ)
 *  ฝากเงิน: เปิดรับแบงก์-เหรียญ นับสด เสร็จแล้วบันทึกเข้าคิวส่งเครื่องร้าน
 *  เปิดตู้: เลือกชื่อ -> ขอเลขจากบอร์ด -> เครื่องคลังส่ง OTP เข้า Telegram -> พิมพ์รหัส -> บอร์ดตรวจเองแล้วปลดกลอน
 *  ตั้งค่า: กดค้างที่หัวจอ 3 วินาที
 */
public class MainActivity extends Activity implements Board.Listener {
    static final String APP_VER = "app-" + BuildConfig.VERSION_NAME;
    static final int[] NOTE_V = {1000, 500, 100, 50, 20};
    static final int[] COIN_V = {10, 5, 2, 1};

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService bg = Executors.newSingleThreadExecutor();
    Store st;
    Board board;

    LinearLayout root, body;
    TextView title, status;
    String screen = "home";
    String door = "-", lock = "-", fw = "-";
    boolean serverOk = false;
    String serverMsg = "ยังไม่ได้ติดต่อเครื่องร้าน";
    JSONArray managers = new JSONArray();
    String openUser = "", openName = "";
    TextView depTotal, depDetail, openMsg, codeView;
    String code = "";

    // ---------------------------------------------------------------- lifecycle
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        st = new Store(this);
        buildFrame();
        board = new Board(this, this);
        queueEvent("app_start", APP_VER);
        if (st.currentDeposit() != null) showDeposit(); else showHome();
        ui.post(pinger);
        ui.postDelayed(helloLoop, 1000);
        ui.postDelayed(flushLoop, 3000);
        ui.postDelayed(depWatch, 10000);
    }

    @Override protected void onDestroy() { board.stop(); bg.shutdown(); super.onDestroy(); }
    @Override public void onBackPressed() { if (!"home".equals(screen) && !"deposit".equals(screen)) showHome(); }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }
    void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ---------------------------------------------------------------- ui helpers
    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    static String money(double v) { return NumberFormat.getNumberInstance(Locale.US).format(v); }

    void buildFrame() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(244, 246, 248));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackgroundColor(Color.rgb(22, 50, 79));
        head.setPadding(dp(20), dp(12), dp(20), dp(12));
        title = text("ตู้เก็บเงิน", 26, Color.WHITE, true);
        status = text("", 15, Color.rgb(207, 227, 255), false);
        head.addView(title); head.addView(status);
        head.setOnLongClickListener(v -> { askSettings(); return true; });
        root.addView(head);
        ScrollView sv = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(20), dp(24), dp(24));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }
    TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }
    Button button(String s, int bg, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s); b.setTextSize(24); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        GradientDrawable d = new GradientDrawable(); d.setColor(bg); d.setCornerRadius(dp(14));
        b.setBackground(d);
        b.setPadding(dp(16), dp(18), dp(16), dp(18));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        b.setLayoutParams(lp);
        return b;
    }
    void clear(String name) { screen = name; body.removeAllViews(); refreshStatus(); }
    void refreshStatus() {
        String box = st.box().isEmpty() ? "ยังไม่ได้ตั้งค่า" : st.box().toUpperCase(Locale.ROOT);
        int q = st.queue().length(), pk = st.parked().length();
        status.setText("ตู้ " + box + "  ·  บอร์ด: " + (board != null && board.connected ? "✓" : "✗") +
                "  ·  เครื่องร้าน: " + (serverOk ? "✓" : "✗ " + serverMsg) +
                (q > 0 ? "  ·  รอส่ง " + q + " รายการ" : "") +
                (pk > 0 ? "  ·  ⚠ เครื่องร้านไม่รับ " + pk + " รายการฝาก (แจ้งเจ้าของร้าน)" : "") + "\nประตู: " + door + "  ·  กลอน: " + lock);
    }

    // ---------------------------------------------------------------- screens
    void showHome() {
        clear("home");
        if (!st.configured()) {
            body.addView(text("ยังไม่ได้ตั้งค่าแอป", 24, Color.rgb(198, 40, 40), true));
            body.addView(text("กดค้างที่แถบสีน้ำเงินด้านบน 3 วินาที เพื่อใส่ที่อยู่เครื่องร้าน รหัสตู้ (b1/b2/b3) และรหัสจากหน้า ตู้เก็บเงิน", 18, Color.DKGRAY, false));
            return;
        }
        body.addView(text("เลือกรายการ", 22, Color.DKGRAY, true));
        body.addView(button("💵  ฝากเงิน", Color.rgb(31, 138, 76), v -> startDeposit()));
        body.addView(button("🔑  เปิดตู้เก็บเงิน (ผู้จัดการ)", Color.rgb(21, 101, 192), v -> showOpenPick()));
    }

    // ---------- ฝากเงิน
    void startDeposit() {
        if (!board.connected) { alert("ยังติดต่อบอร์ดในตู้ไม่ได้ ฝากเงินไม่ได้ตอนนี้"); return; }
        final EditText name = new EditText(this);
        name.setTextSize(24); name.setText(st.lastStaff()); name.setHint("ชื่อคนฝาก");
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle("ชื่อคนฝาก").setView(name)
                .setPositiveButton("ฝากปิดกะ", (d, w) -> beginDeposit(name.getText().toString(), "shift"))
                .setNeutralButton("ฝากปิดวัน", (d, w) -> beginDeposit(name.getText().toString(), "day"))
                .setNegativeButton("ยกเลิก", null).show();
    }
    void beginDeposit(String staff, String kind) {
        staff = staff.trim();
        if (staff.isEmpty()) { alert("ใส่ชื่อคนฝากก่อน"); return; }
        st.setLastStaff(staff);
        try {
            JSONObject d = new JSONObject();
            d.put("uid", Store.uid(st.box(), "d")); d.put("staff", staff); d.put("kind", kind);
            d.put("startedAt", Store.now()); d.put("notes", new JSONObject()); d.put("coins", new JSONObject());
            st.setCurrentDeposit(d);
        } catch (Exception ignored) {}
        showDeposit();
    }
    void showDeposit() {
        clear("deposit");
        closing = false; depTouch();
        JSONObject d = st.currentDeposit();
        body.addView(text("ฝากเงิน — " + (d == null ? "" : d.optString("staff")), 22, Color.DKGRAY, true));
        body.addView(text("สอดแบงก์ทีละใบ / หยอดเหรียญ ได้เลย", 18, Color.GRAY, false));
        depTotal = text("0", 64, Color.rgb(31, 138, 76), true);
        depTotal.setGravity(Gravity.CENTER);
        body.addView(depTotal);
        depDetail = text("", 20, Color.DKGRAY, false);
        depDetail.setGravity(Gravity.CENTER);
        body.addView(depDetail);
        body.addView(button("✓  ฝากเสร็จ", Color.rgb(21, 101, 192), v -> finishDeposit()));
        board.send("ACCEPT ON");
        renderDeposit();
    }
    static double total(JSONObject d) {
        double t = 0;
        JSONObject n = d.optJSONObject("notes"), c = d.optJSONObject("coins");
        for (int v : NOTE_V) t += v * (n == null ? 0 : n.optInt("n" + v));
        for (int v : COIN_V) t += v * (c == null ? 0 : c.optInt("c" + v));
        return t;
    }
    void renderDeposit() {
        JSONObject d = st.currentDeposit();
        if (d == null || depTotal == null) return;
        depTotal.setText(money(total(d)) + " ฿");
        StringBuilder s = new StringBuilder();
        JSONObject n = d.optJSONObject("notes"), c = d.optJSONObject("coins");
        for (int v : NOTE_V) { int k = n.optInt("n" + v); if (k > 0) s.append("แบงก์ ").append(v).append(" × ").append(k).append("\n"); }
        for (int v : COIN_V) { int k = c.optInt("c" + v); if (k > 0) s.append("เหรียญ ").append(v).append(" × ").append(k).append("\n"); }
        depDetail.setText(s.length() == 0 ? "ยังไม่มีเงินเข้า" : s.toString().trim());
    }
    void addMoney(boolean note, int value) {
        JSONObject d = st.currentDeposit();
        try {
            if (d == null) {   // เงินเข้าโดยไม่ได้กดฝาก (ไม่ควรเกิด) — เก็บไว้เป็นการฝากที่ไม่ระบุชื่อ
                d = new JSONObject();
                d.put("uid", Store.uid(st.box(), "d")); d.put("staff", "ไม่ระบุ (เงินเข้าเอง)"); d.put("kind", "shift");
                d.put("startedAt", Store.now()); d.put("notes", new JSONObject()); d.put("coins", new JSONObject());
            }
            JSONObject g = d.getJSONObject(note ? "notes" : "coins");
            String k = (note ? "n" : "c") + value;
            g.put(k, g.optInt(k) + 1);
            st.setCurrentDeposit(d);
        } catch (Exception ignored) {}
        depTouch();
        if (!"deposit".equals(screen)) showDeposit(); else renderDeposit();
        // แบงก์ที่เครื่องกำลังกลืนอยู่ตอนกด ฝากเสร็จ เข้ามาทีหลัง -> ปรับยอดในหน้าต่างยืนยันให้ตรง
        if (confirmDlg != null && confirmDlg.isShowing()) { JSONObject n = st.currentDeposit(); if (n != null) confirmDlg.setMessage(confirmText(n)); }
    }

    // closing = กด ฝากเสร็จ แล้ว กำลังปิดรับเงิน/รอยืนยัน (ระหว่างนี้ถ้าบอร์ดตอบ BUSY ต้องสั่งปิดรับซ้ำ)
    boolean closing = false;
    AlertDialog confirmDlg;
    long depLast = 0;
    static final long DEP_IDLE_MS = 3 * 60000L;   // หน้าฝากเงินไม่มีความเคลื่อนไหวเกินนี้ = ปิดการฝากให้เอง
    void depTouch() { depLast = System.currentTimeMillis(); }
    static String confirmText(JSONObject d) { return "ยอดฝาก " + money(total(d)) + " บาท\nโดย " + d.optString("staff"); }

    /** บันทึกการฝากที่ทำอยู่เข้าคิวส่ง โดยอ่านยอดล่าสุดจากที่เก็บเสมอ  คืนยอดที่บันทึก (0 = ไม่มีเงิน) */
    double commitDeposit(String staffSuffix) {
        JSONObject d = st.currentDeposit();
        if (d == null) return 0;
        double t = total(d);
        if (t > 0) {
            try {
                JSONObject o = new JSONObject();
                o.put("uid", d.getString("uid")); o.put("staff", d.optString("staff") + staffSuffix); o.put("kind", d.optString("kind"));
                o.put("startedAt", d.optString("startedAt")); o.put("endedAt", Store.now());
                o.put("notes", d.getJSONObject("notes")); o.put("coins", d.getJSONObject("coins")); o.put("total", t);
                st.enqueue("/api/cashbox/dev/deposit", o);
            } catch (Exception e) { return -1; }   // บันทึกไม่ได้: คงการฝากไว้ ไม่ล้าง
        }
        st.setCurrentDeposit(null);
        flushNow();
        return t;
    }
    void finishDeposit() {
        if (confirmDlg != null && confirmDlg.isShowing()) return;
        JSONObject d = st.currentDeposit();
        closing = true; depTouch();
        board.send("ACCEPT OFF");
        if (d == null) { closing = false; showHome(); return; }
        if (total(d) <= 0) { closing = false; st.setCurrentDeposit(null); showHome(); return; }
        confirmDlg = new AlertDialog.Builder(this).setTitle("ยืนยันฝากเงิน")
                .setMessage(confirmText(d))
                .setCancelable(false)
                .setPositiveButton("ยืนยัน", (x, w) -> {
                    closing = false;
                    double t = commitDeposit("");
                    if (t < 0) { alert("บันทึกการฝากไม่ได้ ลองกด ฝากเสร็จ อีกครั้ง"); board.send("ACCEPT ON"); return; }
                    showDone(t);
                })
                .setNegativeButton("ฝากต่อ", (x, w) -> { closing = false; depTouch(); board.send("ACCEPT ON"); }).show();
    }
    void showDone(double t) {
        clear("done");
        body.addView(text("✓ บันทึกการฝากแล้ว", 30, Color.rgb(31, 138, 76), true));
        body.addView(text(money(t) + " บาท", 48, Color.DKGRAY, true));
        body.addView(button("กลับหน้าแรก", Color.GRAY, v -> showHome()));
        ui.postDelayed(() -> { if ("done".equals(screen)) showHome(); }, 15000);
    }
    // คนฝากเดินออกไปโดยไม่กด ฝากเสร็จ: ปิดรับเงินและบันทึกยอดที่เข้ามาแล้วให้เอง (กันเงินคนถัดไปลงชื่อคนก่อน)
    final Runnable depWatch = new Runnable() {
        @Override public void run() {
            ui.postDelayed(this, 10000);
            if (!"deposit".equals(screen) || depLast == 0 || System.currentTimeMillis() - depLast < DEP_IDLE_MS) return;
            if (confirmDlg != null && confirmDlg.isShowing()) confirmDlg.dismiss();
            closing = false;
            board.send("ACCEPT OFF");
            double t = commitDeposit(" (ปิดอัตโนมัติ)");
            if (t < 0) { depTouch(); return; }
            if (t > 0) showDone(t); else showHome();
        }
    };

    // ---------- เปิดตู้
    void showOpenPick() {
        clear("openPick");
        body.addView(text("ใครจะเปิดตู้", 22, Color.DKGRAY, true));
        body.addView(text("รหัส OTP จะส่งเข้า Telegram ส่วนตัวของคนที่เลือก", 17, Color.GRAY, false));
        if (managers.length() == 0) body.addView(text("ยังไม่มีรายชื่อ (ตั้งที่หน้า ตู้เก็บเงิน ในเครื่องคลัง หรือยังติดต่อเครื่องร้านไม่ได้)", 18, Color.rgb(198, 40, 40), false));
        for (int i = 0; i < managers.length(); i++) {
            JSONObject m = managers.optJSONObject(i);
            final String id = m.optString("id"), name = m.optString("name");
            body.addView(button(name, Color.rgb(21, 101, 192), v -> requestOtp(id, name)));
        }
        body.addView(button("ยกเลิก", Color.GRAY, v -> showHome()));
    }
    void requestOtp(String id, String name) {
        if (!board.connected) { alert("ยังติดต่อบอร์ดในตู้ไม่ได้"); return; }
        openUser = id; openName = name; code = "";
        clear("openCode");
        body.addView(text("เปิดตู้ — " + name, 22, Color.DKGRAY, true));
        openMsg = text("กำลังขอรหัส...", 19, Color.GRAY, false);
        body.addView(openMsg);
        codeView = text("______", 48, Color.DKGRAY, true);
        codeView.setGravity(Gravity.CENTER);
        codeView.setLetterSpacing(0.3f);
        body.addView(codeView);
        body.addView(keypad());
        body.addView(button("ยกเลิก", Color.GRAY, v -> showHome()));
        board.send("OPENREQ");
    }
    View keypad() {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(3);
        String[] k = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "ตกลง"};
        for (String s : k) {
            Button b = new Button(this);
            b.setText(s); b.setTextSize(28); b.setAllCaps(false);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0; lp.height = dp(72); lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> keyPress(s));
            g.addView(b);
        }
        g.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return g;
    }
    void keyPress(String s) {
        if ("⌫".equals(s)) { if (code.length() > 0) code = code.substring(0, code.length() - 1); }
        else if ("ตกลง".equals(s)) { if (code.length() == 6) { board.send("OTP " + code); openMsg.setText("กำลังตรวจรหัส..."); code = ""; } }
        else if (code.length() < 6) code += s;
        StringBuilder v = new StringBuilder(code);
        while (v.length() < 6) v.append('_');
        if (codeView != null) codeView.setText(v.toString());
    }

    // ---------------------------------------------------------------- จากบอร์ด
    @Override public void onLine(String line) {
        String[] p = line.split(" ", 4);
        String c = p[0];
        if (c.equals("HELLO") || c.equals("PONG")) {
            for (String kv : line.split(" ")) {
                if (kv.startsWith("door=")) door = kv.substring(5).equals("open") ? "เปิด" : "ปิด";
                if (kv.startsWith("lock=")) lock = kv.substring(5).equals("locked") ? "ล็อก" : "ปลดล็อก";
                if (kv.startsWith("fw=")) fw = kv.substring(3);
                if (kv.startsWith("accept=")) accept = kv.substring(7);
            }
            boolean want = "deposit".equals(screen) && !closing;
            if (c.equals("HELLO")) { if (want) board.send("ACCEPT ON"); }
            else if (want && accept.equals("off")) board.send("ACCEPT ON");          // บอร์ดปิดรับอยู่ทั้งที่อยู่หน้าฝากเงิน
            else if (!want && accept.equals("on")) board.send("ACCEPT OFF");         // บอร์ดยังเปิดรับทั้งที่ไม่ได้ฝาก
            refreshStatus();
        } else if ((c.equals("NOTE") || c.equals("COIN")) && p.length >= 3) {
            int v; try { v = Integer.parseInt(p[2].trim()); } catch (Exception e) { board.send("ACK " + p[1]); return; }
            if (!st.seen("m" + p[1])) addMoney(c.equals("NOTE"), v);   // บันทึกลงเครื่องก่อน แล้วค่อยตอบรับ
            board.send("ACK " + p[1]);
        } else if (c.equals("EV") && p.length >= 3) {
            board.send("ACK " + p[1]);
            if (!st.seen("e" + p[1])) queueEventUid(st.box() + "-b" + p[1].replace('.', '-') + "-" + Long.toString(System.currentTimeMillis() / 1000, 36), p[2], p.length > 3 ? p[3] : "");
        } else if (c.equals("NONCE") && p.length >= 2 && "openCode".equals(screen)) {
            sendOtpRequest(p[1].trim());
        } else if (c.equals("UNLOCK")) {
            if (openMsg != null) { openMsg.setText("✓ ปลดกลอนแล้ว เปิดประตูได้เลย\nเก็บเงินเสร็จแล้วปิดประตูให้สนิท"); openMsg.setTextColor(Color.rgb(31, 138, 76)); openMsg.setTextSize(26); }
            ui.postDelayed(() -> { if ("openCode".equals(screen)) showHome(); }, 60000);
        } else if (c.equals("OTP") && p.length >= 2) {
            if (openMsg == null) return;
            if (p[1].equals("FAIL")) { openMsg.setText("✗ รหัสไม่ถูกต้อง ลองได้อีก " + (p.length > 2 ? p[2] : "") + " ครั้ง"); openMsg.setTextColor(Color.rgb(198, 40, 40)); }
            else if (p[1].equals("LOCK")) { openMsg.setText("✗ ใส่ผิดเกินกำหนด กด ยกเลิก แล้วขอรหัสใหม่"); openMsg.setTextColor(Color.rgb(198, 40, 40)); }
        } else if (c.equals("BUSY")) {
            ui.postDelayed(() -> { if (closing || !"deposit".equals(screen)) board.send("ACCEPT OFF"); }, 1500);
        }
    }
    @Override public void onState(boolean connected, String info) {
        if (!connected) { door = lock = "-"; accept = ""; }
        refreshStatus();
        if (connected) { board.send("PING"); queueEvent("board_back", info); }
        else if (wasConnected) queueEvent("board_lost", info);
        wasConnected = connected;
    }
    boolean wasConnected = false;
    String accept = "";

    void sendOtpRequest(String nonce) {
        final String u = openUser;
        openMsg.setText("กำลังส่งรหัสเข้า Telegram ของ " + openName + "...");
        bg.execute(() -> {
            JSONObject o = new JSONObject();
            try { o.put("nonce", nonce); o.put("user", u); } catch (Exception ignored) {}
            Net.Result r = Net.post(st, "/api/cashbox/dev/otp", o);
            ui.post(() -> {
                if (!"openCode".equals(screen)) return;
                if (r.ok()) { openMsg.setText("ส่งรหัสเข้า Telegram ของ " + r.json.optString("sentTo") + " แล้ว\nพิมพ์รหัส 6 หลัก (ใช้ได้ " + r.json.optInt("minutes", 5) + " นาที)"); openMsg.setTextColor(Color.DKGRAY); }
                else { openMsg.setText("✗ " + r.message()); openMsg.setTextColor(Color.rgb(198, 40, 40)); }
            });
        });
    }

    // ---------------------------------------------------------------- เครื่องร้าน
    void queueEvent(String kind, String detail) { queueEventUid(Store.uid(st.box(), "a"), kind, detail); }
    void queueEventUid(String uid, String kind, String detail) {
        if (st.box().isEmpty()) return;
        try {
            JSONObject e = new JSONObject();
            e.put("uid", uid.length() > 40 ? uid.substring(0, 40) : uid); e.put("kind", kind); e.put("detail", detail == null ? "" : detail); e.put("at", Store.now());
            JSONObject o = new JSONObject(); o.put("events", new JSONArray().put(e));
            st.enqueue("/api/cashbox/dev/event", o);
        } catch (Exception ignored) {}
        flushNow();
    }

    boolean flushing = false;
    void flushNow() {
        if (flushing || !st.configured()) return;
        flushing = true;
        bg.execute(() -> {
            String err = null;
            for (int i = 0; i < 50; i++) {
                JSONArray q = st.queue();
                if (q.length() == 0) break;
                JSONObject j = q.optJSONObject(0);
                Net.Result r = Net.post(st, j.optString("path"), j.optJSONObject("body"));
                if (r.ok()) { st.dropFirst(); continue; }
                if (r.status >= 400 && r.status < 500 && r.status != 403 && r.status != 429) {
                    // เครื่องร้านไม่รับ ส่งซ้ำก็ไม่ผ่าน: เหตุการณ์ทิ้งได้ แต่รายการฝากเงินต้องเก็บไว้ (เงินอยู่ในตู้แล้ว)
                    if (j.optString("path").endsWith("/deposit")) st.parkFirst(r.message()); else st.dropFirst();
                    continue;
                }
                err = r.message(); break;
            }
            final String e2 = err;
            ui.post(() -> { flushing = false; if (e2 != null) { serverOk = false; serverMsg = e2; } refreshStatus(); });
        });
    }

    final Runnable flushLoop = new Runnable() { @Override public void run() { flushNow(); ui.postDelayed(this, 15000); } };
    final Runnable pinger = new Runnable() { @Override public void run() { if (board.connected) board.send("PING"); ui.postDelayed(this, 5000); } };
    final Runnable helloLoop = new Runnable() {
        @Override public void run() {
            ui.postDelayed(this, 60000);
            if (!st.configured()) return;
            final String d = door, l = lock;
            bg.execute(() -> {
                JSONObject o = new JSONObject();
                try { o.put("fw", fw); o.put("app", APP_VER); o.put("door", d); o.put("lock", l); } catch (Exception ignored) {}
                Net.Result r = Net.post(st, "/api/cashbox/dev/hello", o);
                ui.post(() -> {
                    serverOk = r.ok();
                    serverMsg = r.ok() ? "" : r.message();
                    if (r.ok()) { managers = r.json.optJSONArray("managers"); if (managers == null) managers = new JSONArray(); title.setText("ตู้เก็บเงิน · " + r.json.optString("name")); }
                    refreshStatus();
                });
            });
        }
    };

    // ---------------------------------------------------------------- ตั้งค่า
    void askSettings() {
        if (!st.configured()) { showSettings(); return; }
        final EditText k = new EditText(this);
        k.setHint("รหัสตู้ (จากหน้า ตู้เก็บเงิน)"); k.setTextSize(22);
        k.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        new AlertDialog.Builder(this).setTitle("เข้าหน้าตั้งค่า").setView(k)
                .setPositiveButton("ตกลง", (d, w) -> { if (k.getText().toString().trim().equalsIgnoreCase(st.key())) showSettings(); else alert("รหัสไม่ถูกต้อง"); })
                .setNegativeButton("ยกเลิก", null).show();
    }
    void showSettings() {
        clear("settings");
        body.addView(text("ตั้งค่า", 24, Color.DKGRAY, true));
        final EditText url = field("ที่อยู่เครื่องร้าน เช่น 192.168.1.203", st.url());
        final EditText box = field("ตู้นี้คือ b1 / b2 / b3", st.box());
        final EditText key = field("รหัสตู้ (จากหน้า ตู้เก็บเงิน ในเครื่องคลัง)", st.key());
        body.addView(button("บันทึกและทดสอบ", Color.rgb(21, 101, 192), v -> {
            st.save(url.getText().toString(), box.getText().toString(), key.getText().toString());
            st.unparkAll();   // ค่าตั้งเปลี่ยนแล้ว ลองส่งรายการฝากที่เคยถูกปฏิเสธใหม่
            serverMsg = "กำลังทดสอบ..."; refreshStatus();
            ui.removeCallbacks(helloLoop); ui.post(helloLoop);
            ui.postDelayed(() -> alert(serverOk ? "✓ ติดต่อเครื่องร้านได้" : "✗ " + serverMsg), 4000);
        }));
        TextView info = text("แอป " + APP_VER + " · บอร์ด " + fw + "\nรอส่ง " + st.queue().length() + " รายการ · เครื่องร้านไม่รับ " + st.parked().length() + " รายการ (กด บันทึกและทดสอบ เพื่อส่งใหม่)", 16, Color.GRAY, false);
        body.addView(info);
        body.addView(button("เปิดหน้าตั้งค่าของ Android", Color.GRAY, v -> startActivity(new Intent(Settings.ACTION_SETTINGS))));
        body.addView(button("กลับ", Color.GRAY, v -> showHome()));
    }
    EditText field(String label, String val) {
        body.addView(text(label, 17, Color.GRAY, false));
        EditText e = new EditText(this);
        e.setText(val); e.setTextSize(22); e.setSingleLine(true);
        body.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }
    void alert(String m) { new AlertDialog.Builder(this).setMessage(m).setPositiveButton("ตกลง", null).show(); }
}
