package io.pzstorm.launcher.ui;

import io.pzstorm.launcher.GameCrashWatch;
import io.pzstorm.launcher.GameLaunch;
import io.pzstorm.launcher.GameProcessTracker;
import io.pzstorm.launcher.JoinFlow;
import io.pzstorm.launcher.LauncherConfig;
import io.pzstorm.launcher.LauncherInfo;
import io.pzstorm.launcher.LauncherPaths;
import io.pzstorm.launcher.Log;
import io.pzstorm.launcher.ServerProfile;
import io.pzstorm.launcher.ServerStore;
import io.pzstorm.launcher.SteamRestartRequiredException;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

public final class LauncherWindow extends JFrame {

    private final LauncherConfig config;
    private final DefaultListModel<ServerProfile> model = new DefaultListModel<>();
    private final JList<ServerProfile> serverList = new JList<>(model);
    private final JTextArea logArea = new JTextArea(9, 80);
    private final StormButton joinButton = StormButton.primary("Join Server");
    private final StormButton launchOnlyButton = StormButton.secondary("Launch to Main Menu");
    private final StormButton joinForceButton =
            StormButton.secondary("Join with forced mod updates");
    private final JLabel detailName = new JLabel(" ");
    private final JLabel detailAddress = new JLabel(" ");
    private final JLabel detailCharacter = new JLabel(" ");
    private final JLabel detailAutoConnect = new JLabel(" ");

    public LauncherWindow(LauncherConfig config) {
        super("Storm Launcher " + LauncherInfo.version());
        this.config = config;
        // The shutdown hook in LauncherMain kills the tracked game on JVM exit so Steam's Stop
        // takes the game down with the launcher. A user-initiated window close is different — the
        // game is meant to keep running (see the "you can keep this window open" log line) — so
        // release the reference before triggering the same System.exit path.
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        GameProcessTracker.releaseCurrent();
                        System.exit(0);
                    }
                });
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
        setMinimumSize(new Dimension(920, 620));
        setLocationRelativeTo(null);
    }

    private void buildUi() {
        getContentPane().setBackground(StormTheme.BG);

        serverList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        serverList.setCellRenderer(new ServerCellRenderer());
        serverList.setBackground(StormTheme.BG_INSET);
        serverList.setFixedCellHeight(52);
        serverList.addListSelectionListener(e -> refreshDetail());
        serverList.addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (e.getClickCount() == 2) {
                            onJoin(false);
                        }
                    }
                });

        joinButton.setToolTipText(
                "<html>Compares all mods against the Steam Workshop in a single request and only"
                        + "<br>updates the ones that actually changed — much faster when"
                        + "<br>everything is already up to date.</html>");
        joinButton.addActionListener(e -> onJoin(false));
        joinForceButton.setFont(StormTheme.font(Font.PLAIN, 12f));
        joinForceButton.setToolTipText(
                "<html>Asks Steam to check and update <b>every</b> server mod before launching."
                        + "<br>Slower, but the most thorough — use this if Join Server still hits"
                        + "<br>the in-game mod update screen.</html>");
        joinForceButton.addActionListener(e -> onJoin(true));
        launchOnlyButton.addActionListener(e -> onLaunchOnly());

        JSplitPane split =
                new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildServerPane(), buildDetailPane());
        split.setResizeWeight(0.5);
        split.setBorder(null);
        split.setBackground(StormTheme.BG);

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(StormTheme.BG);
        content.add(buildHeader(), BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(buildLogPane(), BorderLayout.SOUTH);
        setContentPane(content);
        refreshDetail();
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
        header.setBackground(StormTheme.HEADER_BG);
        header.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, StormTheme.DIVIDER),
                        BorderFactory.createEmptyBorder(12, 16, 12, 12)));

        JLabel wordmark = new JLabel("STORM");
        wordmark.setFont(StormTheme.displayFont(Font.BOLD, 20f));
        wordmark.setForeground(StormTheme.ACCENT);
        JLabel sub = new JLabel("LAUNCHER");
        sub.setFont(StormTheme.displayFont(Font.PLAIN, 20f));
        sub.setForeground(StormTheme.HEADER_TEXT);
        JLabel version = new JLabel(LauncherInfo.version());
        version.setFont(StormTheme.font(Font.PLAIN, 11f));
        version.setForeground(StormTheme.TEXT_FAINT);

        header.add(wordmark);
        header.add(Box.createHorizontalStrut(6));
        header.add(sub);
        header.add(Box.createHorizontalStrut(10));
        header.add(version);
        header.add(Box.createHorizontalGlue());
        header.add(ghost("Send Logs", this::onSendLogs));
        header.add(Box.createHorizontalStrut(4));
        header.add(ghost("Settings", this::onSettings));
        header.add(Box.createHorizontalStrut(4));
        header.add(ghost("Quit", this::onQuit));
        return header;
    }

    private JPanel buildServerPane() {
        JPanel pane = new JPanel(new BorderLayout(0, 8));
        pane.setBackground(StormTheme.BG);
        pane.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 8));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(sectionLabel("SERVERS"), BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        actions.setOpaque(false);
        actions.add(smallGhost("+ Add", this::onAdd));
        actions.add(smallGhost("Edit", this::onEdit));
        actions.add(smallGhost("Remove", this::onRemove));
        actions.add(smallGhost("Refresh", this::onRefreshFromGame));
        top.add(actions, BorderLayout.EAST);
        pane.add(top, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(serverList);
        scroll.setBorder(BorderFactory.createLineBorder(StormTheme.BORDER));
        scroll.getViewport().setBackground(StormTheme.BG_INSET);
        pane.add(scroll, BorderLayout.CENTER);
        return pane;
    }

    private JPanel buildDetailPane() {
        JPanel pane = new JPanel();
        pane.setLayout(new BoxLayout(pane, BoxLayout.Y_AXIS));
        pane.setBackground(StormTheme.BG);
        pane.setBorder(BorderFactory.createEmptyBorder(14, 12, 14, 16));

        detailName.setFont(StormTheme.displayFont(Font.BOLD, 21f));
        detailName.setForeground(StormTheme.HEADER_TEXT);
        detailAddress.setFont(StormTheme.font(Font.PLAIN, 13f));
        detailAddress.setForeground(StormTheme.TEXT_DIM);
        detailCharacter.setFont(StormTheme.font(Font.PLAIN, 13f));
        detailCharacter.setForeground(StormTheme.TEXT_DIM);
        detailAutoConnect.setFont(StormTheme.font(Font.PLAIN, 13f));
        detailAutoConnect.setForeground(StormTheme.TEXT_DIM);

        for (Component c :
                new Component[] {
                    sectionLabel("SELECTED SERVER"),
                    Box.createVerticalStrut(10),
                    detailName,
                    Box.createVerticalStrut(4),
                    detailAddress,
                    Box.createVerticalStrut(12),
                    detailCharacter,
                    Box.createVerticalStrut(2),
                    detailAutoConnect,
                    Box.createVerticalStrut(24),
                    joinButton,
                    Box.createVerticalStrut(10),
                    launchOnlyButton,
                    Box.createVerticalStrut(14),
                    joinForceButton,
                    Box.createVerticalGlue()
                }) {
            if (c instanceof javax.swing.JComponent) {
                ((javax.swing.JComponent) c).setAlignmentX(LEFT_ALIGNMENT);
            }
            pane.add(c);
        }
        return pane;
    }

    private JPanel buildLogPane() {
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setFont(StormTheme.monoFont(12f));
        logArea.setBackground(StormTheme.HEADER_BG);
        logArea.setForeground(StormTheme.TEXT_DIM);
        logArea.setCaretColor(StormTheme.ACCENT);
        logArea.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

        JPanel pane = new JPanel(new BorderLayout(0, 6));
        pane.setBackground(StormTheme.BG);
        pane.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1, 0, 0, 0, StormTheme.DIVIDER),
                        BorderFactory.createEmptyBorder(10, 16, 12, 16)));
        pane.add(sectionLabel("ACTIVITY"), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createLineBorder(StormTheme.BORDER));
        scroll.getViewport().setBackground(StormTheme.HEADER_BG);
        scroll.setPreferredSize(new Dimension(0, 150));
        pane.add(scroll, BorderLayout.CENTER);
        return pane;
    }

    private static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(StormTheme.displayFont(Font.BOLD, 11f));
        label.setForeground(StormTheme.TEXT_FAINT);
        return label;
    }

    private static StormButton ghost(String label, Runnable action) {
        StormButton b = StormButton.ghost(label);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static StormButton smallGhost(String label, Runnable action) {
        StormButton b = ghost(label, action);
        b.setFont(StormTheme.font(Font.PLAIN, 12f));
        b.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        return b;
    }

    /** Two-line card rows: server name over address · character, gold bar on the selected row. */
    private static final class ServerCellRenderer extends JPanel
            implements ListCellRenderer<ServerProfile> {

        private final JLabel title = new JLabel();
        private final JLabel subtitle = new JLabel();
        private boolean selected;

        ServerCellRenderer() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 10));
            title.setFont(StormTheme.font(Font.BOLD, 14f));
            subtitle.setFont(StormTheme.font(Font.PLAIN, 12f));
            add(title);
            add(Box.createVerticalStrut(2));
            add(subtitle);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends ServerProfile> list,
                ServerProfile value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            selected = isSelected;
            title.setText(value.name.isEmpty() ? value.connectAddress() : value.name);
            String character = value.username.isEmpty() ? "no character" : value.username;
            subtitle.setText(value.connectAddress() + "  ·  " + character);
            setBackground(
                    isSelected
                            ? StormTheme.ROW_SELECTED
                            : index % 2 == 1 ? StormTheme.ROW_ALT : StormTheme.BG_INSET);
            title.setForeground(isSelected ? StormTheme.HEADER_TEXT : StormTheme.TEXT);
            subtitle.setForeground(isSelected ? StormTheme.TEXT : StormTheme.TEXT_DIM);
            setEnabled(list.isEnabled());
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (selected) {
                g.setColor(StormTheme.ACCENT);
                g.fillRect(0, 0, 3, getHeight());
            }
        }
    }

    private void refreshDetail() {
        ServerProfile p = serverList.getSelectedValue();
        joinForceButton.setEnabled(p != null);
        joinButton.setEnabled(p != null);
        if (p == null) {
            detailName.setText("No server selected");
            detailName.setForeground(StormTheme.TEXT_FAINT);
            detailAddress.setText("Add a server to get started.");
            detailCharacter.setText(" ");
            detailAutoConnect.setText(" ");
            return;
        }
        detailName.setForeground(StormTheme.HEADER_TEXT);
        detailName.setText(p.name.isEmpty() ? p.connectAddress() : p.name);
        detailAddress.setText(p.connectAddress());
        detailCharacter.setText(
                p.username.isEmpty() ? "Character: none saved" : "Character: " + p.username);
        detailAutoConnect.setText("Auto-connect: " + (p.autoConnect ? "on" : "off"));
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
        SendLogsDialog.open(this, config);
    }

    private void onQuit() {
        dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING));
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
                                GameProcessTracker.armCloseLauncherOnGameExit();
                                SwingUtilities.invokeLater(
                                        () -> Log.info("Launcher will close when the game exits."));
                            } catch (SteamRestartRequiredException e) {
                                Log.error("Launch failed", e);
                                SwingUtilities.invokeLater(
                                        () ->
                                                SteamRestartDialog.show(
                                                        this,
                                                        e.summary(),
                                                        () -> SendLogsDialog.open(this, config)));
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
