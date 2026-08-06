package io.pzstorm.launcher.ui;

import io.pzstorm.launcher.GameLaunch;
import io.pzstorm.launcher.JoinFlow;
import io.pzstorm.launcher.LauncherConfig;
import io.pzstorm.launcher.LauncherInfo;
import io.pzstorm.launcher.LauncherPaths;
import io.pzstorm.launcher.Log;
import io.pzstorm.launcher.ServerProfile;
import io.pzstorm.launcher.VanillaServerImport;
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
    private final JButton joinButton = new JButton("Join Server");
    private final JButton launchOnlyButton = new JButton("Launch Game Only");
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
        toolbar.add(button("Import from game", this::onImportFromGame));
        toolbar.add(Box.createHorizontalGlue());
        toolbar.add(button("Settings", this::onSettings));

        serverList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        serverList.addListSelectionListener(e -> refreshDetail());
        serverList.addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (e.getClickCount() == 2) {
                            onJoin();
                        }
                    }
                });

        joinButton.setFont(joinButton.getFont().deriveFont(Font.BOLD, 16f));
        joinButton.addActionListener(e -> onJoin());
        launchOnlyButton.addActionListener(e -> onLaunchOnly());

        JPanel right = new JPanel();
        right.setLayout(new javax.swing.BoxLayout(right, javax.swing.BoxLayout.Y_AXIS));
        right.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        detailLabel.setAlignmentX(LEFT_ALIGNMENT);
        joinButton.setAlignmentX(LEFT_ALIGNMENT);
        launchOnlyButton.setAlignmentX(LEFT_ALIGNMENT);
        right.add(detailLabel);
        right.add(Box.createVerticalStrut(16));
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
            config.servers.add(profile);
            model.addElement(profile);
            serverList.setSelectedValue(profile, true);
            saveConfig();
        }
    }

    private void onEdit() {
        ServerProfile profile = serverList.getSelectedValue();
        if (profile != null && ServerDialog.edit(this, profile)) {
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
                        "Remove '" + profile + "'?",
                        "Remove server",
                        JOptionPane.OK_CANCEL_OPTION)
                == JOptionPane.OK_OPTION) {
            config.servers.remove(profile);
            model.removeElement(profile);
            saveConfig();
        }
    }

    private void onImportFromGame() {
        int added = VanillaServerImport.importInto(config);
        if (added == 0) {
            JOptionPane.showMessageDialog(
                    this,
                    "No new servers found in the game's saved-server list.",
                    "Import from game",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        model.clear();
        config.servers.forEach(model::addElement);
        serverList.setSelectedIndex(model.size() - added);
        saveConfig();
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

    private void onJoin() {
        ServerProfile profile = serverList.getSelectedValue();
        if (profile == null) {
            return;
        }
        runLaunch(() -> JoinFlow.join(config, profile));
    }

    private void onLaunchOnly() {
        runLaunch(
                () -> {
                    GameLaunch.LaunchPlan plan = GameLaunch.plan(config, null);
                    plan.warnings.forEach(Log::warn);
                    Log.info("Launching: " + GameLaunch.describe(plan));
                    plan.start(LauncherPaths.gameLogFile());
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
        joinButton.setEnabled(!busy && serverList.getSelectedValue() != null);
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
