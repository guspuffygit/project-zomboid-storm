package io.pzstorm.launcher.ui;

import io.pzstorm.launcher.LauncherConfig;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/** Global launcher settings. Empty fields mean auto-detect; hints show the result. */
public final class SettingsDialog extends JDialog {

    private final JTextField gameDir = new JTextField(36);
    private final JTextField jvmPath = new JTextField(36);
    private final JTextField bootstrapDir = new JTextField(36);
    private final JTextArea globalVmArgs = new JTextArea(4, 36);
    private boolean accepted;

    private SettingsDialog(Window owner, LauncherConfig config) {
        super(owner, "Settings", ModalityType.APPLICATION_MODAL);
        gameDir.setText(config.gameDir);
        jvmPath.setText(config.jvmPath);
        bootstrapDir.setText(config.bootstrapDir);
        globalVmArgs.setText(String.join("\n", config.globalVmArgs));

        Path detectedGame = config.resolveGameDir();
        Path detectedJvm = config.resolveJvm(detectedGame);
        Path detectedBootstrap = config.resolveBootstrapDir(detectedGame);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        int row = 0;
        row = addRow(form, row, "Game directory", withBrowse(gameDir, true));
        row = addHint(form, row, detectedGame, "game install");
        row = addRow(form, row, "JVM", withBrowse(jvmPath, false));
        row = addHint(form, row, detectedJvm, "JVM");
        row = addRow(form, row, "Storm bootstrap dir", withBrowse(bootstrapDir, true));
        row = addHint(form, row, detectedBootstrap, "Storm bootstrap");
        row = addRow(form, row, "Global JVM args", new JScrollPane(globalVmArgs));
        row =
                addRow(
                        form,
                        row,
                        null,
                        new JLabel(
                                "<html><i>One arg per line, e.g. -Xmx16g. Empty path fields"
                                        + " auto-detect.</i></html>"));

        JButton ok = new JButton("OK");
        ok.addActionListener(
                e -> {
                    config.gameDir = gameDir.getText().trim();
                    config.jvmPath = jvmPath.getText().trim();
                    config.bootstrapDir = bootstrapDir.getText().trim();
                    config.globalVmArgs = new ArrayList<>();
                    Arrays.stream(globalVmArgs.getText().split("\\s+"))
                            .filter(s -> !s.isEmpty())
                            .forEach(config.globalVmArgs::add);
                    accepted = true;
                    dispose();
                });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel();
        buttons.add(ok);
        buttons.add(cancel);
        addRow(form, row, null, buttons);

        getRootPane().setDefaultButton(ok);
        setContentPane(form);
        pack();
        setLocationRelativeTo(owner);
    }

    private JPanel withBrowse(JTextField field, boolean directories) {
        JPanel panel = new JPanel(new java.awt.BorderLayout(4, 0));
        panel.add(field, java.awt.BorderLayout.CENTER);
        JButton browse = new JButton("…");
        browse.addActionListener(
                e -> {
                    JFileChooser chooser =
                            new JFileChooser(field.getText().isEmpty() ? null : field.getText());
                    chooser.setFileSelectionMode(
                            directories ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
                    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                        field.setText(chooser.getSelectedFile().getAbsolutePath());
                    }
                });
        panel.add(browse, java.awt.BorderLayout.EAST);
        return panel;
    }

    private static int addRow(JPanel form, int row, String label, JComponent field) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        if (label != null) {
            gbc.gridx = 0;
            form.add(new JLabel(label + ":"), gbc);
            gbc.gridx = 1;
        } else {
            gbc.gridx = 0;
            gbc.gridwidth = 2;
        }
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        form.add(field, gbc);
        return row + 1;
    }

    private static int addHint(JPanel form, int row, Path detected, String what) {
        String text =
                detected != null
                        ? "auto: " + detected
                        : "auto-detect failed — set the " + what + " path";
        JLabel hint = new JLabel(text);
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1f));
        hint.setForeground(detected != null ? java.awt.Color.GRAY : java.awt.Color.RED.darker());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(0, 4, 4, 4);
        gbc.gridx = 1;
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.WEST;
        form.add(hint, gbc);
        return row + 1;
    }

    /** Shows the dialog; returns true and mutates {@code config} on OK. */
    public static boolean edit(Window owner, LauncherConfig config) {
        SettingsDialog dialog = new SettingsDialog(owner, config);
        dialog.setVisible(true);
        return dialog.accepted;
    }
}
