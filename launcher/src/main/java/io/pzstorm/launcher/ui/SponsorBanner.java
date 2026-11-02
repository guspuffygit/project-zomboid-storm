package io.pzstorm.launcher.ui;

import io.pzstorm.launcher.LauncherPaths;
import io.pzstorm.launcher.Log;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * Sponsored-hosting banner under the sponsor card. The image is the sponsor's own animated GIF,
 * fetched over HTTP on a background thread at start-up and cached in the launcher directory: the
 * very first launch shows a drawn placeholder until the download lands, and every later launch
 * (online or not) shows the cached copy at once. Clicking anywhere on it opens the sponsor's site.
 * The fetch is the one request the sponsor block makes on its own; privacy policy section 4.3
 * describes it.
 */
final class SponsorBanner extends JComponent {

    static final String LINK_URL = "https://indifferentbroccoli.com/?fpr=gus";
    static final String IMAGE_URL =
            "https://d2gdx5nv84sdx2.cloudfront.net/uploads/jh35snrd/marketing_asset/banner/91584664/IB_BANNER(2).gif";
    static final String CACHE_FILE = "sponsor-banner.gif";

    private static final int NATIVE_WIDTH = 640;
    private static final int NATIVE_HEIGHT = 93;
    private static final int MAX_BYTES = 4 << 20;

    private static final Color BRAND_GREEN = new Color(96, 255, 13);
    private static final Color BRAND_BG = new Color(6, 20, 30);

    private Image image;
    private boolean hover;

    SponsorBanner(Runnable open) {
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText(
                "Indifferent Broccoli — Project Zomboid server hosting (opens your browser)");
        addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        open.run();
                    }

                    @Override
                    public void mouseEntered(MouseEvent e) {
                        hover = true;
                        repaint();
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        hover = false;
                        repaint();
                    }
                });
        // Height follows width (fixed aspect), so a width change must re-run the parent layout.
        addComponentListener(
                new ComponentAdapter() {
                    @Override
                    public void componentResized(ComponentEvent e) {
                        if (getHeight() != getPreferredSize().height) {
                            revalidate();
                        }
                    }
                });
        startLoading();
    }

    private void startLoading() {
        Path cache = LauncherPaths.launcherDir().resolve(CACHE_FILE);
        Thread loader =
                new Thread(
                        () -> {
                            byte[] cached = readCache(cache);
                            if (cached != null) {
                                show(cached);
                            }
                            byte[] fresh = fetch();
                            if (fresh != null && !Arrays.equals(fresh, cached)) {
                                writeCache(cache, fresh);
                                show(fresh);
                            }
                        },
                        "storm-launcher-sponsor-banner");
        loader.setDaemon(true);
        loader.start();
    }

    private void show(byte[] gif) {
        Image loaded = Toolkit.getDefaultToolkit().createImage(gif);
        SwingUtilities.invokeLater(
                () -> {
                    if (image != null) {
                        image.flush();
                    }
                    image = loaded;
                    repaint();
                });
    }

    private static byte[] readCache(Path cache) {
        try {
            return Files.isRegularFile(cache) ? Files.readAllBytes(cache) : null;
        } catch (Exception e) {
            Log.warn("Could not read cached sponsor banner: " + e.getMessage());
            return null;
        }
    }

    private static void writeCache(Path cache, byte[] gif) {
        try {
            Files.createDirectories(cache.getParent());
            Path tmp = cache.resolveSibling(CACHE_FILE + ".tmp");
            Files.write(tmp, gif);
            Files.move(tmp, cache, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Log.warn("Could not cache sponsor banner: " + e.getMessage());
        }
    }

    private static byte[] fetch() {
        try {
            HttpClient http =
                    HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .connectTimeout(Duration.ofSeconds(5))
                            .build();
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(IMAGE_URL))
                            .timeout(Duration.ofSeconds(20))
                            .GET()
                            .build();
            HttpResponse<byte[]> response =
                    http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            if (response.statusCode() != 200 || body.length == 0 || body.length > MAX_BYTES) {
                Log.warn(
                        "Sponsor banner fetch rejected: HTTP "
                                + response.statusCode()
                                + ", "
                                + body.length
                                + " bytes");
                return null;
            }
            return body;
        } catch (Exception e) {
            Log.warn("Sponsor banner fetch failed: " + e.getMessage());
            return null;
        }
    }

    private int layoutWidth() {
        int w = getWidth();
        if (w <= 0 && getParent() != null) {
            Insets in = getParent().getInsets();
            w = getParent().getWidth() - in.left - in.right;
        }
        return w <= 0 ? NATIVE_WIDTH : w;
    }

    @Override
    public Dimension getPreferredSize() {
        int w = layoutWidth();
        return new Dimension(w, Math.round(w * (float) NATIVE_HEIGHT / NATIVE_WIDTH));
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(NATIVE_WIDTH / 4, getPreferredSize().height);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        int w = getWidth();
        int h = getHeight();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g2.setColor(BRAND_BG);
        g2.fillRect(0, 0, w, h);
        if (image != null) {
            g2.drawImage(image, 0, 0, w, h, this);
        } else {
            paintPlaceholder(g2, w, h);
        }
        g2.setColor(hover ? BRAND_GREEN : StormTheme.BORDER);
        g2.drawRect(0, 0, w - 1, h - 1);
        g2.dispose();
    }

    private static void paintPlaceholder(Graphics2D g2, int w, int h) {
        Font name = StormTheme.displayFont(Font.BOLD, Math.max(13f, h * 0.27f));
        Font tag = StormTheme.font(Font.PLAIN, Math.max(10f, h * 0.18f));
        FontMetrics nm = g2.getFontMetrics(name);
        FontMetrics tm = g2.getFontMetrics(tag);
        int block = nm.getAscent() + nm.getDescent() + 2 + tm.getAscent() + tm.getDescent();
        int top = (h - block) / 2;
        int x = Math.max(12, h / 5);
        g2.setFont(name);
        g2.setColor(StormTheme.HEADER_TEXT);
        g2.drawString("indifferent broccoli", x, top + nm.getAscent());
        g2.setFont(tag);
        g2.setColor(StormTheme.TEXT_DIM);
        g2.drawString(
                "Project Zomboid server hosting",
                x,
                top + nm.getAscent() + nm.getDescent() + 2 + tm.getAscent());
        g2.setColor(BRAND_GREEN);
        g2.fillRect(0, 0, 3, h);
    }
}
