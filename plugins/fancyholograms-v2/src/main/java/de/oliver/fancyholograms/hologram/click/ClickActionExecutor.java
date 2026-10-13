package de.oliver.fancyholograms.hologram.click;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import de.oliver.fancyholograms.FancyHolograms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.lushplugins.chatcolorhandler.paper.PaperColor;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Runs the click actions of a hologram. Supported actions:
 * <ul>
 *     <li>{@code player: <command>} - command executed by the player (as if typed in the chat)</li>
 *     <li>{@code console: <command>} - command executed by the console</li>
 *     <li>{@code suggest: <command> [| <text>]} - sends a chat message that puts the command in the chat input when clicked</li>
 *     <li>{@code message: <text>} - sends a message (MiniMessage and placeholders)</li>
 *     <li>{@code sound: <sound> [volume] [pitch]} - plays a sound to the player</li>
 *     <li>{@code title: <title> [| <subtitle>] [| <fade in> <stay> <fade out>]} - shows a title (times in ticks)</li>
 *     <li>{@code actionbar: <text>} - sends an action bar message</li>
 *     <li>{@code teleport: [world] <x> <y> <z> [yaw pitch]} - teleports the player</li>
 *     <li>{@code server: <server>} - sends the player to another server of the proxy (BungeeCord/Velocity)</li>
 *     <li>{@code delay: <ticks>} - waits before running the next actions</li>
 * </ul>
 * {@code <player>}, {@code <uuid>}, {@code <world>} and PlaceholderAPI placeholders are replaced in every action,
 * when the action runs.
 */
public final class ClickActionExecutor {

    public static final String BUNGEE_CHANNEL = "BungeeCord";
    private static final String DEFAULT_SUGGEST_TEXT = "<gray>[Click to use <yellow><command></yellow>]";

    private static Method placeholderApiMethod;

    private ClickActionExecutor() {
    }

    public static void run(@NotNull Player player, @NotNull List<String> actions) {
        run(player, List.copyOf(actions), 0);
    }

    private static void run(Player player, List<String> actions, int start) {
        for (int i = start; i < actions.size(); i++) {
            final String action = actions.get(i);
            final int colon = action.indexOf(':');
            if (colon == -1) {
                warn("Invalid hologram click action (missing type): " + action);
                continue;
            }

            final String type = action.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            final String value = replacePlaceholders(action.substring(colon + 1).trim(), player);

            if (type.equals("delay")) {
                final long ticks = parseLong(value, -1);
                if (ticks < 0) {
                    warn("Invalid delay in hologram click action: " + action);
                    continue;
                }
                if (ticks > 0) {
                    final int next = i + 1;
                    player.getScheduler().runDelayed(FancyHolograms.get(), task -> run(player, actions, next), null, ticks);
                    return;
                }
                continue;
            }

            switch (type) {
                // like typing it in the chat: aliases, command listeners and "unknown command" work as usual
                case "player" -> runForPlayer(player, () -> player.chat("/" + stripSlash(value)));
                case "console" -> Bukkit.getGlobalRegionScheduler().run(FancyHolograms.get(),
                        task -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripSlash(value)));
                case "suggest" -> runForPlayer(player, () -> suggest(player, value));
                case "message" -> runForPlayer(player, () -> player.sendMessage(translate(value, player)));
                case "sound" -> runForPlayer(player, () -> playSound(player, value));
                case "title" -> runForPlayer(player, () -> showTitle(player, value));
                case "actionbar" -> runForPlayer(player, () -> player.sendActionBar(translate(value, player)));
                case "teleport" -> runForPlayer(player, () -> teleport(player, value));
                case "server" -> runForPlayer(player, () -> sendToServer(player, value));
                default -> warn("Unknown hologram click action type '" + type + "' in: " + action);
            }
        }
    }

    private static void runForPlayer(Player player, Runnable runnable) {
        player.getScheduler().run(FancyHolograms.get(), task -> runnable.run(), null);
    }

    private static Component translate(String text, Player player) {
        return PaperColor.handler().translate(text, player);
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

        Component message = translate(text.replace("<command>", command), player)
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
            warn("Invalid volume or pitch in hologram click sound: " + value);
        }
    }

    private static void showTitle(Player player, String value) {
        final String[] parts = value.split("\\|", 3);
        final Component title = translate(parts[0].trim(), player);
        final Component subtitle = parts.length > 1 ? translate(parts[1].trim(), player) : Component.empty();

        Title.Times times = Title.DEFAULT_TIMES;
        if (parts.length > 2) {
            final String[] ticks = parts[2].trim().split("\\s+");
            if (ticks.length == 3) {
                times = Title.Times.times(
                        Duration.ofMillis(parseLong(ticks[0], 10) * 50),
                        Duration.ofMillis(parseLong(ticks[1], 70) * 50),
                        Duration.ofMillis(parseLong(ticks[2], 20) * 50)
                );
            } else {
                warn("Invalid title times (expected '<fade in> <stay> <fade out>'): " + value);
            }
        }

        player.showTitle(Title.title(title, subtitle, times));
    }

    private static void teleport(Player player, String value) {
        final String[] parts = value.split("\\s+");
        int index = 0;

        World world = player.getWorld();
        if (parts.length > 0 && !isNumber(parts[0])) {
            world = Bukkit.getWorld(parts[0]);
            if (world == null) {
                warn("Unknown world in hologram click teleport: " + value);
                return;
            }
            index = 1;
        }

        if (parts.length - index != 3 && parts.length - index != 5) {
            warn("Invalid hologram click teleport (expected '[world] <x> <y> <z> [yaw pitch]'): " + value);
            return;
        }

        try {
            final Location location = new Location(world,
                    Double.parseDouble(parts[index]),
                    Double.parseDouble(parts[index + 1]),
                    Double.parseDouble(parts[index + 2]));
            if (parts.length - index == 5) {
                location.setYaw(Float.parseFloat(parts[index + 3]));
                location.setPitch(Float.parseFloat(parts[index + 4]));
            } else {
                location.setYaw(player.getLocation().getYaw());
                location.setPitch(player.getLocation().getPitch());
            }
            player.teleportAsync(location);
        } catch (NumberFormatException e) {
            warn("Invalid coordinates in hologram click teleport: " + value);
        }
    }

    private static void sendToServer(Player player, String server) {
        final ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(server);
        player.sendPluginMessage(FancyHolograms.get(), BUNGEE_CHANNEL, out.toByteArray());
    }

    private static String replacePlaceholders(String value, Player player) {
        final String replaced = value
                .replace("<player>", player.getName())
                .replace("<uuid>", player.getUniqueId().toString())
                .replace("<world>", player.getWorld().getName());
        return setPlaceholderApiPlaceholders(player, replaced);
    }

    /**
     * Replaces PlaceholderAPI placeholders if the plugin is installed (called by reflection, so it's not a dependency).
     */
    private static String setPlaceholderApiPlaceholders(Player player, String text) {
        if (text.indexOf('%') == -1) {
            return text;
        }

        final Plugin placeholderApi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (placeholderApi == null || !placeholderApi.isEnabled()) {
            return text;
        }

        try {
            if (placeholderApiMethod == null) {
                final Class<?> clazz = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, placeholderApi.getClass().getClassLoader());
                placeholderApiMethod = clazz.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            }
            return (String) placeholderApiMethod.invoke(null, player, text);
        } catch (ReflectiveOperationException | LinkageError e) {
            warn("Could not replace PlaceholderAPI placeholders: " + e.getMessage());
            return text;
        }
    }

    private static boolean isNumber(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String stripSlash(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }

    private static void warn(String message) {
        FancyHolograms.get().getFancyLogger().warn(message);
    }
}
