package com.loc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Fingerprint {

    /** FROM/JOIN/UPDATE/INTO 后的 表名+别名 二元组，保序；支持 schema.table 多级表名；负向断言排除 SQL 关键字被误当别名 */
    private static final Pattern TABLE = Pattern.compile(
            "\\b(FROM|JOIN|UPDATE|INTO)\\s+([A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)*)"
          + "(?:\\s+(?!(?:SET|VALUES|WHERE|ON|USING|GROUP|ORDER|HAVING|LIMIT|UNION|LEFT|RIGHT|"
          + "INNER|OUTER|FULL|CROSS|STRAIGHT_JOIN|FOR|AS)\\b)([A-Za-z_][A-Za-z0-9_$]*))?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WHERE_CLAUSE = Pattern.compile(
            "\\bWHERE\\b(.*?)\\s*(?=\\b(?:GROUP\\s+BY|ORDER\\s+BY|HAVING|LIMIT|UNION|INTERSECT|EXCEPT|FOR\\s+UPDATE|OFFSET)\\b|$)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern JOIN_CLAUSE = Pattern.compile(
            "\\b(?:(LEFT|RIGHT|FULL)(?:\\s+OUTER)?|INNER|CROSS|STRAIGHT_)?JOIN\\s+"
          + "([A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)*)(?:\\s+([A-Za-z_][A-Za-z0-9_$]*))?\\s*"
          + "(?=ON|USING)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ON_CLAUSE = Pattern.compile(
            "\\bON\\b(.*?)\\s*(?=\\b(?:WHERE|GROUP\\s+BY|ORDER\\s+BY|HAVING|LIMIT|UNION|INTERSECT|EXCEPT|OFFSET|(?:LEFT|RIGHT|FULL|INNER|CROSS|STRAIGHT_)?JOIN)\\b|$)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern USING_CLAUSE = Pattern.compile(
            "\\bUSING\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_$]*(?:\\s*,\\s*[A-Za-z_][A-Za-z0-9_$]*)*)\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EQ_PAIR = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)?)\\s*=\\s*([A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)?)");

    private static final Pattern PREDICATE_COL = Pattern.compile(
            "^\\s*(?:NOT\\s+)?([A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)?)\\s*(?:=|<>|!=|<=|>=|<|>|\\bLIKE\\b|\\bIN\\b|\\bIS\\b|\\bBETWEEN\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern AND_OR_SPLIT = Pattern.compile("\\b(?:AND|OR)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern STATEMENT_TYPE = Pattern.compile(
            "\\b(SELECT|UPDATE|INSERT|DELETE)\\b", Pattern.CASE_INSENSITIVE);

    public static String of(String normalizedSql) {
        try {
            byte[] b = MessageDigest.getInstance("MD5")
                    .digest(normalizedSql.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", b[i]));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(normalizedSql.hashCode());
        }
    }

    /** 骨架：["T_ORDER:O","T_CUSTOMER:C",...]，空表名序列兜底 */
    public static List<String> skeleton(String normalizedSql) {
        List<String> out = new ArrayList<>();
        Matcher m = TABLE.matcher(normalizedSql);
        while (m.find()) {
            String tab = m.group(2).toUpperCase();
            String alias = m.group(3);
            out.add(alias == null ? tab : tab + ":" + alias.toUpperCase());
        }
        return out;
    }

    public static String skeletonKey(List<String> skel) {
        return String.join("|", skel);
    }

    /** 从骨架生成别名映射：alias -> table；无别名时 table -> table */
    public static Map<String, String> aliasMap(List<String> skeleton) {
        Map<String, String> map = new HashMap<>();
        for (String s : skeleton) {
            int i = s.indexOf(':');
            String table = i < 0 ? s : s.substring(0, i);
            String alias = i < 0 ? s : s.substring(i + 1);
            map.put(alias, table);
            map.put(table, table);
        }
        return map;
    }

    /** 把括号（含子查询、函数调用）内容替换为 ##SUB##，避免 WHERE/JOIN 提取误入内部 */
    public static String maskParentheses(String normalizedSql) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        int start = -1;
        int lastEnd = 0;
        int n = normalizedSql.length();
        for (int i = 0; i < n; i++) {
            char c = normalizedSql.charAt(i);
            if (c == '\'') {
                int endQuote = normalizedSql.indexOf('\'', i + 1);
                if (endQuote < 0) { sb.append(normalizedSql.substring(i)); break; }
                i = endQuote;
                continue;
            }
            if (c == '(') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == ')') {
                if (depth > 0) {
                    depth--;
                    if (depth == 0) {
                        sb.append(normalizedSql, lastEnd, start).append("##SUB##");
                        lastEnd = i + 1;
                    }
                }
            }
        }
        sb.append(normalizedSql.substring(lastEnd));
        return sb.toString();
    }

    /** 提取 WHERE 条件列集合（纯列名，忽略顺序与表前缀） */
    public static Set<String> whereColumns(String normalizedSql, List<String> skeleton) {
        String masked = maskParentheses(normalizedSql);
        Matcher wm = WHERE_CLAUSE.matcher(masked);
        if (!wm.find()) return Collections.emptySet();
        String body = wm.group(1).trim();
        if (body.isEmpty()) return Collections.emptySet();
        // 去掉 WHERE 后可能出现的第一个 AND/OR（来自 <where> 标签内容）
        if (body.toUpperCase().startsWith("AND ") || body.toUpperCase().startsWith("OR ")) {
            body = body.substring(body.indexOf(' ')).trim();
        }
        Map<String, String> aliasMap = aliasMap(skeleton);
        String primary = skeleton.isEmpty() ? null : skeleton.get(0).split(":")[0];
        Set<String> cols = new TreeSet<>();
        for (String pred : AND_OR_SPLIT.split(body)) {
            Matcher pm = PREDICATE_COL.matcher(pred.trim());
            if (pm.find()) {
                String col = normalizeColumn(pm.group(1), aliasMap, primary, false);
                if (col != null) cols.add(col);
            }
        }
        return cols;
    }

    /** JOIN 信息：表集合与 ON/USING 等式集合 */
    public static final class JoinInfo {
        public final Set<String> tables;
        public final Set<String> onPairs;
        public JoinInfo(Set<String> tables, Set<String> onPairs) {
            this.tables = tables;
            this.onPairs = onPairs;
        }
        public String tablesKey() { return String.join("|", new TreeSet<>(tables)); }
        public String onKey() { return String.join("|", new TreeSet<>(onPairs)); }
    }

    public static JoinInfo joinInfo(String normalizedSql, List<String> skeleton) {
        String masked = maskParentheses(normalizedSql);
        Map<String, String> aliasMap = aliasMap(skeleton);
        String primary = skeleton.isEmpty() ? null : skeleton.get(0).split(":")[0];
        Set<String> tables = new LinkedHashSet<>();
        Set<String> onPairs = new TreeSet<>();
        String previousTable = primary;
        Matcher jm = JOIN_CLAUSE.matcher(masked);
        while (jm.find()) {
            String joinTable = jm.group(2).toUpperCase();
            String joinAlias = jm.group(3) == null ? joinTable : jm.group(3).toUpperCase();
            tables.add(joinTable);
            int afterJoin = jm.end();
            String tail = masked.substring(afterJoin).trim();
            if (tail.toUpperCase().startsWith("USING")) {
                Matcher um = USING_CLAUSE.matcher(tail);
                if (um.find()) {
                    for (String col : um.group(1).split("\\s*,\\s*")) {
                        col = col.trim().toUpperCase();
                        if (previousTable != null) {
                            onPairs.add(canonPair(previousTable, col, joinTable, col));
                        }
                    }
                }
            } else if (tail.toUpperCase().startsWith("ON")) {
                Matcher om = ON_CLAUSE.matcher(tail);
                if (om.find()) {
                    String onBody = om.group(1);
                    for (String pred : AND_OR_SPLIT.split(onBody)) {
                        Matcher em = EQ_PAIR.matcher(pred.trim());
                        if (em.find()) {
                            String left = normalizeColumn(em.group(1), aliasMap, previousTable, true);
                            String right = normalizeColumn(em.group(2), aliasMap, joinTable, true);
                            if (left != null && right != null) {
                                onPairs.add(canonPairStr(left, right));
                            }
                        }
                    }
                }
            }
            previousTable = joinTable;
        }
        return new JoinInfo(tables, onPairs);
    }

    /** 从归一化 SQL 推断语句类型；支持 WITH ... SELECT 等 CTE */
    public static String stmtType(String normalizedSql) {
        if (normalizedSql == null) return null;
        String s = normalizedSql.trim();
        int i = 0, n = s.length();
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        int j = i;
        while (j < n && Character.isLetter(s.charAt(j))) j++;
        if (j >= n) return null;
        String kw = s.substring(i, j).toUpperCase();
        if ("WITH".equals(kw)) {
            Matcher m = STATEMENT_TYPE.matcher(s);
            if (m.find()) return m.group(1).toUpperCase();
            return null;
        }
        if ("SELECT".equals(kw) || "UPDATE".equals(kw) || "INSERT".equals(kw) || "DELETE".equals(kw)) return kw;
        return null;
    }

    /** 列名归一化：去掉别名前缀，返回纯列名（WHERE）或 TABLE.COLUMN（JOIN） */
    private static String normalizeColumn(String raw, Map<String, String> aliasMap, String fallbackTable, boolean keepTablePrefix) {
        if (raw == null) return null;
        raw = raw.toUpperCase().trim();
        int dot = raw.indexOf('.');
        if (dot >= 0) {
            String prefix = raw.substring(0, dot);
            String col = raw.substring(dot + 1);
            String table = aliasMap.get(prefix);
            if (table == null) table = prefix;
            return keepTablePrefix ? table + "." + col : col;
        }
        if (fallbackTable != null && keepTablePrefix) return fallbackTable + "." + raw;
        return raw;
    }

    private static String canonPair(String t1, String c1, String t2, String c2) {
        String a = t1 + "." + c1;
        String b = t2 + "." + c2;
        return a.compareTo(b) <= 0 ? a + "=" + b : b + "=" + a;
    }

    private static String canonPairStr(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "=" + b : b + "=" + a;
    }
}
