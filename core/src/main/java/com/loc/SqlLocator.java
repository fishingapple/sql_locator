package com.loc;

import java.util.*;

public final class SqlLocator {

    private final List<IndexBuilder.Entry> index;

    public SqlLocator(List<IndexBuilder.Entry> index) { this.index = index; }

    /** 索引条目数，用于诊断 */
    public int size() { return index.size(); }

    /**
     * 四级匹配，核心约束：
     * <ul>
     *   <li>L1 指纹完全相等</li>
     *   <li>L2 骨架/表集合一致，且语句类型、WHERE 条件列、JOIN 条件均一致</li>
     *   <li>L3 主表一致、表集合部分重叠（>=0.5），且条件/JOIN 一致</li>
     *   <li>L4 主表一致、重合度较低（>=0.2），且条件/JOIN 一致</li>
     * </ul>
     * 类型不同、主表不同、条件/JOIN 不一致的条目会被直接过滤。
     */
    public List<SqlLocation> search(String sqlText, int topN) {
        String norm = SqlNormalizer.normalize(sqlText);
        String fp = Fingerprint.of(norm);
        List<String> skel = Fingerprint.skeleton(norm);
        String skelKey = Fingerprint.skeletonKey(skel);
        Set<String> tabs = tableSet(skel);
        String primary = primaryTable(skel);
        String qType = Fingerprint.stmtType(norm);
        String qWhereKey = keyOf(Fingerprint.whereColumns(norm, skel));
        Fingerprint.JoinInfo qJoin = Fingerprint.joinInfo(norm, skel);
        String qJoinTablesKey = qJoin.tablesKey();
        String qJoinOnKey = qJoin.onKey();

        List<SqlLocation> res = new ArrayList<>();
        for (IndexBuilder.Entry e : index) {
            int level; double score;
            if (e.fp().equals(fp)) { level = 1; score = 1.0; }
            else {
                // 类型硬过滤
                if (qType == null || !qType.equals(e.type())) continue;

                // L2：骨架完全一致 + 条件/JOIN 一致
                if (e.skelKey().equals(skelKey) && !skelKey.isEmpty()
                        && eq(e.whereColsKey(), qWhereKey)
                        && eq(e.joinTablesKey(), qJoinTablesKey)
                        && eq(e.joinOnKey(), qJoinOnKey)) {
                    level = 2; score = 0.9;
                } else {
                    Set<String> et = tableSetFromKey(e.skelKey());
                    Set<String> inter = new HashSet<>(tabs);
                    inter.retainAll(et);
                    if (inter.isEmpty()) continue;
                    String ePrimary = primaryTableFromKey(e.skelKey());
                    if (primary == null || !primary.equals(ePrimary)) continue;

                    // 表集合相同可进 L2，但仍要求条件/JOIN 一致
                    if (tabs.equals(et)
                            && eq(e.whereColsKey(), qWhereKey)
                            && eq(e.joinTablesKey(), qJoinTablesKey)
                            && eq(e.joinOnKey(), qJoinOnKey)) {
                        level = 2; score = 0.85;
                    } else {
                        // 条件 / JOIN 硬过滤
                        if (!eq(e.whereColsKey(), qWhereKey)) continue;
                        if (!eq(e.joinTablesKey(), qJoinTablesKey)) continue;
                        if (!eq(e.joinOnKey(), qJoinOnKey)) continue;

                        score = (double) inter.size() / Math.max(tabs.size(), et.size());
                        if (score >= 0.5) level = 3;
                        else level = 4;
                    }
                }
            }
            score = Math.round(score * 100.0) / 100.0;
            if (level == 4 && score < 0.2) continue;
            res.add(new SqlLocation(e.key(), e.path(), e.line(), e.type(), level, score));
        }
        res.sort((a, b) -> { int c = Integer.compare(a.level, b.level);
            return c != 0 ? c : Double.compare(b.score, a.score); });
        return res.subList(0, Math.min(topN, res.size()));
    }

    private static boolean eq(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    private static String keyOf(Set<String> set) {
        return String.join("|", new TreeSet<>(set));
    }

    /** 取骨架中的表名（去掉别名）集合 */
    private static Set<String> tableSet(List<String> skel) {
        Set<String> s = new HashSet<>();
        for (String t : skel) s.add(stripAlias(t));
        return s;
    }

    private static Set<String> tableSetFromKey(String key) {
        Set<String> s = new HashSet<>();
        if (key == null || key.isEmpty()) return s;
        for (String t : key.split("\\|")) {
            if (!t.isEmpty()) s.add(stripAlias(t));
        }
        return s;
    }

    private static String stripAlias(String t) {
        int i = t.indexOf(':');
        return i < 0 ? t : t.substring(0, i);
    }

    private static String primaryTable(List<String> skel) {
        if (skel.isEmpty()) return null;
        return stripAlias(skel.get(0));
    }

    private static String primaryTableFromKey(String key) {
        if (key == null || key.isEmpty()) return null;
        int sep = key.indexOf('|');
        String first = sep < 0 ? key : key.substring(0, sep);
        return stripAlias(first);
    }

    /** L4 兜底：生成 grep 命令 */
    public static String grepCmd(String sqlText) {
        List<String> tabs = Fingerprint.skeleton(SqlNormalizer.normalize(sqlText));
        if (tabs.isEmpty()) return "grep -rniE 'SELECT|FROM' --include=*.xml --include=*.java src/";
        String pat = tabs.stream().limit(6).map(t -> "\\b" + t.split(":")[0] + "\\b")
                .reduce((a, b) -> a + "|" + b).orElse("");
        return "grep -rniE '" + pat + "' --include=*.xml --include=*.java src/";
    }
}
