package io.pzstorm.launcher.ui;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * The only window shown while a staged launcher checks its workshop item for updates. That check
 * can end in a silent re-exec into the updated launcher — the window vanishes and a fresh one
 * appears — so the message is the explanation the user sees before it happens.
 */
public final class StageSplash extends JFrame {

    private StageSplash() {
        super("Storm Launcher");
        JLabel label = new JLabel("Checking for updates and restarting...", SwingConstants.CENTER);
        label.setBorder(BorderFactory.createEmptyBorder(32, 48, 32, 48));
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        add(label);
        setResizable(false);
        pack();
        setLocationRelativeTo(null);
    }

    /** Shows the splash on the EDT; null when the environment cannot open windows (headless). */
    public static StageSplash open() {
        StageSplash[] holder = new StageSplash[1];
        try {
            SwingUtilities.invokeAndWait(
                    () -> {
                        StageSplash splash = new StageSplash();
                        splash.setVisible(true);
                        holder[0] = splash;
                    });
        } catch (Exception e) {
            return null;
        }
        return holder[0];
    }

    public void close() {
        SwingUtilities.invokeLater(this::dispose);
    }
}
