package de.oliver.fancyholograms.listeners;

import com.destroystokyo.paper.event.player.PlayerUseUnknownEntityEvent;
import de.oliver.fancyholograms.FancyHolograms;
import de.oliver.fancyholograms.api.data.property.ClickActions;
import de.oliver.fancyholograms.api.hologram.Hologram;
import de.oliver.fancyholograms.api.events.HologramClickEvent;
import de.oliver.fancyholograms.hologram.click.ClickActionExecutor;
import de.oliver.fancyholograms.hologram.click.ClickTargets;
import de.oliver.fancyholograms.hologram.version.HologramImpl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects clicks on the hologram hitboxes (packet-only interaction entities) and runs the click actions.
 */
public final class HologramClickListener implements Listener {

    /**
     * player -> (hologram name -> time of the last click)
     */
    private final Map<UUID, Map<String, Long>> lastClicks = new ConcurrentHashMap<>();

    /**
     * Older versions send a right click twice ("interact" and "interact at"), newer versions once. Clicks of the
     * same type on the same hologram within this time are treated as one, even if the configured cooldown is 0.
     */
    private static final long DUPLICATE_CLICK_MS = 100;

    @EventHandler
    public void onUseUnknownEntity(@NotNull final PlayerUseUnknownEntityEvent event) {
        final Hologram hologram = ClickTargets.get(event.getEntityId());
        if (hologram == null) {
            return;
        }

        // the event can also be called for the off hand
        if (!event.isAttack() && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final ClickActions.ClickType type = ClickActions.ClickType.of(event.isAttack(), event.getPlayer().isSneaking());

        // the hologram could have been removed or reloaded since the hitbox was created
        final Hologram registered = FancyHolograms.get().getHologramsManager().getHologram(hologram.getName()).orElse(null);
        if (registered != hologram) {
            ClickTargets.unregister(event.getEntityId());
            return;
        }

        handleClick(event.getPlayer(), hologram, type);
    }

    private void handleClick(@NotNull final Player player, @NotNull final Hologram hologram, @NotNull final ClickActions.ClickType type) {
        if (!hologram.isViewer(player)) {
            return;
        }

        final ClickActions clickActions = hologram.getData().getClickActions();

        // cooldown per player and hologram
        final long now = System.currentTimeMillis();
        final Map<String, Long> playerClicks = lastClicks.computeIfAbsent(player.getUniqueId(), uuid -> new ConcurrentHashMap<>());
        final Long last = playerClicks.get(hologram.getName());
        if (last != null && now - last < Math.max(clickActions.getCooldown(), DUPLICATE_CLICK_MS)) {
            return;
        }
        playerClicks.put(hologram.getName(), now);

        // the hitbox covers the whole hologram, the clicked line is calculated from the view of the player
        final HologramImpl.ClickedLine line = hologram instanceof HologramImpl impl ? impl.getClickedLine(player) : null;
        final int lineIndex = line != null ? line.firstLine() : -1;
        final String lineActionId = line != null ? line.actionId() : null;

        final List<String> actions = clickActions.resolve(lineActionId, type);
        FancyHolograms.get().getFancyLogger().debug("Hologram click: hologram=" + hologram.getName() + ", type=" + type
                + ", line=" + lineIndex + ", action=" + lineActionId + ", actions=" + actions);
        final HologramClickEvent clickEvent = new HologramClickEvent(hologram, player, type, lineIndex, lineActionId, actions);
        if (!clickEvent.callEvent()) {
            return;
        }

        ClickActionExecutor.run(player, clickEvent.getActions());
    }

    @EventHandler
    public void onQuit(@NotNull final PlayerQuitEvent event) {
        lastClicks.remove(event.getPlayer().getUniqueId());
    }
}
