package com.rifat.robodog;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WiFi সংযোগ: ESP32 এর TCP port 8888 এ একটা socket।
 * ESP32 এর হটস্পটে internet থাকে না, তখন Android মোবাইল ডেটা দিয়ে পাঠাতে চায়।
 * তাই socket টা জোর করে WiFi network এ bind করা হয়।
 */
public class WifiLink implements DogLink {

    public static final int TCP_PORT = 8888;
    public static final int UDP_PORT = 8889;
    private static final int CONNECT_TIMEOUT_MS = 4000;

    private final Context ctx;
    private final String host;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final AtomicBoolean done = new AtomicBoolean(false);

    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean connected;

    public WifiLink(Context ctx, String host, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.host = host;
        this.listener = listener;
    }

    @Override
    public void connect() {
        new Thread(this::run, "wifi-link").start();
    }

    private void run() {
        try {
            Socket s = new Socket();
            socket = s;
            Network wifi = findWifiNetwork(ctx);
            if (wifi != null) wifi.bindSocket(s);
            s.connect(new InetSocketAddress(host, TCP_PORT), CONNECT_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            out = s.getOutputStream();
            if (done.get()) {           // অপেক্ষার মধ্যে user বাতিল করেছে
                closeQuietly();
                return;
            }
            connected = true;
            main.post(() -> {
                if (!done.get()) listener.onConnected(this, host);
            });

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                final String l = line.trim();
                if (l.isEmpty()) continue;
                main.post(() -> {
                    if (!done.get()) listener.onLine(this, l);
                });
            }
            fail(ctx.getString(R.string.err_link_lost));
        } catch (IOException e) {
            fail(ctx.getString(connected ? R.string.err_link_lost : R.string.err_wifi_connect));
        }
    }

    private void fail(String reason) {
        if (done.getAndSet(true)) return;   // আগেই বন্ধ হয়েছে
        connected = false;
        closeQuietly();
        writer.shutdownNow();
        main.post(() -> listener.onDisconnected(this, reason));
    }

    @Override
    public void send(String command) {
        if (!connected || done.get()) return;
        final byte[] data = (command + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            writer.execute(() -> {
                try {
                    OutputStream o = out;
                    if (o != null) {
                        o.write(data);
                        o.flush();
                    }
                } catch (IOException e) {
                    fail(ctx.getString(R.string.err_link_lost));
                }
            });
        } catch (RejectedExecutionException ignored) {
            // সংযোগ বন্ধ হচ্ছে
        }
    }

    @Override
    public void close() {
        if (done.getAndSet(true)) return;
        connected = false;
        try {
            writer.execute(this::closeQuietly);   // আগে পাঠানো কমান্ড (যেমন "S") চলে যাক, তারপর বন্ধ
            writer.shutdown();
        } catch (RejectedExecutionException e) {
            closeQuietly();
        }
    }

    @Override
    public boolean isConnected() {
        return connected && !done.get();
    }

    private void closeQuietly() {
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** ফোনের WiFi network খুঁজে বের করে (না পেলে null) */
    @SuppressWarnings("deprecation")
    static Network findWifiNetwork(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    // ===================== RoboDog খোঁজা (UDP broadcast) =====================

    public interface DiscoverCallback {
        /** পাওয়া গেলে IP, না পেলে null */
        void onResult(String ip);
    }

    /** একই WiFi তে "ROBODOG?" broadcast পাঠায়, ESP32 নিজের IP দিয়ে উত্তর দেয় */
    public static void discover(Context context, DiscoverCallback cb) {
        final Context ctx = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String found = null;
            try (DatagramSocket ds = new DatagramSocket()) {
                Network wifi = findWifiNetwork(ctx);
                if (wifi != null) wifi.bindSocket(ds);
                ds.setBroadcast(true);
                ds.setSoTimeout(800);
                byte[] query = "ROBODOG?".getBytes(StandardCharsets.UTF_8);
                InetAddress bcast = InetAddress.getByName("255.255.255.255");
                byte[] buf = new byte[64];
                for (int attempt = 0; attempt < 3 && found == null; attempt++) {
                    ds.send(new DatagramPacket(query, query.length, bcast, UDP_PORT));
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        ds.receive(p);
                        String r = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();
                        if (r.startsWith("ROBODOG")) found = p.getAddress().getHostAddress();
                    } catch (SocketTimeoutException ignored) {
                        // আবার চেষ্টা
                    }
                }
            } catch (IOException ignored) {
                // পাওয়া যায়নি
            }
            final String result = found;
            main.post(() -> cb.onResult(result));
        }, "wifi-discover").start();
    }
}
