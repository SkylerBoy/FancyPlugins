package de.oliver.fancyholograms.hologram.click;

import de.oliver.fancyholograms.FancyHolograms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.lushplugins.chatcolorhandler.paper.PaperColor;

import java.util.List;
import java.util.Locale;

/**
 * Runs the click actions of a hologram. Supported actions:
 * <ul>
 *     <li>{@code player: <command>} - command executed by the player</li>
 *     <li>{@code console: <command>} - command executed by the console</li>
 *     <li>{@code suggest: <command> [| <text>]} - sends a chat message that puts the command in the chat input when clicked</li>
 *     <li>{@code message: <text>} - sends a message (MiniMessage and placeholders)</li>
 *     <li>{@code sound: <sound> [volume] [pitch]} - plays a sound to the player</li>
 * </ul>
 * {@code <player>}, {@code <uuid>} and {@code <world>} are replaced in every action.
 */
public final class ClickActionExecutor {

    private static final String DEFAULT_SUGGEST_TEXT = "<gray>[Click to use <yellow><command></yellow>]";

    private ClickActionExecutor() {
    }

    public static void run(@NotNull Player player, @NotNull List<String> actions) {
        for (String action : actions) {
            int colon = action.indexOf(':');
            if (colon == -1) {
                FancyHolograms.get().getFancyLogger().warn("Invalid hologram click action (missing type): " + action);
                continue;
            }

            String type = action.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = replacePlaceholders(action.substring(colon + 1).trim(), player);

            switch (type) {
                case "player" -> runForPlayer(player, () -> player.performCommand(stripSlash(value)));
                case "console" -> Bukkit.getGlobalRegionScheduler().run(FancyHolograms.get(),
                        task -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripSlash(value)));
                case "suggest" -> runForPlayer(player, () -> suggest(player, value));
                case "message" -> runForPlayer(player, () -> player.sendMessage(PaperColor.handler().translate(value, player)));
                case "sound" -> runForPlayer(player, () -> playSound(player, value));
                default -> FancyHolograms.get().getFancyLogger().warn("Unknown hologram click action type '" + type + "' in: " + action);
            }
        }
    }

    private static void runForPlayer(Player player, Runnable runnable) {
        player.getScheduler().run(FancyHolograms.get(), task -> runnable.run(), null);
    }

    private static void suggest(Player player, String value) {
        String command = value;
        String text = DEFAULT_SUGGEST_TEXT;

        int separator = value.indexOf('|');
        if (separator != -1) {
            command = value.substring(0, separator).trim();
            text = value.substring(separator + 1).trim();
        }
        if (!command.startsWith("/")) {
            command = "/" + command;
        }

        Component message = PaperColor.handler().translate(text.replace("<command>", command), player)
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command)));
        player.sendMessage(message);
    }

    private static void playSound(Player player, String value) {
        String[] parts = value.split("\\s+");
        try {
            float volume = parts.length > 1 ? Float.parseFloat(parts[1]) : 1f;
            float pitch = parts.length > 2 ? Float.parseFloat(parts[2]) : 1f;
            player.playSound(player.getLocation(), parts[0].toLowerCase(Locale.ROOT), volume, pitch);
        } catch (NumberFormatException e) {
            FancyHolograms.get().getFancyLogger().warn("Invalid volume or pitch in hologram click sound: " + value);
        }
    }

    private static String replacePlaceholders(String value, Player player) {
        return value
                .replace("<player>", player.getName())
                .replace("<uuid>", player.getUniqueId().toString())
                .replace("<world>", player.getWorld().getName());
    }

    private static String stripSlash(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }
}
