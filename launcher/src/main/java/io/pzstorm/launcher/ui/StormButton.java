package io.pzstorm.launcher.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;

/**
 * Flat rounded button painted in the Storm palette. Three weights: {@link Variant#PRIMARY} (gold
 * fill — the one action a screen is about), {@link Variant#SECONDARY} (dark with a border), and
 * {@link Variant#GHOST} (chromeless, fades in on hover — toolbar/low-priority actions).
 */
public final class StormButton extends JButton {

    public enum Variant {
        PRIMARY,
        SECONDARY,
        GHOST
    }

    private static final int ARC = 10;

    private final Variant variant;
    private boolean hover;

    public StormButton(String text, Variant variant) {
        super(text);
        this.variant = variant;
        setContentAreaFilled(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setFont(
                variant == Variant.PRIMARY
                        ? StormTheme.font(java.awt.Font.BOLD, 14f)
                        : StormTheme.baseFont());
        setForeground(variant == Variant.PRIMARY ? StormTheme.ACCENT_TEXT : StormTheme.BTN_TEXT);
        int padX = variant == Variant.GHOST ? 10 : 16;
        int padY = variant == Variant.PRIMARY ? 10 : 7;
        setBorder(BorderFactory.createEmptyBorder(padY, padX, padY, padX));
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        addMouseListener(
                new MouseAdapter() {
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
    }

    public static StormButton primary(String text) {
        return new StormButton(text, Variant.PRIMARY);
    }

    public static StormButton secondary(String text) {
        return new StormButton(text, Variant.SECONDARY);
    }

    public static StormButton ghost(String text) {
        return new StormButton(text, Variant.GHOST);
    }

    @Override
    public Dimension getMaximumSize() {
        // BoxLayout stretches to maximum size. Primary/secondary buttons stretch full-width (the
        // detail pane wants that) but cap height; ghosts keep their natural size so a toolbar of
        // them hugs together instead of spreading across the container.
        Dimension pref = getPreferredSize();
        return variant == Variant.GHOST ? pref : new Dimension(Integer.MAX_VALUE, pref.height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();
        boolean pressed = getModel().isArmed() && getModel().isPressed();

        Color fill;
        Color line = null;
        if (!isEnabled()) {
            fill = variant == Variant.PRIMARY ? StormTheme.ACCENT_DIM : StormTheme.BTN_BG;
            if (variant == Variant.SECONDARY) {
                line = StormTheme.BORDER;
            }
            if (variant == Variant.GHOST) {
                fill = null;
            }
        } else if (variant == Variant.PRIMARY) {
            fill =
                    pressed
                            ? StormTheme.ACCENT_PRESSED
                            : hover ? StormTheme.ACCENT_HOVER : StormTheme.ACCENT;
        } else if (variant == Variant.SECONDARY) {
            fill = pressed || hover ? StormTheme.BTN_BG_HOVER : StormTheme.BTN_BG;
            line = hover ? StormTheme.BTN_BORDER_HOVER : StormTheme.BTN_BORDER;
        } else {
            fill = pressed || hover ? StormTheme.ROW_HOVER : null;
        }

        if (fill != null) {
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, w, h, ARC, ARC);
        }
        if (line != null) {
            g2.setColor(line);
            g2.drawRoundRect(0, 0, w - 1, h - 1, ARC, ARC);
        }
        if (isFocusOwner() && isEnabled()) {
            g2.setColor(
                    variant == Variant.PRIMARY
                            ? StormTheme.ACCENT_TEXT
                            : StormTheme.BTN_BORDER_HOVER);
            g2.drawRoundRect(1, 1, w - 3, h - 3, ARC - 2, ARC - 2);
        }
        g2.dispose();

        Color textColor = currentTextColor();
        if (!textColor.equals(getForeground())) {
            setForeground(textColor); // guarded: an unconditional set would repaint-loop
        }
        super.paintComponent(g);
    }

    private Color currentTextColor() {
        if (!isEnabled()) {
            return variant == Variant.PRIMARY
                    ? StormTheme.TEXT_FAINT
                    : StormTheme.BTN_TEXT_DISABLED;
        }
        if (variant == Variant.PRIMARY) {
            return StormTheme.ACCENT_TEXT;
        }
        if (variant == Variant.GHOST) {
            return hover ? StormTheme.TEXT : StormTheme.TEXT_DIM;
        }
        return StormTheme.BTN_TEXT;
    }
}
