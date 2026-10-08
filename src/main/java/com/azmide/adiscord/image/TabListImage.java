package com.azmide.adiscord.image;

import org.jspecify.annotations.Nullable;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

/** Draws the online players the way the tab list looks in game. */
public final class TabListImage {

    public record Entry(StyledText name, int ping, @Nullable BufferedImage head) {
    }

    // Same as vanilla, a new column starts after 20 players
    private static final int MAX_ROWS = 20;

    private static final int PADDING = 10;
    private static final int ROW_HEIGHT = 28;
    private static final int ROW_GAP = 2;
    private static final int COLUMN_GAP = 4;
    private static final int HEAD_SIZE = 24;
    private static final int NAME_GAP = 6;
    private static final int PING_WIDTH = 19;

    private static final Color BACKGROUND = new Color(0x161616);
    private static final Color ROW = new Color(0x333333);
    private static final Color NO_HEAD = new Color(0x555555);
    private static final Color PING_ON = new Color(0x55FF55);
    private static final Color PING_OFF = new Color(0x4A4A4A);

    private TabListImage() {
    }

    public static byte[] render(List<StyledText> header, List<Entry> entries, List<StyledText> footer) throws IOException {
        Graphics2D measuring = Images.measuring();
        FontMetrics metrics = measuring.getFontMetrics(Images.FONT);
        int nameWidth = entries.stream()
                .mapToInt(entry -> entry.name().width(measuring, Images.FONT))
                .max().orElse(0);
        int textWidth = Stream.concat(header.stream(), footer.stream())
                .mapToInt(line -> line.width(measuring, Images.FONT))
                .max().orElse(0);
        measuring.dispose();

        int columns = 1;
        int rows = entries.size();
        while (rows > MAX_ROWS) {
            columns++;
            rows = (entries.size() + columns - 1) / columns;
        }

        int lineHeight = metrics.getAscent() + metrics.getDescent() + 2;
        int cellWidth = 2 + HEAD_SIZE + NAME_GAP + nameWidth + NAME_GAP * 2 + PING_WIDTH + 4;
        int gridWidth = columns * cellWidth + (columns - 1) * COLUMN_GAP;
        int gridHeight = Math.max(0, rows * (ROW_HEIGHT + ROW_GAP) - ROW_GAP);
        int headerHeight = header.isEmpty() ? 0 : header.size() * lineHeight + PADDING;
        int footerHeight = footer.isEmpty() ? 0 : footer.size() * lineHeight + PADDING;

        int width = Math.max(gridWidth, textWidth) + PADDING * 2;
        int height = headerHeight + gridHeight + footerHeight + PADDING * 2;

        BufferedImage image = Images.create(width, height);
        Graphics2D graphics = Images.graphics(image);
        graphics.setColor(BACKGROUND);
        graphics.fillRect(0, 0, width, height);

        int y = drawCentered(graphics, header, metrics, width, PADDING, lineHeight);
        if (!header.isEmpty()) {
            y += PADDING;
        }

        // Players fill the first column top to bottom before moving on, like in game
        int left = (width - gridWidth) / 2;
        for (int i = 0; i < entries.size(); i++) {
            int x = left + (i / rows) * (cellWidth + COLUMN_GAP);
            int top = y + (i % rows) * (ROW_HEIGHT + ROW_GAP);
            drawEntry(graphics, entries.get(i), metrics, x, top, cellWidth);
        }

        if (!footer.isEmpty()) {
            drawCentered(graphics, footer, metrics, width, y + gridHeight + PADDING, lineHeight);
        }

        graphics.dispose();
        return Images.png(image);
    }

    private static void drawEntry(Graphics2D graphics, Entry entry, FontMetrics metrics, int x, int y, int width) {
        graphics.setColor(ROW);
        graphics.fillRect(x, y, width, ROW_HEIGHT);

        int headX = x + 2;
        int headY = y + (ROW_HEIGHT - HEAD_SIZE) / 2;
        if (entry.head() != null) {
            graphics.drawImage(entry.head(), headX, headY, HEAD_SIZE, HEAD_SIZE, null);
        } else {
            graphics.setColor(NO_HEAD);
            graphics.fillRect(headX, headY, HEAD_SIZE, HEAD_SIZE);
        }

        int baseline = y + (ROW_HEIGHT + metrics.getAscent() - metrics.getDescent()) / 2;
        entry.name().draw(graphics, Images.FONT, headX + HEAD_SIZE + NAME_GAP, baseline, Images.SHADOW);

        drawPing(graphics, entry.ping(), x + width - PING_WIDTH - 4, y + ROW_HEIGHT - 6);
    }

    private static void drawPing(Graphics2D graphics, int ping, int x, int bottom) {
        int bars = bars(ping);
        for (int i = 0; i < 5; i++) {
            int barHeight = 4 + i * 3;
            graphics.setColor(i < bars ? PING_ON : PING_OFF);
            graphics.fillRect(x + i * 4, bottom - barHeight, 3, barHeight);
        }
    }

    /** Same thresholds as the ping icon in game. */
    private static int bars(int ping) {
        if (ping < 0) {
            return 0;
        }
        if (ping < 150) {
            return 5;
        }
        if (ping < 300) {
            return 4;
        }
        if (ping < 600) {
            return 3;
        }
        return ping < 1000 ? 2 : 1;
    }

    private static int drawCentered(Graphics2D graphics, List<StyledText> lines, FontMetrics metrics,
                                    int width, int y, int lineHeight) {
        for (StyledText line : lines) {
            int lineWidth = line.width(graphics, Images.FONT);
            line.draw(graphics, Images.FONT, (width - lineWidth) / 2, y + metrics.getAscent(), Images.SHADOW);
            y += lineHeight;
        }
        return y;
    }
}
