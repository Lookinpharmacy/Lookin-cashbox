package com.lookin.cashbox;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** POST JSON ไปเครื่องร้าน (เรียกจากเธรดพื้นหลังเท่านั้น) */
public class Net {
    public static class Result {
        public int status; public JSONObject json; public String error;
        public boolean ok() { return status == 200 && json != null && json.optBoolean("ok"); }
        public String message() {
            if (json != null && json.optString("error").length() > 0) return json.optString("error");
            return error != null ? error : ("HTTP " + status);
        }
    }

    public static Result post(Store st, String path, JSONObject body) {
        Result r = new Result();
        HttpURLConnection c = null;
        try {
            body.put("box", st.box());
            body.put("key", st.key());
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new URL(st.url() + path).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream o = c.getOutputStream()) { o.write(data); }
            r.status = c.getResponseCode();
            InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                in.close();
                try { r.json = new JSONObject(b.toString("UTF-8")); } catch (Exception e) { r.error = "ตอบกลับไม่ใช่ JSON"; }
            }
        } catch (Exception e) {
            r.status = 0;
            r.error = "ติดต่อเครื่องร้านไม่ได้ (" + e.getClass().getSimpleName() + ")";
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }
}
