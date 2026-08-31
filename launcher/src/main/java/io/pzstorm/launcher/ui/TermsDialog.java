package io.pzstorm.launcher.ui;

import io.pzstorm.launcher.PrivacyPolicy;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * Shows the Terms of Use &amp; Privacy Policy. In consent mode the player must tick the agreement
 * box before Accept enables, and closing the window counts as declining; in view mode (the launcher
 * window's Privacy button) it is read-only with a single Close button.
 */
public final class TermsDialog extends JDialog {

    private boolean accepted;

    private TermsDialog(Window owner, PrivacyPolicy policy, boolean consent, boolean updated) {
        super(owner, "Terms of Use & Privacy Policy", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(StormTheme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JLabel intro = new JLabel(introText(consent, updated, policy.version()));
        intro.setForeground(StormTheme.TEXT);
        root.add(intro, BorderLayout.NORTH);

        JTextArea body = new JTextArea(policy.text());
        body.setEditable(false);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setFont(StormTheme.font(Font.PLAIN, 13f));
        body.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        StormTheme.styleTextComponent(body);
        body.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(BorderFactory.createLineBorder(StormTheme.BORDER));
        scroll.setPreferredSize(new Dimension(680, 420));
        root.add(scroll, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.setOpaque(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        if (consent) {
            JCheckBox agree =
                    new JCheckBox("I have read and agree to the Terms of Use and Privacy Policy");
            agree.setOpaque(false);
            agree.setForeground(StormTheme.TEXT);
            south.add(agree, BorderLayout.NORTH);

            StormButton accept = StormButton.primary("Accept and continue");
            accept.setEnabled(false);
            agree.addActionListener(e -> accept.setEnabled(agree.isSelected()));
            accept.addActionListener(
                    e -> {
                        accepted = true;
                        dispose();
                    });
            StormButton decline = StormButton.ghost("Decline and quit");
            decline.addActionListener(e -> dispose());
            buttons.add(decline);
            buttons.add(accept);
            getRootPane().setDefaultButton(accept);
        } else {
            StormButton close = StormButton.primary("Close");
            close.addActionListener(e -> dispose());
            buttons.add(close);
            getRootPane().setDefaultButton(close);
        }
        south.add(buttons, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);

        addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        dispose();
                    }
                });
        setContentPane(root);
        pack();
        setLocationRelativeTo(owner);
    }

    private static String introText(boolean consent, boolean updated, String version) {
        if (!consent) {
            return "<html>Current version: <b>" + version + "</b></html>";
        }
        if (updated) {
            return "<html><b>The Terms of Use &amp; Privacy Policy have changed</b> (now version "
                    + version
                    + ").<br>Please review the new version and accept it to keep using the Storm"
                    + " Launcher.</html>";
        }
        return "<html><b>Welcome to the Storm Launcher.</b><br>Please read and accept the Terms of"
                + " Use &amp; Privacy Policy (version "
                + version
                + ") before continuing.</html>";
    }

    /**
     * Consent prompt; returns true only when the player ticked the agreement box and pressed
     * Accept. {@code updated} switches the wording to "the terms have changed".
     */
    public static boolean prompt(Window owner, PrivacyPolicy policy, boolean updated) {
        TermsDialog dialog = new TermsDialog(owner, policy, true, updated);
        dialog.setVisible(true);
        return dialog.accepted;
    }

    /** Read-only viewer for an already-accepted document. */
    public static void view(Window owner, PrivacyPolicy policy) {
        new TermsDialog(owner, policy, false, false).setVisible(true);
    }
}
