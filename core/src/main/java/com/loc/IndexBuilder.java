package com.loc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;

public final class IndexBuilder {

    /** 匹配整个语句块：开标签 + 正文 + 闭标签（正文用懒惰匹配，\1 反向引用保证开闭一致） */
    private static final Pattern STMT = Pattern.compile(
            "<\\s*(select|update|insert|delete)\\b([^>]*)>(.*?)</\\s*\\1\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** namespace 在 <mapper namespace="..."> 上，不在语句标签里 */
    private static final Pattern MAPPER_NS = Pattern.compile(
            "<\\s*mapper\\b[^>]*\\bnamespace\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_ATTR = Pattern.compile(
            "\\bid\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANNO = Pattern.compile(
            "@(Select|Update|Delete|Insert|Query)\\s*\\(\\s*\"(.*?)\"\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Java 8 兼容：用普通 final 类替代 record */
    public static final class Entry {
        private final String fp;
        private final String skelKey;
        private final String key;
        private final String path;
        private final int line;
        private final String type;
        private final String whereColsKey;
        private final String joinTablesKey;
        private final String joinOnKey;

        public Entry(String fp, String skelKey, String key, String path, int line, String type,
                     String whereColsKey, String joinTablesKey, String joinOnKey) {
            this.fp = fp; this.skelKey = skelKey; this.key = key;
            this.path = path; this.line = line; this.type = type;
            this.whereColsKey = whereColsKey;
            this.joinTablesKey = joinTablesKey;
            this.joinOnKey = joinOnKey;
        }
        public String fp()           { return fp; }
        public String skelKey()      { return skelKey; }
        public String key()          { return key; }
        public String path()         { return path; }
        public int    line()         { return line; }
        public String type()         { return type; }
        public String whereColsKey() { return whereColsKey; }
        public String joinTablesKey(){ return joinTablesKey; }
        public String joinOnKey()    { return joinOnKey; }
    }

    /**
     * 扫描 roots 下所有 mapper xml 与 java 文件；walkFileTree 对排除目录剪枝，不进入其子树。
     * <p><b>只读约定</b>：全程只用 Files.readAllBytes 读取文件内容，不打开任何写通道，
     * 不修改/删除/重命名/改属性任何文件；且不跟随符号链接（默认行为），不会越出 roots。
     */
    public static List<Entry> build(List<Path> roots) throws IOException {
        final List<Entry> out = Collections.synchronizedList(new ArrayList<Entry>());
        for (final Path root : roots) {
            if (!Files.exists(root)) continue;   // 目录不存在直接跳过，不抛异常
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return isExcluded(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String n = file.getFileName().toString();
                    if (n.endsWith(".xml") || n.endsWith(".java")) {
                        try {
                            if (n.endsWith(".xml")) out.addAll(scanXml(file, root));
                            else out.addAll(scanJava(file, root));
                        } catch (IOException ignore) {}
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;   // 单文件失败不影响整体
                }
            });
        }
        return out;
    }

    /** 按路径段精确匹配排除目录（避免 "out"/"build" 子串误伤 logout、about 等） */
    private static final Set<String> EXCLUDED_DIRS = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList("build", "target", "out", "dist", ".idea", ".git", ".svn", ".gradle",
                    "node_modules", ".workbuddy")));

    private static boolean isExcluded(Path p) {
        for (Path seg : p) {
            if (EXCLUDED_DIRS.contains(seg.toString())) return true;
        }
        return false;
    }

    private static List<Entry> scanXml(Path p, Path root) throws IOException {
        List<Entry> out = new ArrayList<>();
        String txt = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        String rel = root.relativize(p).toString();
        // namespace 取自 <mapper namespace="...">
        Matcher nsM = MAPPER_NS.matcher(txt);
        String ns = nsM.find() ? nsM.group(1) : "__unknown__";
        Matcher m = STMT.matcher(txt);
        while (m.find()) {
            String type = m.group(1).toUpperCase();
            String attr = m.group(2);
            String body = m.group(3);            // 开闭标签之间的 SQL 正文
            Matcher am = ID_ATTR.matcher(attr);
            String id = am.find() ? am.group(1) : "?";
            int start = lineOf(txt, m.start());
            String norm = SqlNormalizer.normalize(SqlNormalizer.stripDynamic(body));
            List<String> skel = Fingerprint.skeleton(norm);
            String skelKey = Fingerprint.skeletonKey(skel);
            String whereKey = keyOf(Fingerprint.whereColumns(norm, skel));
            Fingerprint.JoinInfo joinInfo = Fingerprint.joinInfo(norm, skel);
            out.add(new Entry(Fingerprint.of(norm), skelKey,
                    ns + "." + id, rel, start, type,
                    whereKey, joinInfo.tablesKey(), joinInfo.onKey()));
        }
        return out;
    }

    private static List<Entry> scanJava(Path p, Path root) throws IOException {
        List<Entry> out = new ArrayList<>();
        String txt = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        String rel = root.relativize(p).toString();
        Matcher m = ANNO.matcher(txt);
        while (m.find()) {
            String norm = SqlNormalizer.normalize(m.group(2));
            List<String> skel = Fingerprint.skeleton(norm);
            String skelKey = Fingerprint.skeletonKey(skel);
            String whereKey = keyOf(Fingerprint.whereColumns(norm, skel));
            Fingerprint.JoinInfo joinInfo = Fingerprint.joinInfo(norm, skel);
            out.add(new Entry(Fingerprint.of(norm), skelKey,
                    rel + ":@" + m.group(1), rel, lineOf(txt, m.start()), m.group(1).toUpperCase(),
                    whereKey, joinInfo.tablesKey(), joinInfo.onKey()));
        }
        return out;
    }

    private static String keyOf(Set<String> set) {
        return String.join("|", new TreeSet<>(set));
    }

    private static int lineOf(String txt, int offset) {
        int n = 1;
        for (int i = 0; i < offset && i < txt.length(); i++) if (txt.charAt(i) == '\n') n++;
        return n;
    }
}