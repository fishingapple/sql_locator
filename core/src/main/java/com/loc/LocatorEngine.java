package com.loc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class LocatorEngine {

    public final Path indexFile;
    private volatile SqlLocator locator;

    public LocatorEngine(Path projectRoot) {
        this.indexFile = projectRoot.resolve(".sql-locator-index");
    }

    /**
     * 重建索引。
     * <p><b>只读约定</b>：本引擎绝不修改、删除、重命名任何被扫描的源文件；
     * 唯一的写操作是把索引写入 {@link #indexFile}（项目根下的 .sql-locator-index）。
     * 写入使用显式 CREATE + TRUNCATE_EXISTING + WRITE，杜绝追加/就地改写。
     */
    public void rebuild(List<Path> roots) throws IOException {
        List<IndexBuilder.Entry> entries = IndexBuilder.build(roots);
        Files.write(indexFile, serialize(entries).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        this.locator = new SqlLocator(entries);
    }

    public void load() throws IOException {
        if (!Files.exists(indexFile)) throw new IOException("索引不存在，请先 build");
        String content = new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8);
        List<IndexBuilder.Entry> entries = deserialize(content);
        this.locator = new SqlLocator(entries);
    }

    public List<SqlLocation> locate(String sqlText) {
        if (locator == null) throw new IllegalStateException("未加载索引");
        return locator.search(sqlText, 8);
    }

    public String grepFallback(String sqlText) { return SqlLocator.grepCmd(sqlText); }

    /** 已加载的索引条目数；未加载返回 -1 */
    public int indexSize() { return locator == null ? -1 : locator.size(); }

    /* ---- 极简序列化：一行一个字段，避免引入 JSON 库 ---- */
    private static String serialize(List<IndexBuilder.Entry> list) {
        return list.stream().map(e -> String.join("\t",
                e.fp(), e.skelKey(), esc(e.key()), e.path(), String.valueOf(e.line()), e.type(),
                esc(e.whereColsKey()), esc(e.joinTablesKey()), esc(e.joinOnKey())))
                .collect(Collectors.joining("\n"));
    }
    private static List<IndexBuilder.Entry> deserialize(String s) throws IOException {
        if (s.trim().isEmpty()) return new ArrayList<>();
        List<IndexBuilder.Entry> out = new ArrayList<>();
        for (String l : s.split("\n")) {
            if (l.isEmpty()) continue;
            String[] p = l.split("\t", -1);
            if (p.length != 9) {
                // 旧版索引字段数不一致，触发上层重建
                throw new IOException("索引格式已过期（列数 " + p.length + "，需要 9），请重建");
            }
            out.add(new IndexBuilder.Entry(p[0], p[1], unesc(p[2]), p[3], Integer.parseInt(p[4]), p[5],
                    unesc(p[6]), unesc(p[7]), unesc(p[8])));
        }
        return out;
    }
    private static String esc(String s) { return s.replace("\t", "\\t").replace("\n", "\\n"); }
    private static String unesc(String s) { return s.replace("\\t", "\t").replace("\\n", "\n"); }
}