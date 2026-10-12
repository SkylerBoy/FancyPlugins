package de.oliver.fancyholograms.api.events;

import de.oliver.fancyholograms.api.data.property.ClickActions;
import de.oliver.fancyholograms.api.hologram.Hologram;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Called when a player clicks a hologram. Cancelling it prevents the configured click actions from running.
 */
public final class HologramClickEvent extends HologramEvent {

    private static final HandlerList handlerList = new HandlerList();

    private final @NotNull Player player;
    private final @NotNull ClickActions.ClickType clickType;
    private final int lineIndex;
    private final @Nullable String lineActionId;
    private final @NotNull List<String> actions;

    public HologramClickEvent(@NotNull Hologram hologram, @NotNull Player player, @NotNull ClickActions.ClickType clickType,
                              int lineIndex, @Nullable String lineActionId, @NotNull List<String> actions) {
        super(hologram, !Bukkit.isPrimaryThread());
        this.player = player;
        this.clickType = clickType;
        this.lineIndex = lineIndex;
        this.lineActionId = lineActionId;
        this.actions = new ArrayList<>(actions);
    }

    public static HandlerList getHandlerList() {
        return handlerList;
    }

    public @NotNull Player getPlayer() {
        return player;
    }

    public @NotNull ClickActions.ClickType getClickType() {
        return clickType;
    }

    /**
     * @return the index (0-based) of the first line of the clicked line group, or -1 if no line was clicked
     * (item/block holograms, or the empty space of the hologram)
     */
    public int getLineIndex() {
        return lineIndex;
    }

    /**
     * @return the {@code <action:id>} of the clicked line, or null if the line has none
     */
    public @Nullable String getLineActionId() {
        return lineActionId;
    }

    /**
     * @return the actions that will run. The list can be modified.
     */
    public @NotNull List<String> getActions() {
        return actions;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return handlerList;
    }
}
