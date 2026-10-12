package de.oliver.fancyholograms.hologram.click;

import de.oliver.fancyholograms.api.hologram.Hologram;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps the entity ids of the hitboxes to the hologram they belong to.
 */
public final class ClickTargets {

    private static final Map<Integer, Hologram> TARGETS = new ConcurrentHashMap<>();

    private ClickTargets() {
    }

    public static void register(int entityId, @NotNull Hologram hologram) {
        TARGETS.put(entityId, hologram);
    }

    public static void unregister(int entityId) {
        TARGETS.remove(entityId);
    }

    public static void unregister(@NotNull Hologram hologram) {
        TARGETS.values().removeIf(registered -> registered == hologram);
    }

    public static @Nullable Hologram get(int entityId) {
        return TARGETS.get(entityId);
    }

    /**
     * @return the holograms that currently have a hitbox (holograms with click actions)
     */
    public static @NotNull Collection<Hologram> holograms() {
        return TARGETS.values();
    }
}
