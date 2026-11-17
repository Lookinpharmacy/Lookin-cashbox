package com.lookin.cashbox;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * คุยกับบอร์ด MEGA ในตู้ทางสาย USB (115200, ข้อความทีละบรรทัด) — ดูรายละเอียดใน PROTOCOL.md
 * เชื่อมต่อใหม่เองทุก 3 วินาทีเมื่อหลุด  ส่งข้อความที่ได้กลับเข้าเธรดหน้าจอผ่าน Listener
 */
public class Board {
    public interface Listener { void onLine(String line); void onState(boolean connected, String info); }

    private static final String ACTION_PERM = "com.lookin.cashbox.USB_PERMISSION";
    private final Context ctx;
    private final Listener ls;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private UsbSerialPort port;
    private Thread reader;
    private volatile boolean running = true;
    private boolean asked = false;
    public volatile boolean connected = false;

    public Board(Context c, Listener l) {
        ctx = c.getApplicationContext(); ls = l;
        ctx.registerReceiver(permRx, new IntentFilter(ACTION_PERM));
        ui.post(tick);
    }

    private final BroadcastReceiver permRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { asked = false; ui.post(Board.this::tryOpen); }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (port == null) tryOpen();
            ui.postDelayed(this, 3000);
        }
    };

    private void tryOpen() {
        if (port != null || !running) return;
        UsbManager m = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> list = UsbSerialProber.getDefaultProber().findAllDrivers(m);
        if (list.isEmpty()) { state(false, "ไม่พบบอร์ด (ตรวจสาย USB)"); return; }
        UsbSerialDriver d = list.get(0);
        UsbDevice dev = d.getDevice();
        if (!m.hasPermission(dev)) {
            if (!asked) {
                asked = true;
                int fl = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                Intent pi = new Intent(ACTION_PERM); pi.setPackage(ctx.getPackageName());
                m.requestPermission(dev, PendingIntent.getBroadcast(ctx, 0, pi, fl));
            }
            state(false, "รออนุญาตใช้ USB (กด ตกลง และติ๊ก ใช้เป็นค่าเริ่มต้น)");
            return;
        }
        UsbDeviceConnection conn = m.openDevice(dev);
        if (conn == null) { state(false, "เปิด USB ไม่ได้"); return; }
        try {
            UsbSerialPort p = d.getPorts().get(0);
            p.open(conn);
            p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            try { p.setDTR(true); p.setRTS(true); } catch (Exception ignored) {}
            port = p;
            startReader(p);
            state(true, "เชื่อมต่อบอร์ดแล้ว");
        } catch (Exception e) {
            try { conn.close(); } catch (Exception ignored) {}
            state(false, "เปิดพอร์ตไม่ได้: " + e.getMessage());
        }
    }

    private void startReader(final UsbSerialPort p) {
        reader = new Thread(() -> {
            byte[] buf = new byte[256];
            StringBuilder sb = new StringBuilder();
            while (running && port == p) {
                int n;
                try { n = p.read(buf, 500); } catch (Exception e) { break; }
                for (int i = 0; i < n; i++) {
                    char c = (char) (buf[i] & 0xFF);
                    if (c == '\n') {
                        final String line = new String(sb.toString().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8).trim();
                        sb.setLength(0);
                        if (!line.isEmpty()) ui.post(() -> ls.onLine(line));
                    } else if (c != '\r' && sb.length() < 400) sb.append(c);
                }
            }
            ui.post(() -> { if (port == p) close("บอร์ดหลุด"); });
        }, "board-reader");
        reader.start();
    }

    public synchronized boolean send(String cmd) {
        UsbSerialPort p = port;
        if (p == null) return false;
        try { p.write((cmd + "\n").getBytes(StandardCharsets.UTF_8), 1000); return true; }
        catch (Exception e) { ui.post(() -> close("ส่งคำสั่งไม่ได้")); return false; }
    }

    private void close(String why) {
        UsbSerialPort p = port; port = null;
        if (p != null) try { p.close(); } catch (Exception ignored) {}
        state(false, why);
    }

    private void state(boolean c, String info) {
        boolean changed = c != connected;
        connected = c;
        if (changed || !c) ls.onState(c, info);
    }

    public void stop() {
        running = false;
        close("ปิดแอป");
        try { ctx.unregisterReceiver(permRx); } catch (Exception ignored) {}
    }
}
