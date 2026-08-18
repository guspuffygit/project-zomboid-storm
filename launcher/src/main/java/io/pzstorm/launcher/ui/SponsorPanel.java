package io.pzstorm.launcher.ui;

import io.pzstorm.launcher.A2sInfo;
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
import java.util.Timer;
import java.util.TimerTask;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Sponsored card for the bottom of the detail pane: After The Fall (PvPvE), with a live player
 * count polled over Valve's A2S protocol on the server's own game port, a Discord invite link, and
 * a one-click "Play on ATF" profile setup (the window owns the profile flow; see the constructor).
 */
public final class SponsorPanel extends JPanel {

    public static final String ATF_HOST = "40.160.20.9";
    public static final int ATF_PORT = 16261;
    private static final String DISCORD_URL = "https://discord.gg/after-the-fall";

    /** The spec says "every 5 to 10 minutes"; 7 keeps the poll rare and the count fresh enough. */
    private static final long REFRESH_MILLIS = 7 * 60_000L;

    private static final int QUERY_TIMEOUT_MILLIS = 4_000;

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

        startRefresh();
    }

    @Override
    public Dimension getMaximumSize() {
        // BoxLayout stretches to maximum size; stretch across the pane but never grow taller,
        // so the glue above keeps this card pinned to the bottom.
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    private void startRefresh() {
        Timer timer = new Timer("atf-sponsor-refresh", true);
        timer.schedule(
                new TimerTask() {
                    @Override
                    public void run() {
                        refresh();
                    }
                },
                0,
                REFRESH_MILLIS);
    }

    private void refresh() {
        A2sInfo.Result info = A2sInfo.query(ATF_HOST, ATF_PORT, QUERY_TIMEOUT_MILLIS);
        // HTML so only the count is green; unwrapped text inherits the label's dim foreground
        String text =
                info == null
                        ? "PvPvE server  ·  server offline"
                        : "<html>PvPvE server  ·  <font color='"
                                + hex(StormTheme.SUCCESS)
                                + "'>"
                                + info.players
                                + " online</font></html>";
        SwingUtilities.invokeLater(() -> playersLabel.setText(text));
    }

    private static String hex(java.awt.Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
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
