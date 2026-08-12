package io.pzstorm.launcher.ui;

import io.pzstorm.launcher.LauncherConfig;
import io.pzstorm.launcher.Log;
import io.pzstorm.launcher.LogReport;
import java.awt.Component;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * The privacy prompt + background upload shared by the "Send Logs" toolbar button and any popup
 * that also wants to invite the user to send diagnostics.
 */
public final class SendLogsDialog {

    private SendLogsDialog() {}

    public static void open(Component parent, LauncherConfig config) {
        JTextArea description = new JTextArea(5, 40);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        int choice =
                JOptionPane.showConfirmDialog(
                        parent,
                        new Object[] {
                            "<html><b>Privacy Notice</b><br><br>"
                                    + "You are about to send data to Gus Puffy (the developer).<br>"
                                    + "Passwords are never included, and the data is deleted once"
                                    + " it has been reviewed.<br><br>"
                                    + "Data sent will include:<br>"
                                    + "&nbsp;&nbsp;• Information about this PC (operating system,"
                                    + " CPU, RAM, Java version)<br>"
                                    + "&nbsp;&nbsp;• Launcher settings and launcher logs<br>"
                                    + "&nbsp;&nbsp;• Logs from Project Zomboid and Storm"
                                    + " (console.txt and the Logs folder)<br><br>"
                                    + "Describe the problem (optional):</html>",
                            new JScrollPane(description)
                        },
                        "Send logs to Gus Puffy",
                        JOptionPane.OK_CANCEL_OPTION);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        String text = description.getText().trim();
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                String logId = LogReport.send(config, text);
                                Log.info(
                                        "Logs sent — mention report id "
                                                + logId
                                                + " when asking for help.");
                            } catch (Exception e) {
                                Log.error("Sending logs failed", e);
                            }
                        },
                        "storm-log-report");
        worker.setDaemon(true);
        worker.start();
    }
}
