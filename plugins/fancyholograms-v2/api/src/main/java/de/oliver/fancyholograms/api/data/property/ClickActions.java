package de.oliver.fancyholograms.api.data.property;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Click actions of a hologram.
 * <p>
 * Stored in the hologram file like this:
 * <pre>
 * click:
 *   cooldown: 500          # milliseconds between clicks of the same player
 *   actions:
 *     hologram:            # clicking anywhere on the hologram
 *       any:
 *         - 'message: &lt;gray&gt;You clicked the hologram'
 *     shop:                # used by lines starting with &lt;action:shop&gt;
 *       left:
 *         - 'player: shop'
 *       right:
 *         - 'console: give &lt;player&gt; diamond 1'
 *         - 'sound: entity.experience_orb.pickup 1 1'
 * </pre>
 * A line with an {@code <action:id>} tag uses that action set. For each click type, the actions of the line
 * override the hologram actions; if the line has no actions for that click type, the hologram actions are used.
 */
public final class ClickActions {

    /**
     * The id of the action set used when clicking anywhere on the hologram.
     */
    public static final String HOLOGRAM_ACTION_ID = "hologram";
    public static final long DEFAULT_COOLDOWN = 500;

    private static final List<String> SECTION_COMMENTS = List.of(
            "Click actions of the hologram",
            "- 'hologram' runs when clicking anywhere on the hologram",
            "- Other action sets are used by the lines starting with <action:id> (e.g. <action:shop>)",
            "- Each set can have the lists 'left', 'right' and 'any' ('any' is used for clicks without their own list)",
            "- The actions of a line override the 'hologram' actions for that click type",
            "Actions:",
            "- 'player: <command>'               command executed by the player (as if typed in the chat)",
            "- 'console: <command>'              command executed by the console",
            "- 'suggest: <command> | <text>'     clickable chat message that writes the command in the chat input",
            "- 'message: <text>'                 message (MiniMessage and PlaceholderAPI)",
            "- 'sound: <sound> [volume] [pitch]' sound played to the player",
            "Placeholders in every action: <player>, <uuid>, <world>"
    );

    private final Map<String, ActionSet> actionSets = new LinkedHashMap<>();
    private long cooldown = DEFAULT_COOLDOWN;

    public enum ClickType {
        LEFT, RIGHT;

        public @NotNull String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Actions per click type. The "any" list is used for click types without their own list.
     */
    public record ActionSet(@NotNull List<String> any, @NotNull List<String> left, @NotNull List<String> right) {

        public @NotNull List<String> get(@NotNull ClickType type) {
            List<String> specific = type == ClickType.LEFT ? left : right;
            return specific.isEmpty() ? any : specific;
        }

        public boolean isEmpty() {
            return any.isEmpty() && left.isEmpty() && right.isEmpty();
        }
    }

    public boolean isEmpty() {
        return actionSets.values().stream().allMatch(ActionSet::isEmpty);
    }

    public long getCooldown() {
        return cooldown;
    }

    public void setCooldown(long cooldown) {
        this.cooldown = Math.max(0, cooldown);
    }

    public @NotNull Map<String, ActionSet> getActionSets() {
        return actionSets;
    }

    public @Nullable ActionSet getActionSet(@Nullable String id) {
        return id == null ? null : actionSets.get(id.toLowerCase(Locale.ROOT));
    }

    public void setActionSet(@NotNull String id, @Nullable ActionSet actionSet) {
        if (actionSet == null || actionSet.isEmpty()) {
            actionSets.remove(id.toLowerCase(Locale.ROOT));
        } else {
            actionSets.put(id.toLowerCase(Locale.ROOT), actionSet);
        }
    }

    /**
     * Resolves the actions to run for a click on a line.
     *
     * @param lineActionId the {@code <action:id>} of the clicked line, or null
     * @param type         the click type
     * @return the actions to run (empty if none)
     */
    public @NotNull List<String> resolve(@Nullable String lineActionId, @NotNull ClickType type) {
        ActionSet line = getActionSet(lineActionId);
        if (line != null && !line.get(type).isEmpty()) {
            return line.get(type);
        }

        ActionSet hologram = getActionSet(HOLOGRAM_ACTION_ID);
        return hologram != null ? hologram.get(type) : List.of();
    }

    public void read(@Nullable ConfigurationSection section) {
        actionSets.clear();
        cooldown = DEFAULT_COOLDOWN;
        if (section == null) {
            return;
        }

        cooldown = Math.max(0, section.getLong("cooldown", DEFAULT_COOLDOWN));

        ConfigurationSection actions = section.getConfigurationSection("actions");
        if (actions == null) {
            return;
        }

        for (String id : actions.getKeys(false)) {
            ConfigurationSection set = actions.getConfigurationSection(id);
            if (set == null) {
                continue;
            }

            setActionSet(id, new ActionSet(
                    List.copyOf(set.getStringList("any")),
                    List.copyOf(set.getStringList(ClickType.LEFT.key())),
                    List.copyOf(set.getStringList(ClickType.RIGHT.key()))
            ));
        }
    }

    public void write(@NotNull ConfigurationSection parent, @NotNull String path) {
        if (isEmpty()) {
            parent.set(path, null);
            return;
        }

        parent.set(path, null);
        ConfigurationSection section = parent.createSection(path);
        parent.setComments(path, SECTION_COMMENTS);
        section.set("cooldown", cooldown);
        section.setInlineComments("cooldown", List.of("milliseconds between two clicks of the same player"));
        ConfigurationSection actions = section.createSection("actions");
        for (Map.Entry<String, ActionSet> entry : actionSets.entrySet()) {
            ConfigurationSection set = actions.createSection(entry.getKey());
            ActionSet actionSet = entry.getValue();
            if (!actionSet.any().isEmpty()) set.set("any", actionSet.any());
            if (!actionSet.left().isEmpty()) set.set(ClickType.LEFT.key(), actionSet.left());
            if (!actionSet.right().isEmpty()) set.set(ClickType.RIGHT.key(), actionSet.right());
        }
    }

    public @NotNull ClickActions copy() {
        ClickActions copy = new ClickActions();
        copy.cooldown = cooldown;
        copy.actionSets.putAll(actionSets);
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ClickActions that && cooldown == that.cooldown && actionSets.equals(that.actionSets);
    }

    @Override
    public int hashCode() {
        return Objects.hash(actionSets, cooldown);
    }
}
