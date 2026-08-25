package com.smartmatrix.obd;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class ObdEngine {
    public static class Data {
        public int rpm = -1;
        public double maf = Double.NaN;
        public double stft = Double.NaN;
        public double ltft = Double.NaN;
        public double o2 = Double.NaN;
        public double ect = Double.NaN;
        public double tps = Double.NaN;
        public double map = Double.NaN;
        public double fuelPressure = Double.NaN;
        public double batteryVoltage = Double.NaN;

        public boolean rpmAvailable() { return rpm >= 0; }
        public boolean mafAvailable() { return !Double.isNaN(maf); }
        public boolean stftAvailable() { return !Double.isNaN(stft); }
        public boolean ltftAvailable() { return !Double.isNaN(ltft); }
        public boolean o2Available() { return !Double.isNaN(o2); }
        public boolean ectAvailable() { return !Double.isNaN(ect); }
        public boolean tpsAvailable() { return !Double.isNaN(tps); }
        public boolean mapAvailable() { return !Double.isNaN(map); }
        public boolean fuelPressureAvailable() { return !Double.isNaN(fuelPressure); }
        public boolean batteryAvailable() { return !Double.isNaN(batteryVoltage); }
    }

    private static final Pattern HEX_BYTE = Pattern.compile("(?i)(?<![0-9A-F])[0-9A-F]{2}(?![0-9A-F])");

    public static Data parse(String pid, String raw) {
        Data d = new Data();
        if (pid == null || raw == null) return d;

        List<Integer> bytes = hexBytes(raw);
        int responseMode = responseModeFor(pid);
        int requestedPid = Integer.parseInt(pid.substring(2), 16);
        int idx = findResponse(bytes, responseMode, requestedPid);

        try {
            if (idx < 0) return d;
            switch (requestedPid) {
                case 0x05:
                    if (idx + 2 < bytes.size()) d.ect = bytes.get(idx + 2) - 40.0;
                    break;
                case 0x06:
                    if (idx + 2 < bytes.size()) d.stft = trim(bytes.get(idx + 2));
                    break;
                case 0x07:
                    if (idx + 2 < bytes.size()) d.ltft = trim(bytes.get(idx + 2));
                    break;
                case 0x0A:
                    if (idx + 2 < bytes.size()) d.fuelPressure = bytes.get(idx + 2) * 3.0;
                    break;
                case 0x0B:
                    if (idx + 2 < bytes.size()) d.map = bytes.get(idx + 2);
                    break;
                case 0x0C:
                    if (idx + 3 < bytes.size()) {
                        d.rpm = ((bytes.get(idx + 2) * 256) + bytes.get(idx + 3)) / 4;
                    }
                    break;
                case 0x0F:
                    // IAT is intentionally not assigned to MAF.
                    break;
                case 0x10:
                    if (idx + 3 < bytes.size()) {
                        d.maf = ((bytes.get(idx + 2) * 256) + bytes.get(idx + 3)) / 100.0;
                    }
                    break;
                case 0x11:
                    if (idx + 2 < bytes.size()) d.tps = bytes.get(idx + 2) * 100.0 / 255.0;
                    break;
                case 0x14:
                    if (idx + 2 < bytes.size()) d.o2 = bytes.get(idx + 2) / 200.0;
                    break;
                case 0x42:
                    if (idx + 3 < bytes.size()) {
                        d.batteryVoltage =
                                ((bytes.get(idx + 2) * 256) + bytes.get(idx + 3)) / 1000.0;
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) {
            // Keep the field unavailable rather than inventing a value.
        }
        return d;
    }

    public static boolean hasPositivePidResponse(String pid, String raw) {
        if (raw == null) return false;
        List<Integer> bytes = hexBytes(raw);
        int requestedPid = Integer.parseInt(pid.substring(2), 16);
        return findResponse(bytes, responseModeFor(pid), requestedPid) >= 0;
    }

    private static int responseModeFor(String pid) {
        int mode = Integer.parseInt(pid.substring(0, 2), 16);
        return (mode + 0x40) & 0xFF;
    }

    private static int findResponse(List<Integer> bytes, int responseMode, int requestedPid) {
        for (int i = 0; i + 1 < bytes.size(); i++) {
            if (bytes.get(i) == responseMode && bytes.get(i + 1) == requestedPid) {
                return i;
            }
        }
        return -1;
    }

    private static double trim(int a) {
        return (a - 128) * 100.0 / 128.0;
    }

    public static List<Integer> hexBytes(String raw) {
        List<Integer> out = new ArrayList<>();
        Matcher m = HEX_BYTE.matcher(raw == null ? "" : raw);
        while (m.find()) {
            try {
                out.add(Integer.parseInt(m.group(), 16));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    public static List<String> parseDtc(String raw, int responseMode) {
        List<Integer> bytes = hexBytes(raw);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < bytes.size(); i++) {
            if (bytes.get(i) != responseMode) continue;
            for (int j = i + 1; j + 1 < bytes.size(); j += 2) {
                int hi = bytes.get(j);
                int lo = bytes.get(j + 1);
                if (hi == 0 && lo == 0) continue;
                result.add(decodeDtc(hi, lo));
            }
            if (!result.isEmpty()) break;
        }
        return result;
    }

    private static String decodeDtc(int hi, int lo) {
        String[] letters = {"P", "C", "B", "U"};
        int letter = (hi >> 6) & 0x03;
        int digit1 = (hi >> 4) & 0x03;
        int digit2 = hi & 0x0F;
        int digit3 = (lo >> 4) & 0x0F;
        int digit4 = lo & 0x0F;
        return String.format(Locale.US, "%s%d%d%d%d",
                letters[letter], digit1, digit2, digit3, digit4);
    }

    public static double parseAdapterVoltage(String raw) {
        if (raw == null) return Double.NaN;
        Matcher m = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*V").matcher(raw);
        if (!m.find()) return Double.NaN;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    public static String analyze(Data d) {
        double trim = 0;
        boolean hasTrim = false;
        if (d.stftAvailable()) { trim += d.stft; hasTrim = true; }
        if (d.ltftAvailable()) { trim += d.ltft; hasTrim = true; }

        if (hasTrim && trim >= 35 && d.mafAvailable() && d.rpmAvailable() && d.rpm > 0)
            return "انحراف Fuel Trim مرتفع: افحص تهريب الهواء/العادم، MAF، ضغط الوقود والبخاخات باختبارات تأكيدية.";
        if (hasTrim && trim >= 20)
            return "Fuel Trim مرتفع: خليط فقير محتمل؛ راقب الاتجاه ثم اختبر السبب.";
        if (hasTrim && trim <= -20)
            return "Fuel Trim منخفض: خليط غني محتمل؛ راقب الاتجاه ثم اختبر ضغط الوقود والبخاخات وMAF.";
        if (d.rpmAvailable() && d.rpm < 650)
            return "الخمول منخفض؛ راقب TPS وMAF وMAP وFuel Trim وابحث عن السبب.";
        if (!d.rpmAvailable() && !d.ectAvailable() && !d.mapAvailable())
            return "لا توجد استجابة PID صالحة حتى الآن.";
        return "لا يوجد انحراف حاسم في البيانات المتاحة حالياً.";
    }

    public static String formatDtcList(List<String> dtcs) {
        if (dtcs == null || dtcs.isEmpty()) return "لا توجد أكواد مؤكدة.";
        Set<String> unique = new LinkedHashSet<>(dtcs);
        StringBuilder b = new StringBuilder();
        for (String code : unique) {
            if (b.length() > 0) b.append('\n');
            b.append(code);
        }
        return b.toString();
    }
}
