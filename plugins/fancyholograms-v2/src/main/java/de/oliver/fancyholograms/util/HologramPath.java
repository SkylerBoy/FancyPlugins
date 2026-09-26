package de.oliver.fancyholograms.util;

import de.oliver.fancyholograms.FancyHolograms;
import de.oliver.fancyholograms.storage.FlatFileHologramStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * A hologram name given in a command, optionally prefixed with the folder it should be stored in,
 * relative to the holograms folder (e.g. "lobby/spawn/welcome").
 *
 * @param folder the folder (without trailing slash), or null for none
 * @param name   the hologram name
 */
public record HologramPath(@Nullable String folder, @NotNull String name) {

    private static final Pattern INVALID_FOLDER_CHARS = Pattern.compile("[\\\\:*?\"<>|.]");

    /**
     * Parses "folder/sub/name" into folder and name.
     *
     * @return the parsed path, or null if the folder is invalid
     */
    public static @Nullable HologramPath parse(@NotNull String input) {
        int slash = input.lastIndexOf('/');
        if (slash == -1) {
            return new HologramPath(null, input);
        }

        String folder = input.substring(0, slash);
        String name = input.substring(slash + 1);
        if (name.isEmpty() || folder.isEmpty() || INVALID_FOLDER_CHARS.matcher(folder).find()) {
            return null;
        }

        for (String part : folder.split("/", -1)) {
            if (part.isBlank()) {
                return null;
            }
        }

        return new HologramPath(folder, name);
    }

    /**
     * Checks whether a hologram with this name or file is already stored, in any folder
     * (including holograms of worlds that are not loaded).
     *
     * @param name     the hologram name
     * @param filePath the file path of the new hologram, or null for the root folder
     * @return the relative path of the conflicting file, or null if there is none
     */
    public static @Nullable String findConflictingFile(@NotNull String name, @Nullable String filePath) {
        if (FancyHolograms.get().getHologramStorage() instanceof FlatFileHologramStorage storage) {
            return storage.findConflictingFile(name, filePath);
        }
        return null;
    }

    /**
     * @return the file path relative to the holograms folder (without extension), or null if no folder is set
     */
    public @Nullable String filePath() {
        return folder == null ? null : folder + "/" + name;
    }
}
