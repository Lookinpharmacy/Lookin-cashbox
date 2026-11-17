package com.lookin.cashbox;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/** ค่าตั้ง + คิวงานที่ยังส่งเครื่องร้านไม่สำเร็จ (เก็บถาวร ปิดเครื่องแล้วไม่หาย) */
public class Store {
    private final SharedPreferences p;
    private static final Random RND = new Random();

    public Store(Context c) { p = c.getSharedPreferences("cashbox", Context.MODE_PRIVATE); }

    public String url()  { return p.getString("url", ""); }
    public String box()  { return p.getString("box", ""); }
    public String key()  { return p.getString("key", ""); }
    public boolean configured() { return url().length() > 0 && box().length() > 0 && key().length() > 0; }
    public void save(String url, String box, String key) {
        url = url.trim();
        if (url.length() > 0 && !url.startsWith("http")) url = "http://" + url;
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.length() > 0 && !url.matches(".*:\\d+$")) url = url + ":3001";
        p.edit().putString("url", url).putString("box", box.trim().toLowerCase(Locale.ROOT)).putString("key", key.trim().toUpperCase(Locale.ROOT)).apply();
    }
    public String lastStaff() { return p.getString("lastStaff", ""); }
    public void setLastStaff(String s) { p.edit().putString("lastStaff", s).apply(); }

    // การฝากที่กำลังทำอยู่ (เก็บทุกครั้งที่มีแบงก์/เหรียญเข้า กันแอปปิดกลางทาง)
    public JSONObject currentDeposit() {
        String s = p.getString("dep", "");
        if (s.isEmpty()) return null;
        try { return new JSONObject(s); } catch (Exception e) { return null; }
    }
    public void setCurrentDeposit(JSONObject o) { p.edit().putString("dep", o == null ? "" : o.toString()).commit(); }

    // เลขเหตุการณ์จากบอร์ดที่บันทึกไปแล้ว (กันนับแบงก์ซ้ำเมื่อบอร์ดส่งซ้ำ)
    public synchronized boolean seen(String id) {
        String s = p.getString("seenIds", "");
        if (("," + s + ",").contains("," + id + ",")) return true;
        String[] a = s.isEmpty() ? new String[0] : s.split(",");
        StringBuilder b = new StringBuilder(id);
        for (int i = 0; i < a.length && i < 199; i++) b.append(',').append(a[i]);
        p.edit().putString("seenIds", b.toString()).commit();
        return false;
    }

    // ---------- คิวส่งเครื่องร้าน ----------
    public synchronized void enqueue(String path, JSONObject body) {
        try {
            JSONArray q = queue();
            JSONObject j = new JSONObject();
            j.put("path", path); j.put("body", body);
            q.put(j);
            p.edit().putString("outbox", q.toString()).commit();
        } catch (Exception ignored) {}
    }
    public synchronized JSONArray queue() {
        try { return new JSONArray(p.getString("outbox", "[]")); } catch (Exception e) { return new JSONArray(); }
    }
    public synchronized void dropFirst() {
        JSONArray q = queue(), out = new JSONArray();
        for (int i = 1; i < q.length(); i++) out.put(q.opt(i));
        p.edit().putString("outbox", out.toString()).commit();
    }

    // ---------- รายการฝากที่เครื่องร้านไม่รับ (เก็บไว้ ไม่ทิ้ง — เงินอยู่ในตู้แล้ว) ----------
    public synchronized JSONArray parked() {
        try { return new JSONArray(p.getString("parked", "[]")); } catch (Exception e) { return new JSONArray(); }
    }
    /** ย้ายรายการแรกของคิวไปพักไว้ */
    public synchronized void parkFirst(String why) {
        JSONArray q = queue(), out = new JSONArray(), pk = parked();
        JSONObject first = q.optJSONObject(0);
        if (first != null) { try { first.put("why", why); first.put("parkedAt", now()); } catch (Exception ignored) {} pk.put(first); }
        for (int i = 1; i < q.length(); i++) out.put(q.opt(i));
        p.edit().putString("outbox", out.toString()).putString("parked", pk.toString()).commit();
    }
    /** เอารายการที่พักไว้กลับเข้าคิวส่งใหม่ (หลังแก้ค่าตั้ง) */
    public synchronized void unparkAll() {
        JSONArray pk = parked();
        if (pk.length() == 0) return;
        JSONArray q = queue();
        for (int i = 0; i < pk.length(); i++) { JSONObject j = pk.optJSONObject(i); if (j != null) { j.remove("why"); j.remove("parkedAt"); q.put(j); } }
        p.edit().putString("outbox", q.toString()).putString("parked", "[]").commit();
    }

    public static String now() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()); }
    public static String uid(String box, String kind) {
        return box + "-" + kind + "-" + new SimpleDateFormat("yyMMddHHmmss", Locale.US).format(new Date()) + "-" + Integer.toHexString(0x1000 + RND.nextInt(0xF000));
    }
}
