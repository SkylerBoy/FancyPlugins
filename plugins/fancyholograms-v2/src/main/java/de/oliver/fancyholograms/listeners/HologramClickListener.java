package de.oliver.fancyholograms.listeners;

import com.destroystokyo.paper.event.player.PlayerUseUnknownEntityEvent;
import de.oliver.fancyholograms.FancyHolograms;
import de.oliver.fancyholograms.api.data.property.ClickActions;
import de.oliver.fancyholograms.api.hologram.Hologram;
import de.oliver.fancyholograms.api.events.HologramClickEvent;
import de.oliver.fancyholograms.hologram.click.ClickActionExecutor;
import de.oliver.fancyholograms.hologram.click.ClickTargets;
import de.oliver.fancyholograms.hologram.click.InteractionHitbox;
import de.oliver.fancyholograms.hologram.version.HologramImpl;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
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
        final Hologram hologram = ClickTargets.get(event.getEntityId());
        if (hologram == null) {
            return;
        }

        FancyHolograms.get().getFancyLogger().debug("Hologram hitbox click: hologram=" + hologram.getName() + ", attack=" + event.isAttack()
                + ", hand=" + event.getHand() + ", relativePosition=" + event.getClickedRelativePosition());

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

        // the hologram could have been removed or reloaded since the hitbox was created
        final Hologram registered = FancyHolograms.get().getHologramsManager().getHologram(hologram.getName()).orElse(null);
        if (registered != hologram) {
            ClickTargets.unregister(event.getEntityId());
            return;
        }

        handleClick(event.getPlayer(), hologram, type);
    }

    /**
     * Left clicks on packet-only entities don't always reach the server as an attack (depending on the version),
     * but the client always swings the arm. On an arm swing, the hologram the player is looking at (if any, and
     * closer than the targeted block) is clicked. The hitboxes don't swing the arm on right click (no "response").
     */
    @EventHandler
    public void onArmSwing(@NotNull final PlayerAnimationEvent event) {
        FancyHolograms.get().getFancyLogger().debug("Hologram arm swing: player=" + event.getPlayer().getName() + ", type=" + event.getAnimationType());
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }

        final Player player = event.getPlayer();
        final Location eye = player.getEyeLocation();
        final Vector direction = eye.getDirection();
        final double reach = getInteractionRange(player);

        Hologram target = null;
        double targetDistance = Double.MAX_VALUE;
        for (final Hologram hologram : FancyHolograms.get().getHologramsManager().getHolograms()) {
            if (!(hologram instanceof HologramImpl impl) || !hologram.isViewer(player)) {
                continue;
            }

            final InteractionHitbox hitbox = impl.getHitbox();
            if (hitbox == null || !player.getWorld().equals(hologram.getData().getLocation().getWorld())) {
                continue;
            }

            final double distance = hitbox.rayDistance(eye.getX(), eye.getY(), eye.getZ(), direction.getX(), direction.getY(), direction.getZ(), reach);
            FancyHolograms.get().getFancyLogger().debug(String.format(java.util.Locale.ROOT,
                    "Hologram arm swing check: hologram=%s, hitbox=(%.2f, %.2f, %.2f) size=%.2fx%.2f, eye=(%.2f, %.2f, %.2f), reach=%.2f, distance=%.2f",
                    hologram.getName(), hitbox.getX(), hitbox.getY(), hitbox.getZ(), hitbox.getWidth(), hitbox.getHeight(),
                    eye.getX(), eye.getY(), eye.getZ(), reach, distance));
            if (distance >= 0 && distance < targetDistance) {
                target = hologram;
                targetDistance = distance;
            }
        }

        if (target == null) {
            return;
        }

        // a block in front of the hologram is hit first
        final RayTraceResult block = player.rayTraceBlocks(targetDistance);
        if (block != null && block.getHitPosition().distance(eye.toVector()) < targetDistance) {
            FancyHolograms.get().getFancyLogger().debug("Hologram arm swing: block in front of hologram " + target.getName() + ": " + block.getHitBlock());
            return;
        }

        FancyHolograms.get().getFancyLogger().debug("Hologram arm swing click: hologram=" + target.getName());
        handleClick(player, target, ClickActions.ClickType.LEFT);
    }

    private void handleClick(@NotNull final Player player, @NotNull final Hologram hologram, @NotNull final ClickActions.ClickType type) {
        if (!hologram.isViewer(player)) {
            return;
        }

        final ClickActions clickActions = hologram.getData().getClickActions();

        // cooldown per player and hologram (also prevents running a left click twice: attack + arm swing)
        final long now = System.currentTimeMillis();
        final Map<String, Long> playerClicks = lastClicks.computeIfAbsent(player.getUniqueId(), uuid -> new ConcurrentHashMap<>());
        final Long last = playerClicks.get(hologram.getName());
        if (last != null && now - last < clickActions.getCooldown()) {
            FancyHolograms.get().getFancyLogger().debug("Hologram click on cooldown: hologram=" + hologram.getName() + ", type=" + type);
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

    private static double getInteractionRange(Player player) {
        final AttributeInstance attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        return attribute != null ? attribute.getValue() : 3.0;
    }

    @EventHandler
    public void onQuit(@NotNull final PlayerQuitEvent event) {
        lastClicks.remove(event.getPlayer().getUniqueId());
    }
}
