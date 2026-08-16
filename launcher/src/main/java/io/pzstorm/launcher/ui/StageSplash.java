package io.pzstorm.launcher.ui;

import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * The only window shown while a staged launcher checks its workshop item for updates. That check
 * can end in a silent re-exec into the updated launcher — the window vanishes and a fresh one
 * appears — so the message is the explanation the user sees before it happens.
 */
public final class StageSplash extends JFrame {

    private StageSplash() {
        super("Storm Launcher");
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(StormTheme.HEADER_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(36, 56, 36, 56));

        JPanel wordmark = new JPanel();
        wordmark.setLayout(new BoxLayout(wordmark, BoxLayout.X_AXIS));
        wordmark.setOpaque(false);
        JLabel storm = new JLabel("STORM");
        storm.setFont(StormTheme.displayFont(Font.BOLD, 24f));
        storm.setForeground(StormTheme.ACCENT);
        JLabel launcher = new JLabel("LAUNCHER");
        launcher.setFont(StormTheme.displayFont(Font.PLAIN, 24f));
        launcher.setForeground(StormTheme.HEADER_TEXT);
        wordmark.add(storm);
        wordmark.add(Box.createHorizontalStrut(8));
        wordmark.add(launcher);
        wordmark.setAlignmentX(CENTER_ALIGNMENT);

        JLabel message = new JLabel("Checking for updates and restarting…");
        message.setFont(StormTheme.baseFont());
        message.setForeground(StormTheme.TEXT_DIM);
        message.setAlignmentX(CENTER_ALIGNMENT);

        panel.add(wordmark);
        panel.add(Box.createVerticalStrut(14));
        panel.add(message);

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setContentPane(panel);
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
