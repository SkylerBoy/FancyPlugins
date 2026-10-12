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
 *       shift_right:
 *         - 'message: &lt;gray&gt;Shift + right click'
 * </pre>
 * Click types: {@code left}, {@code right}, {@code shift_left}, {@code shift_right} and {@code any}.
 * A shift click without its own list uses the normal click list, and a click without its own list uses {@code any}.
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
            "- Each set can have the lists 'left', 'right', 'shift_left', 'shift_right' and 'any'",
            "  (a shift click without its own list uses 'left'/'right', and a click without its own list uses 'any')",
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
        LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT;

        public static @NotNull ClickType of(boolean left, boolean shift) {
            if (left) {
                return shift ? SHIFT_LEFT : LEFT;
            }
            return shift ? SHIFT_RIGHT : RIGHT;
        }

        public @NotNull String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean isShift() {
            return this == SHIFT_LEFT || this == SHIFT_RIGHT;
        }

        public boolean isLeft() {
            return this == LEFT || this == SHIFT_LEFT;
        }

        /**
         * @return the click type without shift
         */
        public @NotNull ClickType withoutShift() {
            return isLeft() ? LEFT : RIGHT;
        }
    }

    /**
     * Actions per click type. A shift click without its own list uses the normal click list, and a click type
     * without its own list uses the "any" list.
     */
    public record ActionSet(@NotNull List<String> any, @NotNull Map<ClickType, List<String>> byType) {

        public ActionSet {
            any = List.copyOf(any);
            byType = Map.copyOf(byType);
        }

        public @NotNull List<String> get(@NotNull ClickType type) {
            List<String> specific = byType.getOrDefault(type, List.of());
            if (!specific.isEmpty()) {
                return specific;
            }
            return type.isShift() ? get(type.withoutShift()) : any;
        }

        public boolean isEmpty() {
            return any.isEmpty() && byType.values().stream().allMatch(List::isEmpty);
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

            Map<ClickType, List<String>> byType = new EnumMap<>(ClickType.class);
            for (ClickType type : ClickType.values()) {
                List<String> list = set.getStringList(type.key());
                if (!list.isEmpty()) {
                    byType.put(type, list);
                }
            }
            setActionSet(id, new ActionSet(set.getStringList("any"), byType));
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
            if (!actionSet.any().isEmpty()) {
                set.set("any", actionSet.any());
            }
            for (ClickType type : ClickType.values()) {
                List<String> list = actionSet.byType().getOrDefault(type, List.of());
                if (!list.isEmpty()) {
                    set.set(type.key(), list);
                }
            }
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
