package io.pzstorm.launcher.ui;

import io.pzstorm.launcher.Log;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Sponsored card for the bottom of the detail pane: After The Fall (PvPvE), with a Discord invite
 * link and a one-click "Play on ATF" profile setup (the window owns the profile flow; see the
 * constructor). Deliberately no live player count: polling the server would send the user's IP to a
 * third party they never chose, so the card goes on the network only when a link is clicked.
 */
public final class SponsorPanel extends JPanel {

    public static final String ATF_HOST = "40.160.20.9";
    public static final int ATF_PORT = 16261;
    private static final String DISCORD_URL = "https://discord.gg/after-the-fall";

    /**
     * Logical logo edge. On a HiDPI display Swing scales this up, so the sponsor's art should be
     * square and at least 2x this (112 px); 256×256 PNG with a transparent background is ideal.
     */
    private static final int LOGO_SIZE = 56;

    private final JLabel playersLabel = new JLabel("PvPvE server");

    /**
     * @param playOnAtf runs when "Play on ATF" is clicked; the launcher window owns profile
     *     bookkeeping and the join flow, so the card only reports the click.
     */
    public SponsorPanel(Runnable playOnAtf) {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBackground(StormTheme.ROW_ALT);
        setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(StormTheme.BORDER),
                        BorderFactory.createEmptyBorder(10, 12, 10, 12)));

        JLabel kicker = new JLabel("STORM LAUNCHER SPONSORED BY");
        kicker.setFont(StormTheme.displayFont(Font.BOLD, 13f));
        // cool steel-blue: complements the theme's warm gold links instead of competing with them
        kicker.setForeground(new java.awt.Color(132, 178, 196));

        JLabel name = new JLabel("After The Fall");
        name.setFont(StormTheme.displayFont(Font.BOLD, 16f));
        name.setForeground(StormTheme.HEADER_TEXT);

        playersLabel.setFont(StormTheme.font(Font.PLAIN, 12f));
        playersLabel.setForeground(StormTheme.TEXT_DIM);

        JPanel links = new JPanel();
        links.setLayout(new BoxLayout(links, BoxLayout.X_AXIS));
        links.setOpaque(false);
        links.add(link("Play on ATF", playOnAtf));
        links.add(Box.createHorizontalStrut(14));
        links.add(link("Join the ATF Discord", () -> openLink(DISCORD_URL)));

        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setOpaque(false);
        for (JComponent c : new JComponent[] {kicker, name, playersLabel, links}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            text.add(c);
            text.add(Box.createVerticalStrut(3));
        }

        LogoBadge logo = new LogoBadge();
        logo.setAlignmentY(CENTER_ALIGNMENT);
        add(logo);
        add(Box.createHorizontalStrut(12));
        text.setAlignmentY(CENTER_ALIGNMENT);
        add(text);
        add(Box.createHorizontalGlue());
    }

    @Override
    public Dimension getMaximumSize() {
        // BoxLayout stretches to maximum size; stretch across the pane but never grow taller,
        // so the glue above keeps this card pinned to the bottom.
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    private static JLabel link(String text, Runnable action) {
        JLabel label = new JLabel(text);
        label.setFont(StormTheme.font(Font.BOLD, 12f));
        label.setForeground(StormTheme.ACCENT);
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        action.run();
                    }

                    @Override
                    public void mouseEntered(MouseEvent e) {
                        label.setForeground(StormTheme.ACCENT_HOVER);
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        label.setForeground(StormTheme.ACCENT);
                    }
                });
        return label;
    }

    private static void openLink(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return;
            }
        } catch (Exception e) {
            Log.warn("Could not open browser: " + e.getMessage());
        }
        Log.info("Open in your browser: " + url);
    }

    /**
     * The sponsor's logo, or a placeholder mock at the exact display size while the real art is
     * pending. Drop the final image at {@code io/pzstorm/launcher/ui/atf-logo.png} on the launcher
     * resource path and it is picked up with no code change.
     */
    private static final class LogoBadge extends JComponent {

        private final BufferedImage logo = loadLogo();

        LogoBadge() {
            Dimension size = new Dimension(LOGO_SIZE, LOGO_SIZE);
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);
        }

        private static BufferedImage loadLogo() {
            try (InputStream in = LogoBadge.class.getResourceAsStream("atf-logo.png")) {
                return in == null ? null : ImageIO.read(in);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (logo != null) {
                g2.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(logo, 0, 0, LOGO_SIZE, LOGO_SIZE, null);
            } else {
                g2.setColor(StormTheme.ACCENT_DIM);
                g2.fillRoundRect(0, 0, LOGO_SIZE, LOGO_SIZE, 12, 12);
                g2.setColor(StormTheme.ACCENT);
                g2.drawRoundRect(0, 0, LOGO_SIZE - 1, LOGO_SIZE - 1, 12, 12);
                g2.setFont(StormTheme.displayFont(Font.BOLD, 17f));
                java.awt.FontMetrics fm = g2.getFontMetrics();
                String text = "ATF";
                int x = (LOGO_SIZE - fm.stringWidth(text)) / 2;
                int y = (LOGO_SIZE - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(text, x, y);
            }
            g2.dispose();
        }
    }
}
