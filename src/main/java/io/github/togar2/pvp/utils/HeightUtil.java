package io.github.togar2.pvp.utils;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;

import java.util.OptionalInt;

public class HeightUtil {
    public static OptionalInt getHeight(Instance instance, double x, double z) {
        Chunk chunk = instance.getChunkAt(x, z);

        if (chunk == null)
            return OptionalInt.empty();

        return OptionalInt.of(chunk.worldSurfaceHeightmap().getHeight((int)x - chunk.toPosition().blockX(), (int)z - chunk.toPosition().blockZ()));
    }
}
