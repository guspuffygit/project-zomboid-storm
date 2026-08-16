package io.pzstorm.launcher.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.font.TextAttribute;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.plaf.BorderUIResource;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;
import javax.swing.text.JTextComponent;

/**
 * Shared dark theme for every launcher window, ported from the ATF Economy in-game palette (warm
 * charcoal surfaces, cream text, gold accent). The launcher jar must stay dependency-free, so this
 * is a hand-rolled Metal theme plus UIManager overrides rather than a third-party look-and-feel.
 * Typography rides the OS: the native UI sans for text, its monospace sibling for log panes.
 */
public final class StormTheme {

    public static final Color BG = new Color(24, 23, 21);
    public static final Color BG_INSET = new Color(20, 19, 17);
    public static final Color BORDER = new Color(41, 41, 38);
    public static final Color DIVIDER = new Color(46, 46, 43);
    public static final Color HEADER_BG = new Color(16, 15, 14);
    public static final Color HEADER_TEXT = new Color(245, 245, 235);
    public static final Color TEXT = new Color(237, 235, 222);
    public static final Color TEXT_DIM = new Color(158, 158, 145);
    public static final Color TEXT_FAINT = new Color(115, 115, 107);
    public static final Color ROW_ALT = new Color(28, 26, 24);
    public static final Color ROW_HOVER = new Color(36, 37, 23);
    public static final Color ROW_SELECTED = new Color(67, 71, 35);
    public static final Color ACCENT = new Color(222, 168, 51);
    public static final Color ACCENT_HOVER = new Color(233, 186, 84);
    public static final Color ACCENT_PRESSED = new Color(196, 146, 38);
    public static final Color ACCENT_TEXT = new Color(20, 18, 13);
    public static final Color ACCENT_DIM = new Color(77, 59, 20);
    public static final Color BTN_BG = new Color(33, 31, 28);
    public static final Color BTN_BG_HOVER = new Color(46, 46, 26);
    public static final Color BTN_BORDER = new Color(56, 56, 51);
    public static final Color BTN_BORDER_HOVER = new Color(115, 115, 56);
    public static final Color BTN_TEXT = new Color(219, 219, 204);
    public static final Color BTN_TEXT_DISABLED = new Color(102, 102, 97);
    public static final Color DANGER = new Color(219, 87, 87);
    public static final Color SUCCESS = new Color(140, 219, 115);

    private static final FontUIResource BASE_FONT =
            new FontUIResource(
                    systemFont(Font.SANS_SERIF, 13, "Segoe UI", "Helvetica Neue", "Noto Sans"));
    private static final FontUIResource MONO_FONT =
            new FontUIResource(
                    systemFont(Font.MONOSPACED, 12, "Cascadia Mono", "Consolas", "Menlo"));

    /** Letter-spacing for {@link #displayFont} — set off headings without a second family. */
    private static final float DISPLAY_TRACKING = 0.06f;

    private StormTheme() {}

    public static Font baseFont() {
        return BASE_FONT;
    }

    public static Font font(int style, float size) {
        return BASE_FONT.deriveFont(style, size);
    }

    /** Heading font: the base family letter-spaced — wordmark, section labels, dialog titles. */
    public static Font displayFont(int style, float size) {
        return BASE_FONT
                .deriveFont(style, size)
                .deriveFont(Map.of(TextAttribute.TRACKING, DISPLAY_TRACKING));
    }

    /** Fixed-width font for log panes. */
    public static Font monoFont(float size) {
        return MONO_FONT.deriveFont(size);
    }

    /** Border for text inputs: 1px line plus inner padding so text doesn't hug the edge. */
    public static Border fieldBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(5, 8, 5, 8));
    }

    public static void styleTextComponent(JTextComponent c) {
        c.setBackground(BG_INSET);
        c.setForeground(TEXT);
        c.setCaretColor(ACCENT);
        c.setSelectionColor(ROW_SELECTED);
        c.setSelectedTextColor(HEADER_TEXT);
    }

    /** Installs the theme process-wide. Call once, before any window is created. */
    public static void install() {
        try {
            UIManager.put("swing.boldMetal", Boolean.FALSE);
            MetalLookAndFeel.setCurrentTheme(new StormMetalTheme());
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } catch (Exception e) {
            return; // headless or exotic JRE: default L&F still works, just uglier
        }

        UIManager.put("Panel.background", BG);
        UIManager.put("OptionPane.background", BG);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Label.disabledForeground", TEXT_FAINT);

        Object fieldBorder = new BorderUIResource(fieldBorder());
        for (String key : new String[] {"TextField", "PasswordField", "FormattedTextField"}) {
            UIManager.put(key + ".background", BG_INSET);
            UIManager.put(key + ".foreground", TEXT);
            UIManager.put(key + ".caretForeground", ACCENT);
            UIManager.put(key + ".selectionBackground", ROW_SELECTED);
            UIManager.put(key + ".selectionForeground", HEADER_TEXT);
            UIManager.put(key + ".inactiveBackground", BG);
            UIManager.put(key + ".inactiveForeground", TEXT_FAINT);
            UIManager.put(key + ".border", fieldBorder);
        }
        UIManager.put("TextArea.background", BG_INSET);
        UIManager.put("TextArea.foreground", TEXT);
        UIManager.put("TextArea.caretForeground", ACCENT);
        UIManager.put("TextArea.selectionBackground", ROW_SELECTED);
        UIManager.put("TextArea.selectionForeground", HEADER_TEXT);

        UIManager.put("List.background", BG_INSET);
        UIManager.put("List.foreground", TEXT);
        UIManager.put("List.selectionBackground", ROW_SELECTED);
        UIManager.put("List.selectionForeground", HEADER_TEXT);

        UIManager.put("Button.background", BTN_BG);
        UIManager.put("Button.foreground", BTN_TEXT);
        UIManager.put("Button.select", BTN_BG_HOVER);
        UIManager.put("Button.disabledText", BTN_TEXT_DISABLED);
        UIManager.put(
                "Button.border",
                new BorderUIResource(
                        BorderFactory.createCompoundBorder(
                                BorderFactory.createLineBorder(BTN_BORDER),
                                BorderFactory.createEmptyBorder(6, 14, 6, 14))));

        UIManager.put("CheckBox.background", BG);
        UIManager.put("CheckBox.foreground", TEXT);
        UIManager.put("CheckBox.disabledText", TEXT_FAINT);
        UIManager.put("CheckBox.icon", new StormCheckBoxIcon());
        UIManager.put("RadioButton.background", BG);
        UIManager.put("RadioButton.foreground", TEXT);

        UIManager.put("ScrollPane.background", BG);
        UIManager.put("ScrollPane.border", new BorderUIResource(BorderFactory.createEmptyBorder()));
        UIManager.put("Viewport.background", BG_INSET);
        UIManager.put("ScrollBarUI", StormScrollBarUI.class.getName());
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.background", BG);

        UIManager.put("SplitPane.background", BG);
        UIManager.put("SplitPane.dividerSize", 8);
        UIManager.put("SplitPane.border", new BorderUIResource(BorderFactory.createEmptyBorder()));
        UIManager.put(
                "SplitPaneDivider.border", new BorderUIResource(BorderFactory.createEmptyBorder()));

        UIManager.put("ToolTip.background", ROW_ALT);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put(
                "ToolTip.border",
                new BorderUIResource(
                        BorderFactory.createCompoundBorder(
                                BorderFactory.createLineBorder(BTN_BORDER),
                                BorderFactory.createEmptyBorder(6, 8, 6, 8))));

        UIManager.put("Spinner.background", BG_INSET);
        UIManager.put("Spinner.foreground", TEXT);
        UIManager.put("ComboBox.background", BG_INSET);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("ComboBox.selectionBackground", ROW_SELECTED);
        UIManager.put("ComboBox.selectionForeground", HEADER_TEXT);

        UIManager.put("TitledBorder.titleColor", TEXT_DIM);
        UIManager.put("Separator.foreground", DIVIDER);
        UIManager.put("Separator.background", BG);
    }

    /**
     * First installed family from {@code families} (native OS fonts: Segoe UI on Windows, Helvetica
     * Neue on macOS, ...), else the logical {@code fallback}. Java maps an unknown family name to
     * "Dialog", which is how absence is detected.
     */
    private static Font systemFont(String fallback, int size, String... families) {
        for (String family : families) {
            Font font = new Font(family, Font.PLAIN, size);
            if (!"Dialog".equals(font.getFamily())) {
                return font;
            }
        }
        return new Font(fallback, Font.PLAIN, size);
    }

    /**
     * Metal's stock checkbox is nearly invisible on a dark background: gold rounded box with an ink
     * check when selected, bordered inset well when not.
     */
    private static final class StormCheckBoxIcon implements javax.swing.Icon {

        private static final int SIZE = 16;

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(
                    java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            boolean selected =
                    c instanceof javax.swing.AbstractButton
                            && ((javax.swing.AbstractButton) c).isSelected();
            boolean enabled = c.isEnabled();
            if (selected) {
                g2.setColor(enabled ? ACCENT : ACCENT_DIM);
                g2.fillRoundRect(x, y, SIZE, SIZE, 5, 5);
                g2.setColor(enabled ? ACCENT_TEXT : TEXT_FAINT);
                g2.setStroke(new java.awt.BasicStroke(2f));
                g2.drawPolyline(
                        new int[] {x + 4, x + 7, x + 12}, new int[] {y + 8, y + 11, y + 5}, 3);
            } else {
                g2.setColor(BG_INSET);
                g2.fillRoundRect(x, y, SIZE, SIZE, 5, 5);
                g2.setColor(enabled ? BTN_BORDER : BORDER);
                g2.drawRoundRect(x, y, SIZE - 1, SIZE - 1, 5, 5);
            }
            g2.dispose();
        }
    }

    /**
     * Recolors stock Metal so the pieces we don't custom-paint (checkboxes, spinners, file chooser,
     * option panes) come out dark too. Metal's "white" is the text-surface color and "black" the
     * text color, so swapping them flips the whole L&F dark.
     */
    private static final class StormMetalTheme extends DefaultMetalTheme {

        @Override
        public String getName() {
            return "Storm";
        }

        @Override
        protected ColorUIResource getPrimary1() {
            return new ColorUIResource(BTN_BORDER_HOVER);
        }

        @Override
        protected ColorUIResource getPrimary2() {
            return new ColorUIResource(ACCENT_DIM);
        }

        @Override
        protected ColorUIResource getPrimary3() {
            return new ColorUIResource(ROW_SELECTED);
        }

        @Override
        protected ColorUIResource getSecondary1() {
            return new ColorUIResource(HEADER_BG);
        }

        @Override
        protected ColorUIResource getSecondary2() {
            return new ColorUIResource(BTN_BORDER);
        }

        @Override
        protected ColorUIResource getSecondary3() {
            return new ColorUIResource(BG);
        }

        @Override
        protected ColorUIResource getWhite() {
            return new ColorUIResource(BG_INSET);
        }

        @Override
        protected ColorUIResource getBlack() {
            return new ColorUIResource(TEXT);
        }

        @Override
        public FontUIResource getControlTextFont() {
            return BASE_FONT;
        }

        @Override
        public FontUIResource getSystemTextFont() {
            return BASE_FONT;
        }

        @Override
        public FontUIResource getUserTextFont() {
            return BASE_FONT;
        }

        @Override
        public FontUIResource getMenuTextFont() {
            return BASE_FONT;
        }

        @Override
        public FontUIResource getWindowTitleFont() {
            return BASE_FONT;
        }

        @Override
        public FontUIResource getSubTextFont() {
            return new FontUIResource(BASE_FONT.deriveFont(11f));
        }
    }
}
