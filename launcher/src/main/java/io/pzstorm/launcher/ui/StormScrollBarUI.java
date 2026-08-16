package io.pzstorm.launcher.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicScrollBarUI;

/** Minimal modern scrollbar: no arrow buttons, thin rounded thumb, near-invisible track. */
public final class StormScrollBarUI extends BasicScrollBarUI {

    public static ComponentUI createUI(JComponent c) {
        return new StormScrollBarUI();
    }

    @Override
    protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
        g.setColor(StormTheme.BG);
        g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);
    }

    @Override
    protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
        if (thumbBounds.isEmpty() || !scrollbar.isEnabled()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(isThumbRollover() ? StormTheme.BTN_BORDER_HOVER : StormTheme.BTN_BORDER);
        g2.fillRoundRect(
                thumbBounds.x + 2,
                thumbBounds.y + 2,
                thumbBounds.width - 4,
                thumbBounds.height - 4,
                6,
                6);
        g2.dispose();
    }

    @Override
    protected JButton createDecreaseButton(int orientation) {
        return zeroButton();
    }

    @Override
    protected JButton createIncreaseButton(int orientation) {
        return zeroButton();
    }

    private static JButton zeroButton() {
        JButton button = new JButton();
        Dimension zero = new Dimension(0, 0);
        button.setPreferredSize(zero);
        button.setMinimumSize(zero);
        button.setMaximumSize(zero);
        button.setFocusable(false);
        return button;
    }

    @Override
    public Dimension getPreferredSize(JComponent c) {
        return scrollbar.getOrientation() == javax.swing.JScrollBar.VERTICAL
                ? new Dimension(10, super.getPreferredSize(c).height)
                : new Dimension(super.getPreferredSize(c).width, 10);
    }

    /** Suppresses the default track highlight painting. */
    @Override
    protected void paintDecreaseHighlight(Graphics g) {}

    @Override
    protected void paintIncreaseHighlight(Graphics g) {}

    @Override
    protected void installComponents() {
        super.installComponents();
        for (Component child : scrollbar.getComponents()) {
            child.setVisible(false);
        }
    }
}
