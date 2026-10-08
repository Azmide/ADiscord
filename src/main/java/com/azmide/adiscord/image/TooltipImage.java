package com.azmide.adiscord.image;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

/** Draws an item tooltip the way Minecraft shows it when you hover over an item. */
public final class TooltipImage {

    // Every Minecraft pixel is drawn as a 2x2 block
    private static final int UNIT = 2;
    private static final int PADDING = 5 * UNIT;
    private static final int TITLE_GAP = 2 * UNIT;

    // The in-game colors, already blended the way they look on screen
    private static final Color BACKGROUND = new Color(0x100010);
    private static final Color BORDER_TOP = new Color(0x24005B);
    private static final Color BORDER_BOTTOM = new Color(0x180033);

    private TooltipImage() {
    }

    /** The first line is the item name, the rest are enchantments and lore. */
    public static byte[] render(List<Component> lines) throws IOException {
        List<StyledText> texts = lines.stream()
                .map(line -> StyledText.of(line, NamedTextColor.WHITE))
                .toList();

        Graphics2D measuring = Images.measuring();
        FontMetrics metrics = measuring.getFontMetrics(Images.FONT);
        int textWidth = texts.stream().mapToInt(text -> text.width(measuring, Images.FONT)).max().orElse(0);
        measuring.dispose();

        int lineHeight = metrics.getAscent() + metrics.getDescent() + UNIT;
        int gap = texts.size() > 1 ? TITLE_GAP : 0;
        // Leave room for the shadow and for italic letters leaning to the right
        int width = textWidth + Images.SHADOW + UNIT + PADDING * 2;
        int height = texts.size() * lineHeight + gap + PADDING * 2;

        BufferedImage image = Images.create(width, height);
        Graphics2D graphics = Images.graphics(image);

        // Background with the corners left out
        graphics.setColor(BACKGROUND);
        graphics.fillRect(UNIT, 0, width - UNIT * 2, height);
        graphics.fillRect(0, UNIT, width, height - UNIT * 2);

        // Purple frame that fades from top to bottom
        graphics.setPaint(new GradientPaint(0, UNIT, BORDER_TOP, 0, height - UNIT, BORDER_BOTTOM));
        graphics.fillRect(UNIT, UNIT, width - UNIT * 2, UNIT);
        graphics.fillRect(UNIT, height - UNIT * 2, width - UNIT * 2, UNIT);
        graphics.fillRect(UNIT, UNIT, UNIT, height - UNIT * 2);
        graphics.fillRect(width - UNIT * 2, UNIT, UNIT, height - UNIT * 2);

        int y = PADDING + metrics.getAscent();
        for (int i = 0; i < texts.size(); i++) {
            texts.get(i).draw(graphics, Images.FONT, PADDING, y, Images.SHADOW);
            y += lineHeight + (i == 0 ? gap : 0);
        }

        graphics.dispose();
        return Images.png(image);
    }
}
