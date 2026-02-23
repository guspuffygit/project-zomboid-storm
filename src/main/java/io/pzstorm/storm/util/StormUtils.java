package io.pzstorm.storm.util;

import io.pzstorm.storm.core.StormClassLoader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;
import zombie.core.textures.Texture;

public class StormUtils {

    /**
     * Get game texture by reading the resource with specified name from stream.
     *
     * @param name the resource name.
     * @param classLoader {@code ClassLoader} used to search the resource.
     * @throws IOException if an error occurred while decoding the texture image.
     * @see StormClassLoader#getResourceAsStream(String)
     */
    public static @Nullable Texture getTextureResourceFromStream(
            String name, ClassLoader classLoader) throws IOException {
        Objects.requireNonNull(name);
        try {
            InputStream resource = classLoader.getResourceAsStream(name);
            return resource != null
                    ? new Texture(name, new BufferedInputStream(resource), false)
                    : null;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
