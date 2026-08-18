package io.pzstorm.launcher.ui;

import io.pzstorm.launcher.ServerProfile;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

/**
 * One-click setup for the sponsored After The Fall server: the profile arrives pre-filled with the
 * server's name, address, and (empty) access password, so the player only picks the credentials
 * that create their in-game character. On accept the caller saves the profile and connects.
 */
public final class AtfSetupDialog extends JDialog {

    private final JTextField username = new JTextField(24);
    private final JPasswordField accountPassword = new JPasswordField(24);
    private boolean accepted;

    private AtfSetupDialog(Window owner, ServerProfile profile) {
        super(owner, "Play on After The Fall", ModalityType.APPLICATION_MODAL);
        username.setText(profile.username);
        accountPassword.setText(profile.accountPassword);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(StormTheme.BG);
        form.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        int row = 0;
        JLabel note =
                new JLabel(
                        "<html>Enter a username and password for playing on After The Fall."
                                + "<br>They create your character on the server the first time you"
                                + " join.</html>");
        note.setForeground(StormTheme.TEXT_DIM);
        row = ServerDialog.addRow(form, row, null, note);
        row = ServerDialog.addRow(form, row, "Username", username);
        row = ServerDialog.addRow(form, row, "Account password", accountPassword);

        StormButton ok = StormButton.primary("Save + Connect");
        ok.addActionListener(e -> onOk(profile));
        StormButton cancel = StormButton.ghost("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        buttons.add(ok);
        row = ServerDialog.addRow(form, row, null, buttons);

        getRootPane().setDefaultButton(ok);
        setContentPane(form);
        pack();
        setLocationRelativeTo(owner);
    }

    private void onOk(ServerProfile profile) {
        String user = username.getText().trim();
        if (user.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Enter a username for your character.",
                    "Username required",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        profile.username = user;
        profile.accountPassword = new String(accountPassword.getPassword());
        accepted = true;
        dispose();
    }

    /** Shows the dialog; returns true and fills the credentials on Save + Connect. */
    public static boolean setup(Window owner, ServerProfile profile) {
        AtfSetupDialog dialog = new AtfSetupDialog(owner, profile);
        dialog.setVisible(true);
        return dialog.accepted;
    }
}
