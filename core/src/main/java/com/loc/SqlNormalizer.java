package com.loc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SqlNormalizer {

    private static final Pattern HINT      = Pattern.compile("/\\*\\+.*?\\*/", Pattern.DOTALL);
    private static final Pattern COMMENT   = Pattern.compile("/\\*.*?\\*/|--[^\\n]*", Pattern.DOTALL);
    private static final Pattern WS        = Pattern.compile("\\s+");
    private static final Pattern MYBATIS   = Pattern.compile("#\\{[^}]*\\}|\\$\\{[^}]*\\}");
    private static final Pattern BIND_VAR  = Pattern.compile(":\"[^\"]*\"|:\\d+|:[A-Za-z_]\\w*");
    private static final Pattern JDBC_Q    = Pattern.compile("\\?");
    private static final Pattern IN_LIST   = Pattern.compile(
            "\\bIN\\s*\\(\\s*(?:[:?]\\w*\\s*,\\s*)*[:?]\\w*\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DYN_TAG   = Pattern.compile(
            "</?\\s*(if|where|trim|set|choose|when|otherwise|bind|foreach|include|sql)"
          + "(?:(?:\"[^\"]*\"|'[^']*')|[^>])*?>", Pattern.CASE_INSENSITIVE);

    /** 对 SQL 文本做结构归一化（不改变语义结构） */
    public static String normalize(String sql) {
        if (sql == null) return "";
        String s = sql;
        s = HINT.matcher(s).replaceAll(" ");
        s = COMMENT.matcher(s).replaceAll(" ");
        s = stripDynamicTags(s);                          // 剥掉 MyBatis 动态标签，<where> 转成 WHERE
        s = MYBATIS.matcher(s).replaceAll(":P");          // #{x} / ${x} -> 占位符
        s = JDBC_Q.matcher(s).replaceAll(":P");           // JDBC ?
        s = BIND_VAR.matcher(s).replaceAll(":P");         // :1 / :B1 / :"SYS_B_0"
        s = renumber(s);                                  // :P :P :P -> :1 :2 :3
        s = IN_LIST.matcher(s).replaceAll("IN (?)");      // IN 列表压缩
        s = WS.matcher(s).replaceAll(" ");
        s = s.trim().toUpperCase();
        return s;
    }

    /** 剥离动态标签后的"纯 SQL 骨架文本"，用于相似度计算 */
    public static String stripDynamic(String sql) {
        if (sql == null) return "";
        String s = stripDynamicTags(sql);
        s = WS.matcher(s).replaceAll(" ");
        return s.trim();
    }

    /** 把 <where> 标签替换为 WHERE，其余动态标签替换为空格；同时去掉 WHERE 后的第一个 AND/OR */
    private static String stripDynamicTags(String sql) {
        Matcher m = DYN_TAG.matcher(sql);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String tag = m.group(0).trim().toUpperCase();
            String repl;
            if (tag.startsWith("<WHERE")) repl = " WHERE ";
            else repl = " ";
            m.appendReplacement(sb, repl);
        }
        m.appendTail(sb);
        String s = sb.toString();
        s = s.replaceAll("(?i)\\bWHERE\\s+AND\\b", "WHERE");
        s = s.replaceAll("(?i)\\bWHERE\\s+OR\\b", "WHERE");
        return s;
    }

    private static String renumber(String s) {
        Matcher m = Pattern.compile(":P").matcher(s);
        StringBuffer sb = new StringBuffer();   // Java 8: appendReplacement 仅支持 StringBuffer
        int[] n = {0};
        while (m.find()) m.appendReplacement(sb, ":" + (++n[0]));
        m.appendTail(sb);
        return sb.toString();
    }
}