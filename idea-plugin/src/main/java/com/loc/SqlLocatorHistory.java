package com.loc;

import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/** 管理慢 SQL 查询历史记录（存储在项目根 .sql-locator-history，不触碰源码）。 */
public class SqlLocatorHistory {

    private static final int MAX_ENTRIES = 50;

    public static final class Entry {
        public final long timestamp;
        public final String sql;
        public final int hitCount;
        public final int indexSize;
        public final List<SqlLocation> locations;

        public Entry(long timestamp, String sql, int hitCount, int indexSize, List<SqlLocation> locations) {
            this.timestamp = timestamp;
            this.sql = sql;
            this.hitCount = hitCount;
            this.indexSize = indexSize;
            this.locations = locations == null ? Collections.emptyList() : locations;
        }

        public String formatTime() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            sdf.setTimeZone(TimeZone.getDefault());
            return sdf.format(new Date(timestamp));
        }

        public String summary() {
            String sqlShort = sql.length() > 60 ? sql.substring(0, 60) + "..." : sql;
            return "[" + formatTime() + "] " + hitCount + " 条命中 | " + sqlShort;
        }
    }

    public static List<Entry> load(Project project) {
        Path file = historyFile(project);
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            List<Entry> out = new ArrayList<>();
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                if (line.trim().isEmpty()) continue;
                String[] p = line.split("\t", -1);
                if (p.length < 4) continue;
                try {
                    long ts = Long.parseLong(p[0]);
                    int hitCount = Integer.parseInt(p[1]);
                    int indexSize = Integer.parseInt(p[2]);
                    String sql = unesc(p[3]);
                    List<SqlLocation> locs = Collections.emptyList();
                    if (p.length >= 5) {
                        locs = decodeLocations(p[4]);
                    }
                    out.add(new Entry(ts, sql, hitCount, indexSize, locs));
                } catch (NumberFormatException ignored) {}
            }
            return out;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    public static void add(Project project, String sql, int hitCount, int indexSize) {
        add(project, sql, hitCount, indexSize, Collections.emptyList());
    }

    public static void add(Project project, String sql, int hitCount, int indexSize, List<SqlLocation> locations) {
        if (project == null || sql == null || sql.trim().isEmpty()) return;
        List<Entry> list = load(project);
        list.add(0, new Entry(System.currentTimeMillis(), sql.trim(), hitCount, indexSize, locations));
        while (list.size() > MAX_ENTRIES) list.remove(list.size() - 1);
        save(project, list);
    }

    private static void save(Project project, List<Entry> list) {
        Path file = historyFile(project);
        StringBuilder sb = new StringBuilder();
        for (Entry e : list) {
            sb.append(e.timestamp).append('\t')
              .append(e.hitCount).append('\t')
              .append(e.indexSize).append('\t')
              .append(esc(e.sql)).append('\t')
              .append(encodeLocations(e.locations)).append('\n');
        }
        try {
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException ignored) {}
    }

    private static Path historyFile(Project project) {
        return Paths.get(project.getBasePath(), ".sql-locator-history");
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unesc(String s) {
        return s.replace("\\t", "\t").replace("\\n", "\n").replace("\\r", "\r").replace("\\\\", "\\");
    }

    private static String encodeLocations(List<SqlLocation> locs) {
        if (locs == null || locs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (SqlLocation h : locs) {
            if (sb.length() > 0) sb.append('\u0002');
            sb.append(esc(h.key)).append('\u0001')
              .append(esc(h.path)).append('\u0001')
              .append(h.line).append('\u0001')
              .append(esc(h.stmtType)).append('\u0001')
              .append(h.level).append('\u0001')
              .append(h.score);
        }
        return sb.toString();
    }

    private static List<SqlLocation> decodeLocations(String payload) {
        List<SqlLocation> out = new ArrayList<>();
        if (payload == null || payload.isEmpty()) return out;
        for (String rec : payload.split("\u0002")) {
            if (rec.isEmpty()) continue;
            String[] f = rec.split("\u0001", -1);
            if (f.length != 6) continue;
            try {
                out.add(new SqlLocation(unesc(f[0]), unesc(f[1]), Integer.parseInt(f[2]),
                        unesc(f[3]), Integer.parseInt(f[4]), Double.parseDouble(f[5])));
            } catch (Exception ignored) {}
        }
        return out;
    }
}
