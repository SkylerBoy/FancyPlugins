package de.oliver.fancyholograms.listeners;

import com.destroystokyo.paper.event.player.PlayerUseUnknownEntityEvent;
import de.oliver.fancyholograms.FancyHolograms;
import de.oliver.fancyholograms.api.data.property.ClickActions;
import de.oliver.fancyholograms.api.hologram.Hologram;
import de.oliver.fancyholograms.api.events.HologramClickEvent;
import de.oliver.fancyholograms.hologram.click.ClickActionExecutor;
import de.oliver.fancyholograms.hologram.click.ClickTargets;
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

    @EventHandler
    public void onUseUnknownEntity(@NotNull final PlayerUseUnknownEntityEvent event) {
        final ClickTargets.Target target = ClickTargets.get(event.getEntityId());
        if (target == null) {
            return;
        }

        final ClickActions.ClickType type;
        if (event.isAttack()) {
            type = ClickActions.ClickType.LEFT;
        } else {
            // right clicks are sent once per hand and also as "interact at" -> only handle one of them
            if (event.getHand() != EquipmentSlot.HAND || event.getClickedRelativePosition() != null) {
                return;
            }
            type = ClickActions.ClickType.RIGHT;
        }

        final Player player = event.getPlayer();

        // the hologram could have been removed or reloaded since the hitbox was created
        final Hologram registered = FancyHolograms.get().getHologramsManager().getHologram(target.hologram().getName()).orElse(null);
        if (registered != target.hologram()) {
            ClickTargets.unregister(event.getEntityId());
            return;
        }
        if (!target.hologram().isViewer(player)) {
            return;
        }

        final ClickActions clickActions = target.hologram().getData().getClickActions();

        // cooldown per player and hologram
        final long now = System.currentTimeMillis();
        final Map<String, Long> playerClicks = lastClicks.computeIfAbsent(player.getUniqueId(), uuid -> new ConcurrentHashMap<>());
        final Long last = playerClicks.get(target.hologram().getName());
        if (last != null && now - last < clickActions.getCooldown()) {
            return;
        }
        playerClicks.put(target.hologram().getName(), now);

        final List<String> actions = clickActions.resolve(target.lineActionId(), type);
        final HologramClickEvent clickEvent = new HologramClickEvent(target.hologram(), player, type, target.lineIndex(), target.lineActionId(), actions);
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
