package de.oliver.fancyholograms.api.data.property;

import de.oliver.fancyholograms.api.data.TextHologramData;
import org.bukkit.Color;
import org.bukkit.entity.TextDisplay;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-line display settings of a text hologram.
 * <p>
 * Settings are declared with tags at the very beginning of a line, similar to MiniMessage:
 * <pre>
 * &lt;scale:2&gt;&lt;background:transparent&gt;&lt;red&gt;Big title
 * &lt;scale:0.5,0.5,0.5&gt;&lt;text_shadow:true&gt;small subtitle
 * </pre>
 * Supported tags:
 * <ul>
 *     <li>{@code <scale:x>} or {@code <scale:x,y,z>} - multiplied with the hologram scale</li>
 *     <li>{@code <background:color>} / {@code <bg:color>} - named color, #RRGGBB, #AARRGGBB, transparent or default</li>
 *     <li>{@code <text_shadow:true|false>}</li>
 *     <li>{@code <see_through:true|false>}</li>
 *     <li>{@code <align:left|center|right>} / {@code <text_alignment:...>}</li>
 *     <li>{@code <offset:x,y,z>} / {@code <translation:x,y,z>} - added to the hologram translation</li>
 * </ul>
 * Only tags at the start of the line are parsed; unknown or invalid tags stop the parsing and are left in the text.
 * Settings that are not declared are inherited from the hologram.
 *
 * @param text        the line text without the setting tags
 * @param scale       scale multiplier, or null to inherit
 * @param background  background color, or null to inherit
 * @param textShadow  text shadow, or null to inherit
 * @param seeThrough  see through, or null to inherit
 * @param alignment   text alignment, or null to inherit
 * @param offset      translation offset, or null for none
 */
public record LineSettings(
        @NotNull String text,
        @Nullable Vector3f scale,
        @Nullable Color background,
        @Nullable Boolean textShadow,
        @Nullable Boolean seeThrough,
        @Nullable TextDisplay.TextAlignment alignment,
        @Nullable Vector3f offset
) {

    /**
     * The default background color of text displays (ARGB 0x40000000).
     */
    public static final Color DEFAULT_BACKGROUND = Color.fromARGB(0x40000000);

    private static final Pattern LEADING_TAG = Pattern.compile("^<([a-zA-Z_]+):([^<>]*)>");

    /**
     * Parses the setting tags at the start of a line.
     *
     * @param raw the raw line
     * @return the parsed settings
     */
    public static @NotNull LineSettings parse(@NotNull String raw) {
        Vector3f scale = null;
        Color background = null;
        Boolean textShadow = null;
        Boolean seeThrough = null;
        TextDisplay.TextAlignment alignment = null;
        Vector3f offset = null;

        String rest = raw;
        while (true) {
            Matcher matcher = LEADING_TAG.matcher(rest);
            if (!matcher.find()) {
                break;
            }

            String key = matcher.group(1).toLowerCase(Locale.ROOT);
            String value = matcher.group(2).trim();

            try {
                switch (key) {
                    case "scale" -> scale = parseVector(value, true);
                    case "background", "bg" -> background = parseBackground(value);
                    case "text_shadow", "textshadow" -> textShadow = parseBoolean(value);
                    case "see_through", "seethrough" -> seeThrough = parseBoolean(value);
                    case "align", "alignment", "text_alignment" -> alignment = TextDisplay.TextAlignment.valueOf(value.toUpperCase(Locale.ROOT));
                    case "offset", "translation" -> offset = parseVector(value, false);
                    default -> {
                        // not one of our tags (probably a MiniMessage tag) -> stop parsing
                        return new LineSettings(rest, scale, background, textShadow, seeThrough, alignment, offset);
                    }
                }
            } catch (IllegalArgumentException e) {
                // invalid value -> keep the tag in the text so the user can see it
                break;
            }

            rest = rest.substring(matcher.end());
        }

        return new LineSettings(rest, scale, background, textShadow, seeThrough, alignment, offset);
    }

    /**
     * Removes the setting tags from the start of a line.
     */
    public static @NotNull String stripTags(@NotNull String raw) {
        return parse(raw).text();
    }

    private static Vector3f parseVector(String value, boolean uniformAllowed) {
        String[] parts = value.split(",");
        if (parts.length == 1 && uniformAllowed) {
            float v = Float.parseFloat(parts[0].trim());
            return new Vector3f(v, v, v);
        }

        if (parts.length != 3) {
            throw new IllegalArgumentException("Expected 3 values");
        }

        return new Vector3f(
                Float.parseFloat(parts[0].trim()),
                Float.parseFloat(parts[1].trim()),
                Float.parseFloat(parts[2].trim())
        );
    }

    private static boolean parseBoolean(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on" -> true;
            case "false", "no", "off" -> false;
            default -> throw new IllegalArgumentException("Invalid boolean: " + value);
        };
    }

    private static Color parseBackground(String value) {
        if (value.equalsIgnoreCase("default") || value.equalsIgnoreCase("reset")) {
            return DEFAULT_BACKGROUND;
        }

        Color color = TextHologramData.parseBackground(value);
        if (color == null) {
            throw new IllegalArgumentException("Invalid color: " + value);
        }

        return color;
    }

    /**
     * @return whether this line declares any setting
     */
    public boolean hasSettings() {
        return scale != null || background != null || textShadow != null || seeThrough != null || alignment != null || offset != null;
    }

    /**
     * @return whether both lines declare the same settings (ignoring the text)
     */
    public boolean sameSettings(@NotNull LineSettings other) {
        return Objects.equals(scale, other.scale)
                && Objects.equals(background, other.background)
                && Objects.equals(textShadow, other.textShadow)
                && Objects.equals(seeThrough, other.seeThrough)
                && alignment == other.alignment
                && Objects.equals(offset, other.offset);
    }
}
