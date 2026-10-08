package com.azmide.adiscord.image;

import io.papermc.paper.text.PaperComponents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.flattener.FlattenerListener;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** A component split into pieces of plain text that each keep their own color, bold and italic. */
public final class StyledText {

    private record Run(String text, int rgb, int fontStyle) {
    }

    private final List<Run> runs;

    private StyledText(List<Run> runs) {
        this.runs = runs;
    }

    /** Translatable parts such as item and enchantment names are turned into English text. */
    public static StyledText of(Component component, TextColor fallback) {
        List<Run> runs = new ArrayList<>();
        Deque<Style> styles = new ArrayDeque<>();
        styles.push(Style.style(fallback));

        PaperComponents.flattener().flatten(component, new FlattenerListener() {
            @Override
            public void pushStyle(Style style) {
                styles.push(styles.peek().merge(style));
            }

            @Override
            public void component(String text) {
                if (text.isEmpty()) {
                    return;
                }
                Style style = styles.peek();
                TextColor color = style.color() != null ? style.color() : fallback;
                int fontStyle = (style.hasDecoration(TextDecoration.BOLD) ? Font.BOLD : Font.PLAIN)
                        | (style.hasDecoration(TextDecoration.ITALIC) ? Font.ITALIC : Font.PLAIN);
                runs.add(new Run(text, color.value(), fontStyle));
            }

            @Override
            public void popStyle(Style style) {
                styles.pop();
            }
        });
        return new StyledText(runs);
    }

    public int width(Graphics2D graphics, Font font) {
        int width = 0;
        for (Run run : runs) {
            width += graphics.getFontMetrics(font.deriveFont(run.fontStyle())).stringWidth(run.text());
        }
        return width;
    }

    /** Draws the text with a drop shadow like Minecraft does. {@code y} is the baseline. */
    public void draw(Graphics2D graphics, Font font, int x, int y, int shadow) {
        for (Run run : runs) {
            Font styled = font.deriveFont(run.fontStyle());
            graphics.setFont(styled);
            graphics.setColor(new Color(shadowColor(run.rgb())));
            graphics.drawString(run.text(), x + shadow, y + shadow);
            graphics.setColor(new Color(run.rgb()));
            graphics.drawString(run.text(), x, y);
            x += graphics.getFontMetrics(styled).stringWidth(run.text());
        }
    }

    /** Minecraft draws the shadow in the same color at a quarter of the brightness. */
    private static int shadowColor(int rgb) {
        return (rgb & 0xFCFCFC) >> 2;
    }
}
