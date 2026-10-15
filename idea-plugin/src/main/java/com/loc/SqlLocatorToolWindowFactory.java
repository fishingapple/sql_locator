package com.loc;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SqlLocatorToolWindowFactory implements ToolWindowFactory {

    private static final Map<Project, Runnable> refreshers = new HashMap<>();

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        JPanel panel = new JPanel(new BorderLayout());

        // 上部：历史列表(左) | 详情框(右)
        DefaultListModel<SqlLocatorHistory.Entry> model = new DefaultListModel<>();
        JList<SqlLocatorHistory.Entry> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof SqlLocatorHistory.Entry) {
                    setText(((SqlLocatorHistory.Entry) value).summary());
                }
                return this;
            }
        });

        JTextArea detail = new JTextArea();
        detail.setEditable(false);
        detail.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        detail.setBorder(BorderFactory.createTitledBorder("查询详情"));

        list.addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                SqlLocatorHistory.Entry entry = list.getSelectedValue();
                if (entry == null) {
                    detail.setText("");
                    return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append("时间：").append(entry.formatTime()).append("\n");
                sb.append("索引规模：").append(entry.indexSize).append(" 条\n");
                sb.append("命中数：").append(entry.hitCount).append(" 条\n\n");
                sb.append("SQL：\n").append(entry.sql);

                List<SqlLocation> hits = entry.locations;
                if (hits == null || hits.isEmpty()) {
                    sb.append("\n\n命中详情：无（旧格式记录或 0 命中）\n");
                } else {
                    sb.append("\n\n命中详情（").append(hits.size()).append(" 条）：\n");
                    for (SqlLocation h : hits) {
                        sb.append("• [").append(h.badge()).append("] ")
                          .append(h.stmtType).append(' ').append(h.key).append('\n')
                          .append("  ").append(h.path).append(':').append(h.line)
                          .append("   评分=").append(h.score).append('\n');
                    }
                }
                detail.setText(sb.toString());
            }
        });

        JSplitPane topSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(list), new JScrollPane(detail));
        topSplit.setDividerLocation(280);
        panel.add(topSplit, BorderLayout.CENTER);

        // 下部：SQL 输入框(上) + 新建查询按钮(下)
        JTextArea sqlInput = new JTextArea(3, 20);
        sqlInput.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane sqlScroll = new JScrollPane(sqlInput);
        sqlScroll.setBorder(BorderFactory.createTitledBorder("输入 SQL"));

        JButton newQueryBtn = new JButton("新建查询 (Ctrl+Alt+Q)");
        newQueryBtn.addActionListener(e -> {
            String input = sqlInput.getText();
            if (input != null && !input.trim().isEmpty()) {
                LocateSqlAction.locateAndShow(project, input.trim());
            }
        });
        JButton rebuildBtn = new JButton("重建索引");
        rebuildBtn.addActionListener(e -> LocateSqlAction.rebuildIndex(project));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btnPanel.add(newQueryBtn);
        btnPanel.add(rebuildBtn);

        JPanel bottomPanel = new JPanel(new BorderLayout(4, 4));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        bottomPanel.add(sqlScroll, BorderLayout.CENTER);
        bottomPanel.add(btnPanel, BorderLayout.SOUTH);
        panel.add(bottomPanel, BorderLayout.SOUTH);

        refreshers.put(project, new Runnable() {
            @Override
            public void run() {
                model.clear();
                List<SqlLocatorHistory.Entry> entries = SqlLocatorHistory.load(project);
                for (SqlLocatorHistory.Entry entry : entries) {
                    model.addElement(entry);
                }
            }
        });
        refreshers.get(project).run();

        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setCloseable(false);
        toolWindow.getContentManager().addContent(content);
    }

    public static void refresh(Project project) {
        Runnable r = refreshers.get(project);
        if (r != null) SwingUtilities.invokeLater(r);
    }
}
