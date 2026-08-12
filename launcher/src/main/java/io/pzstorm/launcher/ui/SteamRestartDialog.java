package io.pzstorm.launcher.ui;

import io.pzstorm.launcher.SteamRestart;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Window;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * The popup shown when a join was cancelled because Steam is in a stuck state.
 *
 * <p>Two flows, picked by {@link SteamRestart#isSupported()}:
 *
 * <ul>
 *   <li>Windows: ask permission to auto-restart, run it with a live progress log, fall back to
 *       manual instructions if the automatic path fails or the user declines.
 *   <li>Everywhere else: show manual instructions immediately.
 * </ul>
 *
 * <p>Either flow can offer a "Send Logs" shortcut so the user can report the issue without hunting
 * for the toolbar button.
 */
public final class SteamRestartDialog {

    private static final String TASKBAR_INSTRUCTION =
            System.getProperty("os.name", "").toLowerCase().contains("mac")
                    ? "Right-click the Steam icon in the menu bar and choose <b>Quit Steam</b>."
                    : "Right-click the Steam icon in the taskbar tray and click <b>Exit</b>.";

    private SteamRestartDialog() {}

    public static void show(Component parent, String summary, Runnable sendLogsAction) {
        show(
                parent,
                summary,
                SteamRestart::attemptRestart,
                SteamRestart.isSupported(),
                sendLogsAction);
    }

    /**
     * Test/demo hook: inject the restart function (usually {@link SteamRestart#attemptRestart}) and
     * force the auto path on/off so the same code renders on any OS.
     */
    public static void show(
            Component parent,
            String summary,
            Function<java.util.function.Consumer<String>, SteamRestart.Result> restart,
            boolean offerAutoRestart,
            Runnable sendLogsAction) {
        if (!offerAutoRestart) {
            showManual(parent, summary, sendLogsAction);
            return;
        }
        int choice = askPermission(parent, summary);
        if (choice != JOptionPane.YES_OPTION) {
            showManual(parent, summary, sendLogsAction);
            return;
        }
        runAutoRestart(parent, summary, restart, sendLogsAction);
    }

    private static int askPermission(Component parent, String summary) {
        JLabel heading = new JLabel("Steam needs to be restarted");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel body =
                new JLabel(
                        "<html><div style='width:420px;'>"
                                + escape(summary)
                                + "<br><br>"
                                + "Would you like the launcher to close Steam and start it again"
                                + " for you? You'll still need to press <b>Join Server</b> once"
                                + " Steam finishes reconnecting."
                                + "</div></html>",
                        UIManager.getIcon("OptionPane.warningIcon"),
                        SwingConstants.LEFT);
        body.setIconTextGap(16);
        body.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        panel.add(heading);
        panel.add(Box.createVerticalStrut(12));
        panel.add(body);

        return JOptionPane.showOptionDialog(
                parent,
                panel,
                "Restart Steam",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                new Object[] {"Restart Steam for me", "I'll do it myself"},
                "Restart Steam for me");
    }

    /** Demo/test hook: skip the initial ask and jump straight to the progress dialog. */
    public static void runAutoRestart(
            Component parent,
            String summary,
            Function<java.util.function.Consumer<String>, SteamRestart.Result> restart,
            Runnable sendLogsAction) {
        JTextArea log = new JTextArea(14, 60);
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(log);
        scroll.setPreferredSize(new Dimension(560, 260));

        JLabel status = new JLabel("Restarting Steam …");
        status.setFont(status.getFont().deriveFont(Font.BOLD, 14f));

        JButton sendLogs = new JButton("Send Logs to Developer");
        sendLogs.setVisible(false);
        JButton close = new JButton("Close");
        close.setEnabled(false);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(status, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        buttons.add(close);
        if (sendLogsAction != null) {
            buttons.add(sendLogs);
        }
        content.add(buttons, BorderLayout.SOUTH);

        JDialog dialog =
                new JDialog(
                        parent instanceof Window ? (Window) parent : ownerFrame(parent),
                        "Restarting Steam",
                        JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        close.addActionListener(e -> dialog.dispose());
        if (sendLogsAction != null) {
            sendLogs.addActionListener(e -> sendLogsAction.run());
        }

        Thread worker =
                new Thread(
                        () -> {
                            SteamRestart.Result result =
                                    restart.apply(
                                            line ->
                                                    SwingUtilities.invokeLater(
                                                            () -> {
                                                                log.append(line + "\n");
                                                                log.setCaretPosition(
                                                                        log.getDocument()
                                                                                .getLength());
                                                            }));
                            SwingUtilities.invokeLater(
                                    () -> {
                                        if (result.ok) {
                                            status.setText("Steam restart requested.");
                                        } else {
                                            status.setText("Automatic restart failed.");
                                            log.append("\n" + result.failureReason + "\n");
                                            log.append(
                                                    "Please restart Steam yourself: "
                                                            + plainInstructions()
                                                            + "\n");
                                            if (sendLogsAction != null) {
                                                log.append(
                                                        "\nIf that doesn't work, send logs to"
                                                                + " the developer.\n");
                                                sendLogs.setVisible(true);
                                                buttons.revalidate();
                                                buttons.repaint();
                                            }
                                            log.setCaretPosition(log.getDocument().getLength());
                                        }
                                        close.setEnabled(true);
                                        close.requestFocusInWindow();
                                    });
                        },
                        "storm-steam-restart");
        worker.setDaemon(true);
        worker.start();
        dialog.setVisible(true);
    }

    private static void showManual(Component parent, String summary, Runnable sendLogsAction) {
        JLabel heading = new JLabel("Please restart Steam");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);

        String fallback =
                sendLogsAction == null
                        ? ""
                        : "<p style='margin-top:10px;'>If that doesn't work, send logs to the"
                                + " developer.</p>";
        JLabel body =
                new JLabel(
                        "<html><div style='width:420px;'>"
                                + escape(summary)
                                + "<br><br>"
                                + "<b>To fix it:</b>"
                                + "<ol style='margin-top:4px;'>"
                                + "<li>"
                                + TASKBAR_INSTRUCTION
                                + "</li>"
                                + "<li>Wait a few seconds for Steam to fully close.</li>"
                                + "<li>Start Steam again.</li>"
                                + "<li>Come back here and click <b>Join Server</b> again.</li>"
                                + "</ol>"
                                + fallback
                                + "</div></html>",
                        UIManager.getIcon("OptionPane.warningIcon"),
                        SwingConstants.LEFT);
        body.setIconTextGap(16);
        body.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        panel.add(heading);
        panel.add(Box.createVerticalStrut(12));
        panel.add(body);

        Object[] options =
                sendLogsAction == null
                        ? new Object[] {"OK"}
                        : new Object[] {"OK", "Send Logs to Developer"};
        int choice =
                JOptionPane.showOptionDialog(
                        parent,
                        panel,
                        "Restart Steam",
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.PLAIN_MESSAGE,
                        null,
                        options,
                        options[0]);
        if (sendLogsAction != null && choice == 1) {
            sendLogsAction.run();
        }
    }

    private static String plainInstructions() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return "right-click Steam in the menu bar and choose Quit Steam, then reopen it.";
        }
        return "right-click Steam in the taskbar tray and click Exit, then reopen it.";
    }

    private static Frame ownerFrame(Component parent) {
        if (parent == null) {
            return null;
        }
        Component c = parent;
        while (c != null) {
            if (c instanceof Frame) {
                return (Frame) c;
            }
            c = c.getParent();
        }
        return null;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
