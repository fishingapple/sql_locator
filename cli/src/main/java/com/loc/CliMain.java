package com.loc;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

public class CliMain {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir"));
        LocatorEngine engine = new LocatorEngine(root);
        if (args.length > 0 && "--build".equals(args[0])) {
            Path srcPath = args.length > 1 ? Paths.get(args[1]) : root.resolve("src");
            engine.rebuild(Collections.singletonList(srcPath));
            System.out.println("[index] built -> " + engine.indexFile.toAbsolutePath());
            return;
        }
        engine.load();
        String sql = args.length > 0 ? String.join(" ", args) : readStdin();
        List<SqlLocation> hits = engine.locate(sql);
        if (hits.isEmpty()) {
            System.out.println("未命中索引。\ngrep 兜底: " + engine.grepFallback(sql));
        } else {
            hits.forEach(System.out::println);
        }
    }
    private static String readStdin() throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = System.in.read(chunk)) != -1) buf.write(chunk, 0, n);
        return new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
    }
}