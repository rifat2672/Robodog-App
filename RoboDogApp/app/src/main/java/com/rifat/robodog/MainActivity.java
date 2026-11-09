package com.rifat.robodog;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements DogLink.Listener {

    private static final String DEFAULT_IP = "192.168.4.1";
    private static final long HEARTBEAT_MS = 250;      // চাপ ধরে রাখলে কত পরপর কমান্ড যাবে
    private static final int REQ_BLE = 1;

    private static final String[] LEG_NAMES = {"Front Left", "Front Right", "Back Left", "Back Right"};
    private static final int[] LEG_PINS = {26, 19, 27, 18};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private DogLink link;
    private String connectedInfo = "";

    // চলাফেরা
    private String holdDir;                   // "F", "B", "L", "R" অথবা null
    private Button[] padButtons;

    // ক্যালিব্রেশনের মান (ESP32 থেকে আসা)
    private final int[] trim = new int[4];
    private final TextView[] trimViews = new TextView[4];
    private final CheckBox[] invBoxes = new CheckBox[4];
    private boolean applyingStatus;           // ESP32 এর মান বসানোর সময় আবার কমান্ড না পাঠাতে

    // View
    private TextView txtStatus, txtModeHelp, txtSpeed, txtStride;
    private RadioButton rbBle;
    private RadioGroup rgGait;
    private View rowWifi, calPanel;
    private EditText edtIp;
    private Button btnFind, btnConnect, btnCalToggle;
    private CheckBox chkTap;
    private SeekBar sbSpeed, sbStride;

    // =========================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("robodog", MODE_PRIVATE);

        txtStatus = findViewById(R.id.txtStatus);
        txtModeHelp = findViewById(R.id.txtModeHelp);
        txtSpeed = findViewById(R.id.txtSpeed);
        txtStride = findViewById(R.id.txtStride);
        RadioGroup rgMode = findViewById(R.id.rgMode);
        rbBle = findViewById(R.id.rbBle);
        RadioButton rbWifi = findViewById(R.id.rbWifi);
        rgGait = findViewById(R.id.rgGait);
        rowWifi = findViewById(R.id.rowWifi);
        calPanel = findViewById(R.id.calPanel);
        edtIp = findViewById(R.id.edtIp);
        btnFind = findViewById(R.id.btnFind);
        btnConnect = findViewById(R.id.btnConnect);
        btnCalToggle = findViewById(R.id.btnCalToggle);
        chkTap = findViewById(R.id.chkTap);
        sbSpeed = findViewById(R.id.sbSpeed);
        sbStride = findViewById(R.id.sbStride);

        // ---------- সংযোগ ----------
        edtIp.setText(prefs.getString("ip", DEFAULT_IP));
        if (prefs.getBoolean("ble", false)) rbBle.setChecked(true);
        else rbWifi.setChecked(true);
        updateModeUi();
        rgMode.setOnCheckedChangeListener((g, id) -> updateModeUi());

        btnFind.setOnClickListener(v -> findOnWifi());
        btnConnect.setOnClickListener(v -> {
            if (link != null) disconnect();
            else startConnect();
        });

        // ---------- চলাফেরা ----------
        Button btnFwd = findViewById(R.id.btnFwd);
        Button btnBack = findViewById(R.id.btnBack);
        Button btnLeft = findViewById(R.id.btnLeft);
        Button btnRight = findViewById(R.id.btnRight);
        padButtons = new Button[]{btnFwd, btnBack, btnLeft, btnRight};
        setupPad(btnFwd, "F");
        setupPad(btnBack, "B");
        setupPad(btnLeft, "L");
        setupPad(btnRight, "R");
        findViewById(R.id.btnStop).setOnClickListener(v -> stopMove(true));

        chkTap.setChecked(prefs.getBoolean("tap", false));
        chkTap.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("tap", checked).apply();
            stopMove(true);
        });

        // ---------- গতি / ধাপ / হাঁটার ধরন ----------
        sbSpeed.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                txtSpeed.setText(String.valueOf(p));
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                send("SPD:" + s.getProgress());
            }
        });
        sbStride.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                txtStride.setText(getString(R.string.stride_value, p));
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                send("STR:" + s.getProgress());
            }
        });
        rgGait.setOnCheckedChangeListener((g, id) -> {
            if (!applyingStatus) send("GAIT:" + (id == R.id.rbGaitFast ? 1 : 0));
        });

        // ---------- ভঙ্গি ----------
        findViewById(R.id.btnStand).setOnClickListener(v -> action("stand"));
        findViewById(R.id.btnSit).setOnClickListener(v -> action("sit"));
        findViewById(R.id.btnLie).setOnClickListener(v -> action("lie"));
        findViewById(R.id.btnHi).setOnClickListener(v -> action("hi"));

        // ---------- ক্যালিব্রেশন ----------
        btnCalToggle.setOnClickListener(v -> {
            boolean show = calPanel.getVisibility() != View.VISIBLE;
            calPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            btnCalToggle.setText(show ? R.string.cal_hide : R.string.cal_show);
        });
        findViewById(R.id.btnCalStand).setOnClickListener(v -> {
            stopMove(false);
            send("CAL:0");
        });
        findViewById(R.id.btnCalFwd).setOnClickListener(v -> {
            stopMove(false);
            send("CAL:30");
        });
        findViewById(R.id.btnCalReset).setOnClickListener(v -> send("RST"));
        buildLegRows();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopMove(true);       // app ছোট করলে রোবট থামবে
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (link != null) {
            link.send("S");
            link.close();
            link = null;
        }
        super.onDestroy();
    }

    // ===================== সংযোগ =====================

    private void updateModeUi() {
        boolean ble = rbBle.isChecked();
        rowWifi.setVisibility(ble ? View.GONE : View.VISIBLE);
        txtModeHelp.setText(ble ? R.string.ble_help : R.string.wifi_help);
    }

    private void setModeEnabled(boolean enabled) {
        findViewById(R.id.rbWifi).setEnabled(enabled);
        rbBle.setEnabled(enabled);
        edtIp.setEnabled(enabled);
        btnFind.setEnabled(enabled);
    }

    private void startConnect() {
        boolean ble = rbBle.isChecked();
        prefs.edit().putBoolean("ble", ble).apply();

        DogLink newLink;
        if (ble) {
            if (!ensureBlePermissions()) return;     // অনুমতি পেলে onRequestPermissionsResult আবার ডাকবে
            BluetoothManager bm = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
            if (adapter == null || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                toast(getString(R.string.err_no_ble));
                return;
            }
            if (!adapter.isEnabled()) {
                try {
                    startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                } catch (SecurityException ignored) {
                }
                toast(getString(R.string.err_bt_off));
                return;
            }
            newLink = new BleLink(this, adapter, this);
            setStatus(getString(R.string.st_ble_searching), R.color.muted);
        } else {
            String ip = edtIp.getText().toString().trim();
            if (ip.isEmpty()) {
                ip = DEFAULT_IP;
                edtIp.setText(ip);
            }
            prefs.edit().putString("ip", ip).apply();
            newLink = new WifiLink(this, ip, this);
            setStatus(getString(R.string.st_wifi_connecting, ip), R.color.muted);
        }

        link = newLink;
        btnConnect.setText(R.string.cancel);
        setModeEnabled(false);
        newLink.connect();   // শেষে রাখা, কারণ ব্যর্থ হলে সাথে সাথে onDisconnected আসতে পারে
    }

    private void disconnect() {
        stopMove(true);
        DogLink l = link;
        link = null;
        if (l != null) l.close();
        onLinkDown(getString(R.string.st_disconnected), R.color.muted);
    }

    private void onLinkDown(String msg, int colorRes) {
        stopMove(false);
        setStatus(msg, colorRes);
        btnConnect.setText(R.string.connect);
        setModeEnabled(true);
    }

    @Override
    public void onConnected(DogLink l, String info) {
        if (l != link) return;
        connectedInfo = info;
        restoreConnectedStatus();
        btnConnect.setText(R.string.disconnect);
        send("?");          // ESP32 এর সেভ করা সেটিং নিয়ে আসি
    }

    @Override
    public void onDisconnected(DogLink l, String reason) {
        if (l != link) return;
        link = null;
        onLinkDown(reason, R.color.err);
        toast(reason);
    }

    @Override
    public void onLine(DogLink l, String line) {
        if (l != link) return;
        if (line.startsWith("ST:")) applyStatus(line.substring(3));
    }

    private boolean isConnected() {
        return link != null && link.isConnected();
    }

    private void send(String cmd) {
        if (isConnected()) link.send(cmd);
    }

    // ---------- WiFi তে RoboDog খোঁজা ----------
    private void findOnWifi() {
        btnFind.setEnabled(false);
        btnFind.setText(R.string.finding);
        WifiLink.discover(this, ip -> {
            btnFind.setText(R.string.find);
            btnFind.setEnabled(link == null);
            if (ip != null) {
                edtIp.setText(ip);
                prefs.edit().putString("ip", ip).apply();
                toast(getString(R.string.found_ip, ip));
            } else {
                toast(getString(R.string.not_found_ip));
            }
        });
    }

    // ---------- Bluetooth permission ----------
    private boolean ensureBlePermissions() {
        String[] needed;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed = new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        } else {
            needed = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        }
        List<String> missing = new ArrayList<>();
        for (String p : needed) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) return true;
        requestPermissions(missing.toArray(new String[0]), REQ_BLE);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_BLE) return;
        if (grantResults.length == 0) return;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                toast(getString(R.string.err_ble_perm));
                return;
            }
        }
        if (link == null) startConnect();
    }

    // ===================== চলাফেরা =====================

    @SuppressLint("ClickableViewAccessibility")
    private void setupPad(Button b, String dir) {
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.getParent().requestDisallowInterceptTouchEvent(true);   // scroll যেন touch কেড়ে না নেয়
                    v.setPressed(true);
                    if (chkTap.isChecked() && dir.equals(holdDir)) stopMove(true);
                    else startMove(dir);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    if (!chkTap.isChecked() && dir.equals(holdDir)) stopMove(true);
                    return true;
                default:
                    return true;
            }
        });
    }

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (holdDir == null) return;
            send(holdDir);
            handler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    private void startMove(String dir) {
        if (!isConnected()) {
            toast(getString(R.string.not_connected));
            return;
        }
        holdDir = dir;
        markActive(dir);
        handler.removeCallbacks(heartbeat);
        heartbeat.run();
        int msg;
        switch (dir) {
            case "F": msg = R.string.st_moving_f; break;
            case "B": msg = R.string.st_moving_b; break;
            case "L": msg = R.string.st_moving_l; break;
            default:  msg = R.string.st_moving_r; break;
        }
        setStatus(getString(msg), R.color.accent);
    }

    private void stopMove(boolean sendStop) {
        handler.removeCallbacks(heartbeat);
        boolean wasMoving = holdDir != null;
        holdDir = null;
        markActive(null);
        if (sendStop) send("S");
        if (wasMoving && isConnected()) restoreConnectedStatus();
    }

    private void markActive(String dir) {
        String[] dirs = {"F", "B", "L", "R"};
        for (int i = 0; i < padButtons.length; i++) {
            padButtons[i].setActivated(dirs[i].equals(dir));
        }
    }

    private void restoreConnectedStatus() {
        if (link == null) return;
        boolean ble = link instanceof BleLink;
        setStatus(getString(ble ? R.string.st_connected_ble : R.string.st_connected_wifi, connectedInfo), R.color.ok);
    }

    private void action(String name) {
        if (!isConnected()) {
            toast(getString(R.string.not_connected));
            return;
        }
        stopMove(false);
        send("ACT:" + name);
    }

    // ===================== ক্যালিব্রেশন =====================

    private void buildLegRows() {
        LinearLayout container = findViewById(R.id.legsContainer);
        for (int i = 0; i < 4; i++) {
            final int leg = i;
            View row = getLayoutInflater().inflate(R.layout.item_leg, container, false);
            ((TextView) row.findViewById(R.id.txtLegName))
                    .setText(getString(R.string.leg_label, LEG_NAMES[i], LEG_PINS[i]));
            trimViews[i] = row.findViewById(R.id.txtTrim);
            invBoxes[i] = row.findViewById(R.id.chkInv);
            row.findViewById(R.id.btnMinus).setOnClickListener(v -> changeTrim(leg, -1));
            row.findViewById(R.id.btnPlus).setOnClickListener(v -> changeTrim(leg, +1));
            invBoxes[i].setOnCheckedChangeListener((b, checked) -> {
                if (!applyingStatus) send("INV:" + leg + ":" + (checked ? 1 : 0));
            });
            container.addView(row);
        }
    }

    private void changeTrim(int leg, int delta) {
        if (!isConnected()) {
            toast(getString(R.string.not_connected));
            return;
        }
        trim[leg] = Math.max(-30, Math.min(30, trim[leg] + delta));
        trimViews[leg].setText(String.valueOf(trim[leg]));
        send("TRIM:" + leg + ":" + trim[leg]);
    }

    /** "5,30,0,t0,t1,t2,t3,i0,i1,i2,i3" — ESP32 এর সেভ করা মান UI তে বসাই */
    private void applyStatus(String csv) {
        String[] p = csv.split(",");
        if (p.length < 11) return;
        try {
            int speed = Integer.parseInt(p[0].trim());
            int stride = Integer.parseInt(p[1].trim());
            int gait = Integer.parseInt(p[2].trim());
            applyingStatus = true;
            sbSpeed.setProgress(speed);
            txtSpeed.setText(String.valueOf(speed));
            sbStride.setProgress(stride);
            txtStride.setText(getString(R.string.stride_value, stride));
            rgGait.check(gait == 1 ? R.id.rbGaitFast : R.id.rbGaitStable);
            for (int i = 0; i < 4; i++) {
                trim[i] = Integer.parseInt(p[3 + i].trim());
                trimViews[i].setText(String.valueOf(trim[i]));
                invBoxes[i].setChecked(Integer.parseInt(p[7 + i].trim()) != 0);
            }
        } catch (NumberFormatException ignored) {
            // ভাঙা লাইন, বাদ
        } finally {
            applyingStatus = false;
        }
    }

    // ===================== ছোটখাটো =====================

    private void setStatus(String text, int colorRes) {
        txtStatus.setText(text);
        txtStatus.setTextColor(getColor(colorRes));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** SeekBar listener এর ফাঁকা method গুলো */
    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar s) {
        }
    }
}
