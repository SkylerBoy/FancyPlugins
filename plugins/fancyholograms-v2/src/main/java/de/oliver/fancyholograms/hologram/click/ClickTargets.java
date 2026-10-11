package de.oliver.fancyholograms.hologram.click;

import de.oliver.fancyholograms.api.hologram.Hologram;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps the entity ids of the hitboxes to the hologram (and line group) they belong to.
 */
public final class ClickTargets {

    private static final Map<Integer, Target> TARGETS = new ConcurrentHashMap<>();

    private ClickTargets() {
    }

    /**
     * @param hologram     the clicked hologram
     * @param lineIndex    index of the first line of the clicked line group, or -1 for item/block holograms
     * @param lineActionId the {@code <action:id>} of the line group, or null
     */
    public record Target(@NotNull Hologram hologram, int lineIndex, @Nullable String lineActionId) {
    }

    public static void register(int entityId, @NotNull Target target) {
        TARGETS.put(entityId, target);
    }

    public static void unregister(int entityId) {
        TARGETS.remove(entityId);
    }

    public static @Nullable Target get(int entityId) {
        return TARGETS.get(entityId);
    }
}
