package io.pzstorm.launcher.ui;

import io.pzstorm.launcher.ServerProfile;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

/** Modal add/edit dialog for one saved server. */
public final class ServerDialog extends JDialog {

    private final JTextField name = new JTextField(24);
    private final JTextField host = new JTextField(24);
    private final JTextField port = new JTextField("16261", 8);
    private final JPasswordField serverPassword = new JPasswordField(24);
    private final JTextField username = new JTextField(24);
    private final JPasswordField accountPassword = new JPasswordField(24);
    private final JCheckBox savePassword =
            new JCheckBox("Save account password (in the game's saved-server list)");
    private final JCheckBox autoConnect =
            new JCheckBox("Auto-connect in-game (requires Storm mod enabled on this client)");
    private final JCheckBox updateWorkshopMods =
            new JCheckBox("Update Steam workshop mods before launch");
    private final JTextField extraVmArgs = new JTextField(24);
    private boolean accepted;

    private ServerDialog(Window owner, ServerProfile profile) {
        super(
                owner,
                (profile.host.isEmpty() ? "Add Server" : "Edit Server"),
                ModalityType.APPLICATION_MODAL);
        name.setText(profile.name);
        host.setText(profile.host);
        port.setText(String.valueOf(profile.port));
        serverPassword.setText(profile.serverPassword);
        username.setText(profile.username);
        accountPassword.setText(profile.accountPassword);
        savePassword.setSelected(!profile.accountPassword.isEmpty());
        autoConnect.setSelected(profile.autoConnect);
        updateWorkshopMods.setSelected(profile.updateWorkshopMods);
        extraVmArgs.setText(String.join(" ", profile.extraVmArgs));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        int row = 0;
        row = addRow(form, row, "Name", name);
        row = addRow(form, row, "Host / IP", host);
        row = addRow(form, row, "Game port", port);
        row = addRow(form, row, "Server password", serverPassword);
        row = addRow(form, row, "Username", username);
        row = addRow(form, row, "Account password", accountPassword);
        row = addRow(form, row, null, savePassword);
        row = addRow(form, row, null, autoConnect);
        row = addRow(form, row, "Extra JVM args", extraVmArgs);
        row = addRow(form, row, null, updateWorkshopMods);
        JLabel note =
                new JLabel(
                        "<html><i>With auto-connect on, Storm fills and submits"
                                + " the connect dialog for you.<br>Without it the game still"
                                + " pre-fills what it remembers; you click CONNECT once.</i></html>");
        row = addRow(form, row, null, note);

        JButton ok = new JButton("OK");
        ok.addActionListener(e -> onOk(profile));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel();
        buttons.add(ok);
        buttons.add(cancel);
        row = addRow(form, row, null, buttons);

        getRootPane().setDefaultButton(ok);
        setContentPane(form);
        pack();
        setLocationRelativeTo(owner);
    }

    private static int addRow(JPanel form, int row, String label, JComponent field) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.WEST;
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

    private void onOk(ServerProfile profile) {
        String hostText = host.getText().trim();
        if (hostText.isEmpty() || hostText.contains(":") || hostText.contains(" ")) {
            JOptionPane.showMessageDialog(
                    this,
                    "Enter a hostname or IP (without port).",
                    "Invalid host",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        int gamePort = parsePort(port.getText(), -1);
        if (gamePort <= 0) {
            JOptionPane.showMessageDialog(
                    this, "Game port must be 1-65535.", "Invalid port", JOptionPane.ERROR_MESSAGE);
            return;
        }
        profile.name = name.getText().trim();
        profile.host = hostText;
        profile.port = gamePort;
        profile.serverPassword = new String(serverPassword.getPassword());
        profile.username = username.getText().trim();
        profile.accountPassword =
                savePassword.isSelected() ? new String(accountPassword.getPassword()) : "";
        profile.autoConnect = autoConnect.isSelected();
        profile.updateWorkshopMods = updateWorkshopMods.isSelected();
        profile.extraVmArgs = new ArrayList<>();
        Arrays.stream(extraVmArgs.getText().trim().split("\\s+"))
                .filter(s -> !s.isEmpty())
                .forEach(profile.extraVmArgs::add);
        accepted = true;
        dispose();
    }

    /** Returns emptyValue for blank input, -1 for garbage/out-of-range. */
    private static int parsePort(String text, int emptyValue) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return emptyValue;
        }
        try {
            int value = Integer.parseInt(trimmed);
            return value >= 1 && value <= 65535 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Shows the dialog; returns true and mutates {@code profile} on OK. */
    public static boolean edit(Window owner, ServerProfile profile) {
        ServerDialog dialog = new ServerDialog(owner, profile);
        dialog.setVisible(true);
        return dialog.accepted;
    }
}
