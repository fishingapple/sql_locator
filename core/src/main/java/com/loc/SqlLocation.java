package com.loc;

public final class SqlLocation {
    public final String key;      // namespace.statementId 或 类名@注解行
    public final String path;     // 相对路径
    public final int line;
    public final String stmtType; // SELECT/UPDATE/INSERT/DELETE
    public final int level;       // 1..4
    public final double score;

    public SqlLocation(String key, String path, int line, String stmtType, int level, double score) {
        this.key = key; this.path = path; this.line = line;
        this.stmtType = stmtType; this.level = level; this.score = score;
    }

    public String badge() {
        switch (level) {
            case 1:  return "★指纹命中";
            case 2:  return "☆骨架一致";
            case 3:  return "~表重合";
            default: return "?弱相关";
        }
    }

    @Override public String toString() {
        return String.format("[%s] %-50s @ %s:%d", badge(), key, path, line);
    }
}