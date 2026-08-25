package com.smartmatrix.obd;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.os.Handler;
import android.content.pm.PackageManager;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    TextView status, live, diagnosis, codes, log;
    ObdBluetooth obd = new ObdBluetooth();
    Handler handler = new Handler();
    boolean running = false;
    boolean ecuReady = false;
    ObdEngine.Data data = new ObdEngine.Data();

    String[] pids = {
            "010C", // RPM
            "0110", // MAF
            "0106", // STFT B1
            "0107", // LTFT B1
            "0105", // ECT
            "0111", // TPS
            "010B", // MAP
            "010A", // Fuel pressure
            "0114", // O2 B1S1
            "0142"  // Control module voltage
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        live = findViewById(R.id.live);
        diagnosis = findViewById(R.id.diagnosis);
        codes = findViewById(R.id.codes);
        log = findViewById(R.id.log);

        findViewById(R.id.connect).setOnClickListener(v -> chooseDevice());
        findViewById(R.id.start).setOnClickListener(v -> {
            if (!ecuReady) {
                status.setText("المحول غير متصل بـ ECU؛ لن تبدأ قراءة وهمية.");
                return;
            }
            running = !running;
            if (running) poll();
        });

        if (android.os.Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[] {
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            }, 100);
        }
    }

    void chooseDevice() {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) {
            status.setText("الهاتف لا يدعم Bluetooth");
            return;
        }

        Set<BluetoothDevice> paired;
        try {
            paired = a.getBondedDevices();
        } catch (SecurityException e) {
            status.setText("لم تُمنح صلاحية Bluetooth");
            return;
        }

        if (paired.isEmpty()) {
            status.setText("اقرن محول ELM327 من إعدادات Bluetooth أولاً");
            return;
        }

        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> macs = new ArrayList<>();
        for (BluetoothDevice d : paired) {
            try {
                names.add(d.getName() + "\n" + d.getAddress());
                macs.add(d.getAddress());
            } catch (SecurityException ignored) {}
        }

        new AlertDialog.Builder(this)
                .setTitle("اختر محول OBD")
                .setItems(names.toArray(new String[0]), (x, w) -> {
                    status.setText("جاري تهيئة المحول واختبار ECU...");
                    running = false;

                    new Thread(() -> {
                        ObdBluetooth.ConnectionResult result = obd.connect(macs.get(w));
                        ecuReady = result.ecuConnected;

                        runOnUiThread(() -> {
                            if (result.ecuConnected) {
                                status.setText("متصل بـ ECU فعلياً — " + result.message);
                            } else if (result.adapterConnected) {
                                status.setText("المحول متصل، لكن ECU غير مستجيب: " + result.message);
                            } else {
                                status.setText("فشل اتصال OBD: " + result.message);
                            }
                            refreshLog();
                        });
                    }).start();
                }).show();
    }

    void poll() {
        if (!running || !ecuReady) return;

        new Thread(() -> {
            for (String pid : pids) {
                String raw = obd.command(pid, 3500);
                ObdEngine.Data x = ObdEngine.parse(pid, raw);
                merge(x);
            }

            // 0142 is the ECU-reported control-module voltage. If the vehicle does not
            // expose that PID, ATRV gives the adapter-side vehicle supply voltage.
            if (!data.batteryAvailable()) {
                double adapterVoltage = ObdEngine.parseAdapterVoltage(
                        obd.command("ATRV", 2500));
                if (!Double.isNaN(adapterVoltage)) {
                    data.batteryVoltage = adapterVoltage;
                }
            }

            String dtcRaw = obd.command("03", 5000);
            List<String> confirmed = ObdEngine.parseDtc(dtcRaw, 0x43);

            String pendingRaw = obd.command("07", 5000);
            List<String> pending = ObdEngine.parseDtc(pendingRaw, 0x47);

            runOnUiThread(() -> {
                render();
                diagnosis.setText("التحليل اللحظي: " + ObdEngine.analyze(data));

                StringBuilder c = new StringBuilder();
                c.append("مؤكدة:\n").append(ObdEngine.formatDtcList(confirmed));
                c.append("\n\nمعلّقة:\n").append(ObdEngine.formatDtcList(pending));
                codes.setText(c.toString());
                refreshLog();
            });

            handler.postDelayed(this::poll, 250);
        }).start();
    }

    synchronized void merge(ObdEngine.Data x) {
        if (x.rpmAvailable()) data.rpm = x.rpm;
        if (x.mafAvailable()) data.maf = x.maf;
        if (x.stftAvailable()) data.stft = x.stft;
        if (x.ltftAvailable()) data.ltft = x.ltft;
        if (x.ectAvailable()) data.ect = x.ect;
        if (x.tpsAvailable()) data.tps = x.tps;
        if (x.mapAvailable()) data.map = x.map;
        if (x.fuelPressureAvailable()) data.fuelPressure = x.fuelPressure;
        if (x.o2Available()) data.o2 = x.o2;
        if (x.batteryAvailable()) data.batteryVoltage = x.batteryVoltage;
    }

    void render() {
        live.setText(String.format(Locale.US,
                "RPM: %s\nMAF: %s g/s\nSTFT B1: %s %%\nLTFT B1: %s %%\n" +
                        "O2 B1S1: %s V\nECT: %s °C\nTPS: %s %%\nMAP: %s kPa\n" +
                        "Fuel Pressure: %s kPa\nBattery Voltage: %s V",
                rpmText(), number(data.maf, 1), number(data.stft, 1),
                number(data.ltft, 1), number(data.o2, 3),
                number(data.ect, 0), number(data.tps, 1),
                number(data.map, 0), number(data.fuelPressure, 0),
                number(data.batteryVoltage, 3)));
    }

    String rpmText() {
        return data.rpmAvailable() ? String.valueOf(data.rpm) : "غير متاح";
    }

    String number(double value, int decimals) {
        if (Double.isNaN(value)) return "غير متاح";
        return String.format(Locale.US, "%." + decimals + "f", value);
    }

    void refreshLog() {
        List<String> entries = obd.getLogSnapshot();
        StringBuilder b = new StringBuilder();
        int start = Math.max(0, entries.size() - 80);
        for (int i = start; i < entries.size(); i++) {
            if (b.length() > 0) b.append('\n');
            b.append(entries.get(i));
        }
        b.append("\n\n").append(new Date());
        log.setText(b.toString());
    }

    @Override protected void onDestroy() {
        running = false;
        obd.close();
        super.onDestroy();
    }
}
