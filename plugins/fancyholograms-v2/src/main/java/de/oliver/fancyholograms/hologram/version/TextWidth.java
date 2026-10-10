package de.oliver.fancyholograms.hologram.version;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.Nullable;

/**
 * Approximates the rendered width of a text using the glyph widths of the default Minecraft font.
 * Characters outside the basic latin range are approximated with the default width.
 */
final class TextWidth {

    private static final int DEFAULT_ADVANCE = 6;

    private TextWidth() {
    }

    /**
     * @return the width of the widest line in font pixels
     */
    static int of(@Nullable Component component) {
        if (component == null) {
            return 0;
        }

        final int[] state = new int[2]; // [current line width, widest line width]
        walk(component, false, state);
        return Math.max(state[0], state[1]);
    }

    private static void walk(Component component, boolean parentBold, int[] state) {
        final TextDecoration.State boldState = component.decoration(TextDecoration.BOLD);
        final boolean bold = boldState == TextDecoration.State.NOT_SET ? parentBold : boldState == TextDecoration.State.TRUE;

        final String content = component instanceof TextComponent text
                ? text.content()
                : PlainTextComponentSerializer.plainText().serialize(component.children(java.util.List.of()));

        for (int i = 0; i < content.length(); i++) {
            final char c = content.charAt(i);
            if (c == '\n') {
                state[1] = Math.max(state[1], state[0]);
                state[0] = 0;
                continue;
            }
            state[0] += advance(c) + (bold ? 1 : 0);
        }

        for (Component child : component.children()) {
            walk(child, bold, state);
        }
    }

    /**
     * @return the horizontal advance of a character (glyph width + 1 pixel spacing)
     */
    private static int advance(char c) {
        return switch (c) {
            case ' ' -> 4;
            case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 2;
            case 'l', '`' -> 3;
            case 'I', 't', '[', ']' -> 4;
            case 'f', 'k', '"', '(', ')', '{', '}', '<', '>', '*' -> 5;
            case '@', '~' -> 7;
            default -> DEFAULT_ADVANCE;
        };
    }
}
