package com.mar.forex.util;

import lombok.experimental.UtilityClass;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.mar.forex.domain.model.PaperTrade;

@UtilityClass
public class BacktestUtils {

    public String featureSignature(String maType, int fast, int slow, int atrP) {
        return String.format("v1|maType=%s|fast=%d|slow=%d|atrP=%d|features=maFast,maSlow,rsi,atr,ret1", maType, fast, slow, atrP);
    }

    public String readMeta(Path metaPath) {
        try {
            return Files.exists(metaPath) ? Files.readString(metaPath) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void writeMeta(Path metaPath, String sig) {
        try {
            Files.createDirectories(metaPath.getParent());
            Files.writeString(metaPath, sig);
        } catch (Exception ignored) {
        }
    }

    // ---- R metrics helpers ----
    public List<Double> realizedRSeries(List<PaperTrade> trades) {
        List<Double> out = new ArrayList<>(trades.size());
        for (var t : trades) {
            double risk = Math.abs(t.getEntry() - t.getStop());
            if (risk == 0) continue;
            double r = (t.getSide() == PaperTrade.Side.BUY)
                ? (t.getExit() - t.getEntry()) / risk
                : (t.getEntry() - t.getExit()) / risk;
            out.add(r);
        }
        return out;
    }

    public double profitFactor(List<Double> r) {
        double g = 0, l = 0;
        for (double x : r) {
            if (x > 0) g += x;
            else l += x;
        }
        return l == 0 ? (g == 0 ? 0 : Double.POSITIVE_INFINITY) : g / l;
    }

    public double maxDrawdownR(List<Double> r) {
        double peak = 0, equity = 0, maxDD = 0;
        for (double x : r) {
            equity += x;
            if (equity > peak) peak = equity;
            maxDD = Math.max(maxDD, peak - equity);
        }
        return maxDD;
    }

    public double sum(List<Double> xs) {
        double s = 0;
        for (double x : xs) s += x;
        return s;
    }

    public String format2(double v) {
        return String.format("%.2f", v);
    }

    public String format3(double v) {
        return String.format("%.3f", v);
    }

    /**
     * "13:00-17:00Z" -> [13,17]
     */
    public int[] parseSessionHoursUtc(String s) {
        try {
            if (s == null || s.isBlank()) return new int[]{13, 17};
            String core = s.trim().toUpperCase().replace("Z", "");
            String[] parts = core.split("-");
            String[] a = parts[0].split(":");
            String[] b = parts[1].split(":");
            return new int[]{Integer.parseInt(a[0]), Integer.parseInt(b[0])};
        } catch (Throwable __ignore) {
            return new int[]{13, 17};
        }
    }

    /**
     * Is timestamp within [startHour,endHour) UTC?
     */
    public boolean inSession(Object time, int startHourUtc, int endHourUtc) {
        try {
            ZonedDateTime zdt;
            if (time instanceof ZonedDateTime) {
                zdt = (ZonedDateTime) time;
            } else if (time instanceof OffsetDateTime) {
                zdt = ((OffsetDateTime) time).toZonedDateTime();
            } else if (time instanceof Instant) {
                zdt = ZonedDateTime.ofInstant((Instant) time, ZoneOffset.UTC);
            } else {
                zdt = ZonedDateTime.parse(time.toString());
            }
            int hour = zdt.withZoneSameInstant(ZoneOffset.UTC).getHour();
            return hour >= startHourUtc && hour < endHourUtc;
        } catch (Throwable __ignore) {
            return true;
        }
    }

    /**
     * Bars/day approx for granularity.
     */
    public int barsPerDayFor(String granularity) {
        if (granularity == null) return 24;
        switch (granularity.trim().toUpperCase()) {
            case "S5":
                return 24 * 60 * 12;
            case "M1":
                return 24 * 60;
            case "M5":
                return 24 * 12;
            case "M15":
                return 24 * 4;
            case "M30":
                return 24 * 2;
            case "H1":
                return 24;
            case "H4":
                return 6;
            case "D":
                return 1;
            case "W":
                return 1;
            default:
                return 24;
        }
    }

    public int toUtcDayKey(Object time) {
        try {
            ZonedDateTime zdt;
            if (time instanceof ZonedDateTime) {
                zdt = (ZonedDateTime) time;
            } else if (time instanceof OffsetDateTime) {
                zdt = ((OffsetDateTime) time).toZonedDateTime();
            } else if (time instanceof Instant) {
                zdt = ZonedDateTime.ofInstant((Instant) time, ZoneOffset.UTC);
            } else {
                zdt = ZonedDateTime.parse(time.toString());
            }
            ZonedDateTime u = zdt.withZoneSameInstant(ZoneOffset.UTC);
            return u.getYear() * 10000 + u.getMonthValue() * 100 + u.getDayOfMonth();
        } catch (Throwable __ignore) {
            return -1;
        }
    }

    /**
     * Percentile of the first `len` elements of arr (fixed 95th, legacy behavior).
     */
    public double percentile(double[] arr, int len) {
        if (arr == null || len <= 0) return Double.NaN;
        double[] tmp = new double[len];
        System.arraycopy(arr, 0, tmp, 0, len);
        Arrays.sort(tmp);
        if (len == 1) return tmp[0];
        double rank = (95.0 / 100.0) * (len - 1);
        int lo = (int) Math.floor(rank), hi = (int) Math.ceil(rank);
        if (lo == hi) return tmp[lo];
        double w = rank - lo;
        return tmp[lo] * (1.0 - w) + tmp[hi] * w;
    }

    /**
     * Percentile over a trailing window ending at endIdx (inclusive).
     */
    public double percentileOfWindow(double[] arr, int endIdx, int window, double pct) {
        if (arr == null || arr.length == 0 || endIdx < 0) return Double.NaN;
        int start = Math.max(0, endIdx - window + 1);
        int len = endIdx - start + 1;
        if (len <= 0) return Double.NaN;
        double[] tmp = new double[len];
        System.arraycopy(arr, start, tmp, 0, len);
        Arrays.sort(tmp);
        if (len == 1) return tmp[0];
        double rank = (pct / 100.0) * (len - 1);
        int lo = (int) Math.floor(rank), hi = (int) Math.ceil(rank);
        if (lo == hi) return tmp[lo];
        double w = rank - lo;
        return tmp[lo] * (1.0 - w) + tmp[hi] * w;
    }

    // ---- Calibration helpers ----
    public double[][] loadCalibration(java.nio.file.Path path) {
        try {
            if (!java.nio.file.Files.exists(path)) return null;
            var lines = java.nio.file.Files.readAllLines(path);
            var rows = new java.util.ArrayList<double[]>();
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("lo")) continue;
                String[] parts = line.split(",");
                if (parts.length < 3) continue;
                rows.add(new double[]{
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2])
                });
            }
            if (rows.isEmpty()) return null;
            double[][] table = new double[rows.size()][3];
            for (int i = 0; i < rows.size(); i++) table[i] = rows.get(i);
            return table;
        } catch (Throwable __ignore) {
            return null;
        }
    }

    /**
     * Map raw p to calibrated p using reliability table; fall back to raw if bin empty.
     */
    public double calibrate(double p, double[][] table) {
        if (table == null || !Double.isFinite(p)) return p;
        for (double[] row : table) {
            double lo = row[0], hi = row[1], wr = row[2];
            if (p >= lo && p < hi) return Double.isNaN(wr) ? p : Math.max(lo, Math.min(hi, wr));
        }
        return p;
    }
}
