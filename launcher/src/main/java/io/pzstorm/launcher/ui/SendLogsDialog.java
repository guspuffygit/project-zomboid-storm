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
                                    + "You are about to send data to a private Discord channel"
                                    + " readable only by (the"
                                    + " developer).<br>"
                                    + "Passwords are never included, your operating-system account"
                                    + " name is removed from file paths and logs before sending,"
                                    + " and reports are deleted after 7 days.<br><br>"
                                    + "Data sent will include:<br>"
                                    + "&nbsp;&nbsp;• Information about this PC (operating system,"
                                    + " CPU, RAM, Java version)<br>"
                                    + "&nbsp;&nbsp;• Launcher settings and launcher logs<br>"
                                    + "&nbsp;&nbsp;• Logs from Project Zomboid and Storm"
                                    + " (console.txt and the Logs folder)<br>"
                                    + "&nbsp;&nbsp;• JVM crash dumps (hs_err files) from the"
                                    + " game folder<br>"
                                    + "&nbsp;&nbsp;• The description you type below<br><br>"
                                    + "Full details: Privacy Policy section 4.4 (the Privacy"
                                    + " button).<br><br>"
                                    + "Describe the problem (optional):</html>",
                            new JScrollPane(description)
                        },
                        "Send logs",
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
