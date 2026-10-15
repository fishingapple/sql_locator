package repro;

import com.loc.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 独立复现程序：不动生产源码，仅用真实管线（IndexBuilder + SqlLocator + Fingerprint）复现
 * monitor / trans-info 误匹配，并定向验证 skeleton 子查询泄漏、whereColumns UNION 分支丢失两处缺陷。
 * 运行：javac -cp build/classes -d repro/out repro/Repro.java
 *       java  -cp build/classes;repro/out repro.Repro
 */
public class Repro {

    // ---- 场景 A：真实 monitor/trans-info 误匹配（同表 + 同过滤列 + 无 JOIN，两条不同语句）----
    static final String TRANS_INFO = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n" +
        "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n" +
        "<mapper namespace=\"com.capital.mapper.InsuranceLoanMapper\">\n" +
        "  <select id=\"queryInsuranceLoanTransInfo\" resultType=\"map\">\n" +
        "    <![CDATA[\n" +
        "    SELECT t.PRODUCT_ID, t.ORGAN_CODE, COUNT(*) AS CNT\n" +
        "    FROM capital.capital_insurance_invoice t\n" +
        "    WHERE t.CREATE_TIME >= :1 - (:2 /1440) AND t.CREATE_TIME <= :3 - (:4 /1440)\n" +
        "    GROUP BY t.PRODUCT_ID, t.ORGAN_CODE\n" +
        "    ]]>\n" +
        "  </select>\n" +
        "</mapper>\n";

    static final String MONITOR = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n" +
        "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n" +
        "<mapper namespace=\"com.capital.mapper.InsuranceLoanMapper\">\n" +
        "  <select id=\"queryInsuranceLoanTransSuccessRate\" resultType=\"map\">\n" +
        "    <![CDATA[\n" +
        "    SELECT t.PRODUCT_ID, COUNT(*) AS CNT, SUM(t.AMOUNT) AS AMT\n" +
        "    FROM capital.capital_insurance_invoice t\n" +
        "    WHERE t.CREATE_TIME >= :1 AND t.CREATE_TIME <= :2\n" +
        "    ]]>\n" +
        "  </select>\n" +
        "</mapper>\n";

    // 用户实际输入 SQL（plain text，非 XML）
    static final String INPUT = "SELECT t.PRODUCT_ID, t.ORGAN_CODE, COUNT(*) CNT\n" +
        "FROM capital.capital_insurance_invoice t\n" +
        "WHERE t.CREATE_TIME >= :1 - (:2 /1440) AND t.CREATE_TIME <= :3 - (:4 /1440)\n" +
        "GROUP BY t.PRODUCT_ID, t.ORGAN_CODE";

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get("repro/_mappers");
        Files.createDirectories(dir);
        // monitor 先写入 -> 索引顺序排在 trans-info 之前（复现"错误 mapper 排在前"的真实表现）
        Files.write(dir.resolve("CapitalMonitorMapper.xml"), MONITOR.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("CapitalTransInfoMapper.xml"), TRANS_INFO.getBytes(StandardCharsets.UTF_8));

        List<IndexBuilder.Entry> entries = IndexBuilder.build(Collections.singletonList(dir));

        System.out.println("================ 场景 A：monitor / trans-info 误匹配 ================");
        System.out.println("--- 索引条目签名（fp/skelKey/whereColsKey/joinKeys）---");
        for (IndexBuilder.Entry e : entries) {
            System.out.printf("key=%-45s type=%s line=%d%n", e.key(), e.type(), e.line());
            System.out.printf("   fp=%s%n   skelKey=%s%n   whereColsKey=%s%n   joinTablesKey=[%s] joinOnKey=[%s]%n",
                e.fp(), e.skelKey(), e.whereColsKey(), e.joinTablesKey(), e.joinOnKey());
        }

        String norm = SqlNormalizer.normalize(INPUT);
        System.out.println("\n--- 输入 SQL 归一化 ---");
        System.out.println(norm);
        List<String> qSkel = Fingerprint.skeleton(norm);
        System.out.println("query skelKey      = " + Fingerprint.skeletonKey(qSkel));
        System.out.println("query whereColsKey = " + keyOf(Fingerprint.whereColumns(norm, qSkel)));

        SqlLocator loc = new SqlLocator(entries);
        List<SqlLocation> hits = loc.search(INPUT, 8);
        System.out.println("\n--- search 结果（按 level/score 排序，topN=8）---");
        for (SqlLocation h : hits) {
            System.out.printf("  level=%d score=%.2f  %s @ %s:%d%n", h.level, h.score, h.key, h.path, h.line);
        }
        System.out.println("\n>>> WHY: two DIFFERENT statements (one has GROUP BY+arithmetic, one does not) BOTH score 0.85 'skeleton-match'.");
        System.out.println(">>>      fp differs (not a fingerprint hit), yet the L2 signature (table-set + filter-cols + join) collides");
        System.out.println(">>>      -> tie broken by index order; the monitor indexed first is surfaced as a strong match, correct trans-info pushed aside.");

        System.out.println("\n\n================ 缺陷1：skeleton 子查询表泄漏 / 主表误判 ================");
        String subq = "SELECT x.col FROM (SELECT a.col FROM real_t a WHERE a.id=:1) x " +
                      "JOIN other_t y ON x.id=y.id WHERE y.flag=:2";
        String subqN = SqlNormalizer.normalize(subq);
        List<String> sk = Fingerprint.skeleton(subqN);
        System.out.println("SQL(归一化): " + subqN);
        System.out.println("skeleton    = " + sk);
        System.out.println("primary     = " + (sk.isEmpty() ? "null" : sk.get(0).split(":")[0]));
        System.out.println(">>> WHY: outer FROM is an inline view x (a subquery), yet skeleton treats the subquery's REAL_T as the primary table;");
        System.out.println(">>>      if another mapper's real primary is OTHER_T, their primaries differ -> L3 'primary-match' filter misfires (kills or keeps wrongly).");

        System.out.println("\n\n================ 缺陷2：whereColumns 仅抓第一个顶层 WHERE（UNION 分支丢失）================");
        String union = "SELECT a FROM t1 WHERE a.x=:1 UNION SELECT b FROM t2 WHERE b.y=:2";
        String unionN = SqlNormalizer.normalize(union);
        System.out.println("SQL(归一化): " + unionN);
        System.out.println("whereCols   = " + keyOf(Fingerprint.whereColumns(unionN, Fingerprint.skeleton(unionN))));
        System.out.println(">>> WHY: the second UNION branch's WHERE b.y=:2 is dropped entirely; the filter-column signature is incomplete,");
        System.out.println(">>>      which can both miss a true match (expected y but not counted) and cause false equality elsewhere.");
    }

    private static String keyOf(Set<String> set) {
        return String.join("|", new TreeSet<String>(set));
    }
}
