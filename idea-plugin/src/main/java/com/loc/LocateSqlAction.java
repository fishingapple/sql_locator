package com.loc;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class LocateSqlAction extends AnAction {

    private static final Map<Project, LocatorEngine> ENGINES = new ConcurrentHashMap<>();

    @Override
    public void actionPerformed(AnActionEvent e) {
        final Project project = e.getProject();
        if (project == null) return;
        final String input = Messages.showInputDialog(project, "粘贴 SQL 文本（占位符形式即可）：",
                "Locate Slow SQL", Messages.getQuestionIcon());
        if (input == null || input.trim().isEmpty()) return;
        locateAndShow(project, input.trim());
    }

    /** 工具窗口与右键菜单共用的查询入口 */
    public static void locateAndShow(final Project project, final String input) {
        final AtomicReference<List<SqlLocation>> hitsRef = new AtomicReference<List<SqlLocation>>();
        final AtomicReference<LocatorEngine> engRef = new AtomicReference<LocatorEngine>();
        final AtomicReference<String> grepRef = new AtomicReference<String>();

        Task.Backgroundable task = new Task.Backgroundable(project, "Locate Slow SQL", true) {
            @Override
            public void run(ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    LocatorEngine eng = loadEngine(project, indicator);
                    engRef.set(eng);
                    indicator.setText("Matching SQL...");
                    hitsRef.set(eng.locate(input));
                    grepRef.set(eng.grepFallback(input));
                } catch (Exception ex) {
                    throw new RuntimeException(ex.getMessage(), ex);
                }
            }

            @Override
            public void onSuccess() {
                List<SqlLocation> hits = hitsRef.get();
                LocatorEngine eng = engRef.get();
                int indexSize = eng == null ? -1 : eng.indexSize();
                showResult(project, hits, indexSize, grepRef.get());
                SqlLocatorHistory.add(project, input, hits == null ? 0 : hits.size(), indexSize,
                        hits == null ? Collections.emptyList() : hits);
                SqlLocatorToolWindowFactory.refresh(project);
            }

            @Override
            public void onThrowable(Throwable error) {
                Messages.showErrorDialog(project,
                        "定位失败：" + String.valueOf(error.getMessage()), "Locate Slow SQL");
            }
        };
        ProgressManager.getInstance().run(task);
    }

    /**
     * 在后台加载或重建索引；失败时抛出异常，由 onThrowable 统一弹窗。
     * ProgressIndicator 会实时显示“加载索引 / 扫描源文件 / 匹配中”等阶段。
     */
    private static LocatorEngine loadEngine(Project project, ProgressIndicator indicator) throws Exception {
        LocatorEngine eng = ENGINES.get(project);
        if (eng != null) return eng;
        synchronized (ENGINES) {
            eng = ENGINES.get(project);
            if (eng != null) return eng;
            Path base = Paths.get(project.getBasePath());
            eng = new LocatorEngine(base);
            try {
                indicator.setText("Loading existing SQL index...");
                eng.load();
            } catch (Exception e) {
                indicator.setText("Building SQL index (scanning source files)...");
                try {
                    eng.rebuild(Collections.singletonList(base));
                } catch (Exception e2) {
                    throw new Exception("自动构建索引失败：" + e2 + "\n\n项目路径：" + base, e2);
                }
            }
            ENGINES.put(project, eng);
            return eng;
        }
    }

    /**
     * 工具窗口「重建索引」按钮入口：在后台重扫源码、重建索引文件，并替换缓存。
     * <p>重建后调用 {@code ENGINES.put(project, eng)} 替换缓存引擎，使下次查询直接命中新索引；
     * 索引写入仍遵循只读约定（仅写项目根下的 .sql-locator-index）。
     */
    public static void rebuildIndex(final Project project) {
        if (project == null || project.getBasePath() == null) return;
        Task.Backgroundable task = new Task.Backgroundable(project, "Rebuild SQL Index", true) {
            @Override
            public void run(ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                Path base = Paths.get(project.getBasePath());
                LocatorEngine eng = new LocatorEngine(base);
                indicator.setText("Scanning source files and rebuilding index...");
                try {
                    eng.rebuild(Collections.singletonList(base));
                } catch (Exception e) {
                    throw new RuntimeException("重建索引失败：" + e, e);
                }
                ENGINES.put(project, eng);
            }

            @Override
            public void onSuccess() {
                LocatorEngine eng = ENGINES.get(project);
                int size = eng == null ? -1 : eng.indexSize();
                Messages.showMessageDialog(project,
                        "索引已重建，共 " + size + " 条 SQL。", "Rebuild Index", Messages.getInformationIcon());
                SqlLocatorToolWindowFactory.refresh(project);
            }

            @Override
            public void onThrowable(Throwable error) {
                Messages.showErrorDialog(project, "重建索引失败：" + String.valueOf(error.getMessage()), "Rebuild Index");
            }
        };
        ProgressManager.getInstance().run(task);
    }

    private static void showResult(Project project, List<SqlLocation> hits, int indexSize, String grep) {
        StringBuilder sb = new StringBuilder();
        if (hits == null || hits.isEmpty()) {
            sb.append("未命中索引（已扫描 ").append(indexSize).append(" 条 SQL）\n\n")
              .append("可尝试的 grep 兜底：\n").append(grep);
        } else {
            for (SqlLocation h : hits) {
                sb.append(h).append("  匹配度=").append(String.format("%.2f", h.score)).append('\n');
            }
            sb.append("\n索引规模：").append(indexSize).append(" 条（匹配度：1.0 指纹命中 / 0.85~0.9 骨架一致 / 0.5~0.7 表重合 / 0.2~0.5 弱相关）");
        }
        Messages.showMessageDialog(project, sb.toString(), "定位结果", Messages.getInformationIcon());
    }
}
