package com.azmide.adiscord.image;

import org.jspecify.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/** Downloads and caches player heads for the tab list picture. */
public final class Heads implements AutoCloseable {

    private static final int MAX_CACHED = 512;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Map<String, BufferedImage> cache = new ConcurrentHashMap<>();

    /**
     * Cuts the face out of the player's skin. Players without a skin, like on offline mode servers,
     * get the image behind the fallback URL instead. Completes with null when neither works.
     */
    public CompletableFuture<@Nullable BufferedImage> get(@Nullable String skinUrl, String fallbackUrl) {
        if (skinUrl == null) {
            return fetch(fallbackUrl, UnaryOperator.identity());
        }
        return fetch(skinUrl, Heads::face).thenCompose(face -> face != null
                ? CompletableFuture.completedFuture(face)
                : fetch(fallbackUrl, UnaryOperator.identity()));
    }

    @Override
    public void close() {
        http.close();
    }

    private CompletableFuture<@Nullable BufferedImage> fetch(String url, UnaryOperator<BufferedImage> transform) {
        BufferedImage cached = cache.get(url);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "ADiscord")
                    .build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(null);
        }

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    BufferedImage image = response.statusCode() == 200 ? read(response.body()) : null;
                    if (image == null) {
                        return null;
                    }
                    BufferedImage result = transform.apply(image);
                    if (cache.size() >= MAX_CACHED) {
                        cache.clear();
                    }
                    cache.put(url, result);
                    return result;
                })
                .exceptionally(error -> null);
    }

    private static @Nullable BufferedImage read(byte[] data) {
        try {
            return ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The 8x8 face from a skin texture, with the hat layer drawn on top. */
    private static BufferedImage face(BufferedImage skin) {
        BufferedImage face = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = face.createGraphics();
        graphics.drawImage(skin.getSubimage(8, 8, 8, 8), 0, 0, null);
        graphics.drawImage(skin.getSubimage(40, 8, 8, 8), 0, 0, null);
        graphics.dispose();
        return face;
    }
}
