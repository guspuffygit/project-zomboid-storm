package io.pzstorm.launcher.ui;

import io.pzstorm.launcher.GameCrashWatch;
import io.pzstorm.launcher.GameLaunch;
import io.pzstorm.launcher.GameProcessTracker;
import io.pzstorm.launcher.JoinFlow;
import io.pzstorm.launcher.LauncherConfig;
import io.pzstorm.launcher.LauncherInfo;
import io.pzstorm.launcher.LauncherPaths;
import io.pzstorm.launcher.Log;
import io.pzstorm.launcher.LogReport;
import io.pzstorm.launcher.ServerProfile;
import io.pzstorm.launcher.ServerStore;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

public final class LauncherWindow extends JFrame {

    private final LauncherConfig config;
    private final DefaultListModel<ServerProfile> model = new DefaultListModel<>();
    private final JList<ServerProfile> serverList = new JList<>(model);
    private final JTextArea logArea = new JTextArea(10, 80);
    private final JButton joinForceButton = new JButton("Join Server Force Mod Updates");
    private final JButton joinButton = new JButton("Join Server");
    private final JButton launchOnlyButton = new JButton("Launch to Main Menu");
    private final JLabel detailLabel = new JLabel(" ");

    public LauncherWindow(LauncherConfig config) {
        super("Storm Launcher " + LauncherInfo.version());
        this.config = config;
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        buildUi();
        config.servers.forEach(model::addElement);
        if (!model.isEmpty()) {
            serverList.setSelectedIndex(0);
        }
        Log.setSink(
                line ->
                        SwingUtilities.invokeLater(
                                () -> {
                                    logArea.append(line + "\n");
                                    logArea.setCaretPosition(logArea.getDocument().getLength());
                                }));
        GameCrashWatch.onAlert(
                message ->
                        SwingUtilities.invokeLater(
                                () ->
                                        JOptionPane.showMessageDialog(
                                                this,
                                                message,
                                                "Out of memory",
                                                JOptionPane.ERROR_MESSAGE)));
        Log.info(
                "Storm Launcher "
                        + LauncherInfo.version()
                        + " ready. Config: "
                        + LauncherPaths.configFile());
        Path gameDir = config.resolveGameDir();
        Log.info(
                gameDir != null
                        ? "Game directory: " + gameDir
                        : "Game directory NOT found — open Settings and point me at Project Zomboid.");
        pack();
        setMinimumSize(new Dimension(860, 560));
        setLocationRelativeTo(null);
    }

    private void buildUi() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(button("Add", this::onAdd));
        toolbar.add(button("Edit", this::onEdit));
        toolbar.add(button("Remove", this::onRemove));
        toolbar.add(button("Refresh from game", this::onRefreshFromGame));
        toolbar.add(Box.createHorizontalGlue());
        toolbar.add(button("Send Logs", this::onSendLogs));
        toolbar.add(button("Settings", this::onSettings));

        serverList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        serverList.addListSelectionListener(e -> refreshDetail());
        serverList.addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (e.getClickCount() == 2) {
                            onJoin(true);
                        }
                    }
                });

        joinForceButton.setFont(joinForceButton.getFont().deriveFont(Font.BOLD, 16f));
        joinForceButton.setToolTipText(
                "<html>Asks Steam to check and update <b>every</b> server mod before launching."
                        + "<br>Slower, but the most thorough — use this if Join Server still hits"
                        + "<br>the in-game mod update screen.</html>");
        joinForceButton.addActionListener(e -> onJoin(true));
        joinButton.setFont(joinButton.getFont().deriveFont(Font.PLAIN, 16f));
        joinButton.setToolTipText(
                "<html>Compares all mods against the Steam Workshop in a single request and only"
                        + "<br>updates the ones that actually changed — much faster when"
                        + "<br>everything is already up to date.</html>");
        joinButton.addActionListener(e -> onJoin(false));
        launchOnlyButton.addActionListener(e -> onLaunchOnly());

        JPanel right = new JPanel();
        right.setLayout(new javax.swing.BoxLayout(right, javax.swing.BoxLayout.Y_AXIS));
        right.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        detailLabel.setAlignmentX(LEFT_ALIGNMENT);
        joinForceButton.setAlignmentX(LEFT_ALIGNMENT);
        joinButton.setAlignmentX(LEFT_ALIGNMENT);
        launchOnlyButton.setAlignmentX(LEFT_ALIGNMENT);
        right.add(detailLabel);
        right.add(Box.createVerticalStrut(16));
        right.add(joinForceButton);
        right.add(Box.createVerticalStrut(8));
        right.add(joinButton);
        right.add(Box.createVerticalStrut(8));
        right.add(launchOnlyButton);
        right.add(Box.createVerticalGlue());

        JSplitPane split =
                new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(serverList), right);
        split.setResizeWeight(0.55);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JPanel content = new JPanel(new BorderLayout());
        content.add(toolbar, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(new JScrollPane(logArea), BorderLayout.SOUTH);
        setContentPane(content);
        refreshDetail();
    }

    private static JButton button(String label, Runnable action) {
        JButton b = new JButton(label);
        b.addActionListener(e -> action.run());
        return b;
    }

    private void refreshDetail() {
        ServerProfile p = serverList.getSelectedValue();
        joinForceButton.setEnabled(p != null);
        joinButton.setEnabled(p != null);
        if (p == null) {
            detailLabel.setText("<html><i>Add a server to get started.</i></html>");
            return;
        }
        String character =
                p.username.isEmpty() ? "no character" : "character: " + escape(p.username);
        String extras = p.autoConnect ? "auto-connect on" : "auto-connect off";
        detailLabel.setText(
                "<html><b>"
                        + escape(p.name.isEmpty() ? p.connectAddress() : p.name)
                        + "</b><br>"
                        + escape(p.connectAddress())
                        + "<br>"
                        + character
                        + "<br>"
                        + extras
                        + "</html>");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void onAdd() {
        ServerProfile profile = new ServerProfile();
        if (ServerDialog.edit(this, profile)) {
            ServerStore.save(config, profile);
            config.servers.add(profile);
            model.addElement(profile);
            serverList.setSelectedValue(profile, true);
            saveConfig();
        }
    }

    private void onEdit() {
        ServerProfile profile = serverList.getSelectedValue();
        if (profile != null && ServerDialog.edit(this, profile)) {
            ServerStore.save(config, profile);
            serverList.repaint();
            refreshDetail();
            saveConfig();
        }
    }

    private void onRemove() {
        ServerProfile profile = serverList.getSelectedValue();
        if (profile == null) {
            return;
        }
        if (JOptionPane.showConfirmDialog(
                        this,
                        "Remove '"
                                + profile
                                + "' from the launcher and the game's saved-server list?",
                        "Remove server",
                        JOptionPane.OK_CANCEL_OPTION)
                == JOptionPane.OK_OPTION) {
            ServerStore.remove(config, profile);
            model.removeElement(profile);
            saveConfig();
        }
    }

    /** Re-reads the game's saved-server database, e.g. after adding a server in-game. */
    private void onRefreshFromGame() {
        ServerStore.load(config);
        model.clear();
        config.servers.forEach(model::addElement);
        if (!model.isEmpty()) {
            serverList.setSelectedIndex(0);
        }
        saveConfig();
    }

    /** Uploads metadata + zipped launcher/game/Zomboid/Storm logs to the Storm team's Discord. */
    private void onSendLogs() {
        JTextArea description = new JTextArea(5, 40);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        int choice =
                JOptionPane.showConfirmDialog(
                        this,
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

    private void onSettings() {
        if (SettingsDialog.edit(this, config)) {
            saveConfig();
            Path gameDir = config.resolveGameDir();
            Log.info(
                    gameDir != null
                            ? "Game directory: " + gameDir
                            : "Game directory still not found.");
        }
    }

    private void onJoin(boolean forceModUpdates) {
        ServerProfile profile = serverList.getSelectedValue();
        if (profile == null) {
            return;
        }
        runLaunch(() -> JoinFlow.join(config, profile, forceModUpdates));
    }

    private void onLaunchOnly() {
        runLaunch(
                () -> {
                    GameProcessTracker.reapLeftover();
                    GameLaunch.LaunchPlan plan = GameLaunch.plan(config, null);
                    plan.warnings.forEach(Log::warn);
                    Log.info("Launching: " + GameLaunch.describe(plan));
                    Log.info("Game JVM args: " + GameLaunch.describeJvmArgs(plan));
                    Process process = plan.start(LauncherPaths.gameLogFile());
                    GameProcessTracker.record(process);
                    GameCrashWatch.arm(process, LauncherPaths.gameLogFile());
                });
    }

    private interface LaunchAction {
        void run() throws Exception;
    }

    private void runLaunch(LaunchAction action) {
        setBusy(true);
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                action.run();
                                SwingUtilities.invokeLater(
                                        () ->
                                                Log.info(
                                                        "You can keep this window open — the game runs independently."));
                            } catch (Exception e) {
                                Log.error("Launch failed", e);
                                SwingUtilities.invokeLater(
                                        () ->
                                                JOptionPane.showMessageDialog(
                                                        this,
                                                        e.getMessage(),
                                                        "Launch failed",
                                                        JOptionPane.ERROR_MESSAGE));
                            } finally {
                                SwingUtilities.invokeLater(() -> setBusy(false));
                            }
                        },
                        "storm-launcher-join");
        worker.setDaemon(false);
        worker.start();
    }

    private void setBusy(boolean busy) {
        boolean joinable = !busy && serverList.getSelectedValue() != null;
        joinForceButton.setEnabled(joinable);
        joinButton.setEnabled(joinable);
        launchOnlyButton.setEnabled(!busy);
        serverList.setEnabled(!busy);
    }

    private void saveConfig() {
        try {
            config.save(LauncherPaths.configFile());
        } catch (IOException e) {
            Log.error("Could not save config", e);
            JOptionPane.showMessageDialog(
                    this,
                    "Could not save config: " + e.getMessage(),
                    "Config",
                    JOptionPane.ERROR_MESSAGE);
        }
    }
}
