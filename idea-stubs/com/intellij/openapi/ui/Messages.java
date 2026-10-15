package com.intellij.openapi.ui;

import com.intellij.openapi.project.Project;
import javax.swing.Icon;

public class Messages {
    public static String showInputDialog(Project project, String message, String title, Icon icon) {
        return null;
    }
    public static void showErrorDialog(Project project, String message, String title) {}
    public static void showMessageDialog(Project project, Object component, String title, Icon icon) {}
    public static Icon getQuestionIcon() { return null; }
    public static Icon getInformationIcon() { return null; }
}
