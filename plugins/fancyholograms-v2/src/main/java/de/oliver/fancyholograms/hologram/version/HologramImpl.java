package de.oliver.fancyholograms.hologram.version;

import de.oliver.fancyholograms.api.data.*;
import de.oliver.fancyholograms.api.data.property.LineSettings;
import de.oliver.fancyholograms.api.events.HologramHideEvent;
import de.oliver.fancyholograms.api.events.HologramShowEvent;
import de.oliver.fancyholograms.api.hologram.Hologram;
import de.oliver.fancyholograms.hologram.click.ClickTargets;
import de.oliver.fancyholograms.hologram.click.InteractionHitbox;
import de.oliver.fancysitula.api.entities.*;
import de.oliver.fancysitula.factories.FancySitula;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class HologramImpl extends Hologram {

    /**
     * Height of a single text line in blocks at scale 1 (10 pixels * 0.025).
     */
    private static final float LINE_HEIGHT = 0.25f;

    /**
     * Size of one font pixel in blocks at scale 1.
     */
    private static final float PIXEL_SIZE = 0.025f;

    /**
     * Extra size of the click hitboxes (in blocks), so the background of the text is clickable too.
     */
    private static final float HITBOX_PADDING = 0.1f;

    /**
     * Space of the text background above and below the lines (1 font pixel), counted as part of a line when clicking.
     */
    private static final float BACKGROUND_PADDING = 0.025f;

    /**
     * The display entities and (for text holograms) the line segments they render.
     * Text holograms whose lines use per-line settings are split into one display per group of consecutive
     * lines with the same settings. Replaced atomically, so it can be read from any thread.
     */
    private volatile State state = State.EMPTY;

    public HologramImpl(@NotNull final HologramData data) {
        super(data);
    }

    @Override
    public int getEntityId() {
        final var displays = state.displays();
        return displays.isEmpty() ? -1 : displays.getFirst().getId();
    }

    @Override
    public @Nullable org.bukkit.entity.Display getDisplayEntity() {
        return null;
    }

    @Override
    public void create() {
        final var location = data.getLocation();
        if (!location.isWorldLoaded()) {
            return;
        }

        if (data instanceof TextHologramData textData) {
            final var segments = computeSegments(textData);
            this.state = new State(createDisplays(segments.size()), segments, new Vector3f[segments.size()], createHitboxes());
        } else {
            this.state = new State(createDisplays(1), List.of(), new Vector3f[0], createHitboxes());
        }

        update();
    }

    @Override
    public void delete() {
        this.state.hitboxes().forEach(hitbox -> ClickTargets.unregister(hitbox.getId()));
        this.state = State.EMPTY;
    }

    @Override
    public void update() {
        State current = this.state;
        if (current.displays().isEmpty()) {
            return;
        }

        // location data
        final var location = data.getLocation();
        if (location.getWorld() == null || !location.isWorldLoaded()) {
            return;
        }

        final State previous = current;
        final List<Segment> newSegments = data instanceof TextHologramData textData ? computeSegments(textData) : List.of();
        final int count = data instanceof TextHologramData ? newSegments.size() : 1;

        // the amount of entities changed -> they have to be respawned for the viewers
        final boolean rebuildDisplays = count != current.displays().size();
        final boolean rebuildHitboxes = hasClickActions() == current.hitboxes().isEmpty();

        current = new State(
                rebuildDisplays ? createDisplays(count) : current.displays(),
                newSegments,
                new Vector3f[newSegments.size()],
                rebuildHitboxes ? createHitboxes() : current.hitboxes()
        );

        final var displays = current.displays();
        final var segments = current.segments();

        // y offset of each segment: segments are stacked from the bottom (last segment) to the top (first segment)
        final float[] yOffsets = new float[displays.size()];
        if (data instanceof DisplayHologramData displayData && !segments.isEmpty()) {
            float height = 0;
            for (int i = segments.size() - 1; i >= 0; i--) {
                yOffsets[i] = height;
                height += segments.get(i).lineCount() * LINE_HEIGHT * segmentScale(displayData, segments.get(i)).y;
            }
        }

        for (int i = 0; i < displays.size(); i++) {
            final FS_Display fsDisplay = displays.get(i);
            final Segment segment = segments.isEmpty() ? null : segments.get(i);
            applyData(fsDisplay, segment, yOffsets[i]);
            if (i < current.baseTranslations().length) {
                current.baseTranslations()[i] = new Vector3f(fsDisplay.getTranslation());
            }
        }

        // map the hitbox to this hologram
        current.hitboxes().forEach(hitbox -> ClickTargets.register(hitbox.getId(), this));

        this.state = current;

        if (rebuildDisplays || rebuildHitboxes) {
            if (rebuildHitboxes) {
                previous.hitboxes().forEach(hitbox -> ClickTargets.unregister(hitbox.getId()));
            }
            respawnForViewers(previous, current);
        }
    }

    /**
     * @return the click hitbox of the hologram, or null if it has no click actions
     */
    public @Nullable InteractionHitbox getHitbox() {
        final var hitboxes = state.hitboxes();
        return hitboxes.isEmpty() ? null : hitboxes.getFirst();
    }

    private boolean hasClickActions() {
        return !data.getClickActions().isEmpty();
    }

    /**
     * @return one hitbox around the whole hologram, or none if the hologram has no click actions
     */
    private List<InteractionHitbox> createHitboxes() {
        return hasClickActions() ? List.of(new InteractionHitbox()) : List.of();
    }

    /**
     * Calculates which line group the player is looking at, by intersecting the view ray of the player with the
     * plane the text is rendered on (which depends on the billboard, like in the client).
     * <p>
     * The hitbox (interaction entity) can't be used for this: it's an axis-aligned square box, so a wide hologram
     * gets a deep box and the ray enters it far in front of the text, at the height of another line.
     *
     * @return the clicked line group, or null if the player isn't looking at a line
     */
    public @Nullable ClickedLine getClickedLine(@NotNull Player player) {
        final State current = this.state;
        if (current.segments().isEmpty() || !(data instanceof DisplayHologramData displayData)) {
            return null;
        }

        final var location = data.getLocation();
        final var eye = player.getEyeLocation();
        final org.bukkit.util.Vector direction = eye.getDirection();
        final org.bukkit.util.Vector origin = eye.toVector().subtract(location.toVector());

        // normal of the text plane
        final org.bukkit.util.Vector normal = switch (displayData.getBillboard()) {
            case CENTER -> direction.clone();
            case VERTICAL -> new org.bukkit.util.Vector(direction.getX(), 0, direction.getZ());
            case HORIZONTAL -> directionOf(location.getYaw(), eye.getPitch());
            case FIXED -> directionOf(location.getYaw(), location.getPitch());
        };
        if (normal.lengthSquared() < 1.0E-6) {
            return null;
        }
        normal.normalize();

        final double denominator = direction.dot(normal);
        if (Math.abs(denominator) < 1.0E-6) {
            return null;
        }
        final double distance = -origin.dot(normal) / denominator;
        if (distance < 0) {
            return null;
        }

        // "up" axis of the text plane
        final org.bukkit.util.Vector up = new org.bukkit.util.Vector(0, 1, 0).subtract(normal.clone().multiply(normal.getY()));
        if (up.lengthSquared() < 1.0E-6) {
            return null;
        }
        up.normalize();

        final org.bukkit.util.Vector hit = origin.add(direction.multiply(distance));
        final double height = hit.dot(up);

        for (int i = 0; i < current.segments().size(); i++) {
            final Vector3f base = current.baseTranslations()[i];
            if (base == null) {
                continue;
            }

            final Segment segment = current.segments().get(i);
            final double bottom = base.y - BACKGROUND_PADDING;
            final double top = base.y + segment.lineCount() * LINE_HEIGHT * current.displays().get(i).getScale().y + BACKGROUND_PADDING;
            if (height >= bottom && height <= top) {
                return new ClickedLine(segment.firstLine(), segment.settings().action());
            }
        }

        return null;
    }

    private static org.bukkit.util.Vector directionOf(float yaw, float pitch) {
        final double yawRad = Math.toRadians(yaw);
        final double pitchRad = Math.toRadians(pitch);
        final double xz = Math.cos(pitchRad);
        return new org.bukkit.util.Vector(-xz * Math.sin(yawRad), -Math.sin(pitchRad), xz * Math.cos(yawRad));
    }

    /**
     * @param firstLine index of the first line of the clicked line group
     * @param actionId  the {@code <action:id>} of the line group, or null
     */
    public record ClickedLine(int firstLine, @Nullable String actionId) {
    }

    private void applyData(FS_Display fsDisplay, @Nullable Segment segment, float yOffset) {
        fsDisplay.setLocation(data.getLocation());

        if (fsDisplay instanceof FS_TextDisplay textDisplay && data instanceof TextHologramData textData) {
            final LineSettings settings = segment != null ? segment.settings() : LineSettings.parse("");

            // line width
            textDisplay.setLineWidth(Hologram.LINE_WIDTH);

            // background
            final Color background = settings.background() != null ? settings.background() : textData.getBackground();
            if (background == null) {
                textDisplay.setBackground(1073741824); // default background
            } else if (background == Hologram.TRANSPARENT) {
                textDisplay.setBackground(0);
            } else {
                textDisplay.setBackground(background.asARGB());
            }

            textDisplay.setStyleFlags((byte) 0);
            textDisplay.setShadow(settings.textShadow() != null ? settings.textShadow() : textData.hasTextShadow());
            textDisplay.setSeeThrough(settings.seeThrough() != null ? settings.seeThrough() : textData.isSeeThrough());

            final TextDisplay.TextAlignment alignment = settings.alignment() != null ? settings.alignment() : textData.getTextAlignment();
            textDisplay.setAlignLeft(alignment == TextDisplay.TextAlignment.LEFT);
            textDisplay.setAlignRight(alignment == TextDisplay.TextAlignment.RIGHT);
        } else if (fsDisplay instanceof FS_ItemDisplay itemDisplay && data instanceof ItemHologramData itemData) {
            // item
            itemDisplay.setItem(itemData.getItemStack());
        } else if (fsDisplay instanceof FS_BlockDisplay blockDisplay && data instanceof BlockHologramData blockData) {
            // block

//            BlockType blockType = RegistryAccess.registryAccess().getRegistry(RegistryKey.BLOCK).get(blockData.getBlock().getKey());
            blockDisplay.setBlock(blockData.getBlock().createBlockData().createBlockState());
        }

        if (data instanceof DisplayHologramData displayData) {
            // billboard data
            fsDisplay.setBillboard(FS_Display.Billboard.valueOf(displayData.getBillboard().name()));

            // brightness
            if (displayData.getBrightness() != null) {
                fsDisplay.setBrightnessOverride(displayData.getBrightness().getBlockLight() << 4 | displayData.getBrightness().getSkyLight() << 20);
            }

            // entity transformation
            // the translation is applied after the billboard rotation, so stacked segments stay aligned
            final Vector3f translation = new Vector3f(displayData.getTranslation()).add(0, yOffset, 0);
            if (segment != null && segment.settings().offset() != null) {
                translation.add(segment.settings().offset());
            }
            fsDisplay.setTranslation(translation);
            fsDisplay.setScale(segment != null ? segmentScale(displayData, segment) : displayData.getScale());
            fsDisplay.setLeftRotation(new Quaternionf());
            fsDisplay.setRightRotation(new Quaternionf());

            // entity shadow
            fsDisplay.setShadowRadius(displayData.getShadowRadius());
            fsDisplay.setShadowStrength(displayData.getShadowStrength());

            fsDisplay.setViewRange(displayData.getVisibilityDistance());
        }
    }

    private static Vector3f segmentScale(DisplayHologramData displayData, Segment segment) {
        final Vector3f scale = new Vector3f(displayData.getScale());
        if (segment.settings().scale() != null) {
            scale.mul(segment.settings().scale());
        }
        return scale;
    }

    /**
     * Splits the text into segments of consecutive lines with the same per-line settings.
     * If no line has settings, the whole text is one segment (a single display entity, like before).
     */
    private static List<Segment> computeSegments(TextHologramData textData) {
        final List<LineSettings> lines = textData.getText().stream().map(LineSettings::parse).toList();

        if (lines.stream().noneMatch(LineSettings::hasSettings)) {
            final String text = String.join("\n", lines.stream().map(LineSettings::text).toList());
            return List.of(new Segment(LineSettings.parse(""), text, Math.max(1, lines.size()), 0));
        }

        final List<Segment> segments = new ArrayList<>();
        LineSettings groupSettings = null;
        int groupStart = 0;
        List<String> groupLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            final LineSettings line = lines.get(i);
            if (groupSettings != null && !groupSettings.sameSettings(line)) {
                segments.add(new Segment(groupSettings, String.join("\n", groupLines), groupLines.size(), groupStart));
                groupLines = new ArrayList<>();
            }
            if (groupLines.isEmpty()) {
                groupSettings = line;
                groupStart = i;
            }
            groupLines.add(line.text());
        }
        segments.add(new Segment(groupSettings, String.join("\n", groupLines), groupLines.size(), groupStart));

        return segments;
    }

    private List<FS_Display> createDisplays(int amount) {
        final List<FS_Display> displays = new ArrayList<>(amount);
        for (int i = 0; i < amount; i++) {
            final FS_Display fsDisplay = switch (data.getType()) {
                case TEXT -> new FS_TextDisplay();
                case ITEM -> new FS_ItemDisplay();
                case BLOCK -> new FS_BlockDisplay();
            };

            if (data instanceof DisplayHologramData dd) {
                fsDisplay.setTransformationInterpolationDuration(dd.getInterpolationDuration());
                fsDisplay.setTransformationInterpolationStartDeltaTicks(0);
            }

            displays.add(fsDisplay);
        }
        return List.copyOf(displays);
    }

    private void respawnForViewers(State oldState, State newState) {
        for (UUID viewer : getViewers()) {
            final Player player = Bukkit.getPlayer(viewer);
            if (player == null) {
                continue;
            }

            final FS_RealPlayer fsPlayer = new FS_RealPlayer(player);
            oldState.entities().forEach(entity -> FancySitula.ENTITY_FACTORY.despawnEntityFor(fsPlayer, entity));
            newState.entities().forEach(entity -> FancySitula.ENTITY_FACTORY.spawnEntityFor(fsPlayer, entity));
            refreshHologram(player);
        }
    }

    @Override
    public boolean show(@NotNull final Player player) {
        if (!new HologramShowEvent(this, player).callEvent()) {
            return false;
        }

        if (state.displays().isEmpty()) {
            create(); // try to create it if it doesn't exist every time
        }

        final var displays = state.displays();
        if (displays.isEmpty()) {
            return false; // could not be created, nothing to show
        }

        if (!data.getLocation().getWorld().getName().equals(player.getLocation().getWorld().getName())) {
            return false;
        }

        // TODO: cache player protocol version
        // TODO: fix this
//        final var protocolVersion = FancyHologramsPlugin.get().isUsingViaVersion() ? Via.getAPI().getPlayerVersion(player) : MINIMUM_PROTOCOL_VERSION;
//        if (protocolVersion < MINIMUM_PROTOCOL_VERSION) {
//            return false;
//        }

        FS_RealPlayer fsPlayer = new FS_RealPlayer(player);
        state.entities().forEach(entity -> FancySitula.ENTITY_FACTORY.spawnEntityFor(fsPlayer, entity));

        this.viewers.add(player.getUniqueId());
        refreshHologram(player);

        return true;
    }

    @Override
    public boolean hide(@NotNull final Player player) {
        if (!new HologramHideEvent(this, player).callEvent()) {
            return false;
        }

        final var displays = state.displays();
        if (displays.isEmpty()) {
            return false; // doesn't exist, nothing to hide
        }

        FS_RealPlayer fsPlayer = new FS_RealPlayer(player);
        state.entities().forEach(entity -> FancySitula.ENTITY_FACTORY.despawnEntityFor(fsPlayer, entity));

        this.viewers.remove(player.getUniqueId());
        return true;
    }


    @Override
    public void refresh(@NotNull final Player player) {
        final State current = this.state;
        if (current.displays().isEmpty()) {
            return; // doesn't exist, nothing to refresh
        }

        if (!isViewer(player)) {
            return;
        }

        FS_RealPlayer fsPlayer = new FS_RealPlayer(player);

        // texts are resolved per player (placeholders), so the alignment of the segments is calculated here as well
        final Component[] texts = new Component[current.displays().size()];
        for (int i = 0; i < texts.length; i++) {
            if (current.displays().get(i) instanceof FS_TextDisplay) {
                texts[i] = i < current.segments().size()
                        ? getShownText(player, current.segments().get(i).rawText())
                        : getShownText(player);
            }
        }
        // the text width is only needed to align line groups against each other and to size the click hitbox
        final boolean needsWidth = current.segments().size() > 1 || !current.hitboxes().isEmpty();
        final float[] widths = new float[texts.length];
        float maxWidth = 0;
        for (int i = 0; i < texts.length && needsWidth; i++) {
            if (texts[i] != null) {
                widths[i] = TextWidth.of(texts[i]) * PIXEL_SIZE * current.displays().get(i).getScale().x;
                maxWidth = Math.max(maxWidth, widths[i]);
            }
        }
        alignSegments(current, widths, maxWidth);

        for (int i = 0; i < current.displays().size(); i++) {
            final FS_Display fsDisplay = current.displays().get(i);

            FancySitula.PACKET_FACTORY.createTeleportEntityPacket(
                            fsDisplay.getId(),
                            data.getLocation().x(),
                            data.getLocation().y(),
                            data.getLocation().z(),
                            data.getLocation().getYaw(),
                            data.getLocation().getPitch(),
                            true)
                    .send(fsPlayer);

            if (fsDisplay instanceof FS_TextDisplay textDisplay) {
                textDisplay.setText(texts[i]);
            }

            FancySitula.ENTITY_FACTORY.setEntityDataFor(fsPlayer, fsDisplay);
        }

        for (final InteractionHitbox hitbox : current.hitboxes()) {
            updateHitbox(hitbox, current, maxWidth);

            FancySitula.PACKET_FACTORY.createTeleportEntityPacket(
                            hitbox.getId(),
                            hitbox.getX(),
                            hitbox.getY(),
                            hitbox.getZ(),
                            0,
                            0,
                            true)
                    .send(fsPlayer);
            FancySitula.ENTITY_FACTORY.setEntityDataFor(fsPlayer, hitbox);
        }
    }

    /**
     * Places the hitbox around the whole hologram. Interaction entities are axis-aligned boxes positioned at
     * their bottom center, so text hitboxes use the width of the widest line on both horizontal axes (the text
     * faces the player). The clicked line is calculated separately, see {@link #getClickedLine(Player)}.
     */
    private void updateHitbox(InteractionHitbox hitbox, State current, float maxTextWidth) {
        final var location = data.getLocation();
        if (!(data instanceof DisplayHologramData displayData)) {
            return;
        }

        final Vector3f scale = displayData.getScale();
        final Vector3f translation = displayData.getTranslation();

        if (data instanceof TextHologramData && !current.segments().isEmpty()) {
            float bottom = Float.MAX_VALUE;
            float top = -Float.MAX_VALUE;
            for (int i = 0; i < current.segments().size(); i++) {
                final Vector3f base = current.baseTranslations()[i] != null ? current.baseTranslations()[i] : translation;
                bottom = Math.min(bottom, base.y);
                top = Math.max(top, base.y + current.segments().get(i).lineCount() * LINE_HEIGHT * current.displays().get(i).getScale().y);
            }

            hitbox.setLocation(location.x(), location.y() + bottom - HITBOX_PADDING / 2f, location.z());
            hitbox.setSize(Math.max(maxTextWidth, 0.25f) + HITBOX_PADDING, top - bottom + HITBOX_PADDING);
        } else if (data instanceof ItemHologramData) {
            // item displays are rendered around their position
            hitbox.setLocation(location.x() + translation.x, location.y() + translation.y - scale.y / 2f, location.z() + translation.z);
            hitbox.setSize(Math.max(scale.x, scale.z), scale.y);
        } else {
            // block displays are rendered from their position (corner) to +scale
            hitbox.setLocation(location.x() + translation.x + scale.x / 2f, location.y() + translation.y, location.z() + translation.z + scale.z / 2f);
            hitbox.setSize(Math.max(scale.x, scale.z), scale.y);
        }
    }

    /**
     * The text alignment of a display only aligns the lines inside that display, and every display is centered
     * on the hologram. To align segments (separate displays) against each other, they are moved horizontally
     * relative to the widest segment.
     */
    private void alignSegments(State current, float[] widths, float maxWidth) {
        if (current.segments().size() < 2 || !(data instanceof TextHologramData textData)) {
            return;
        }

        final int count = current.segments().size();

        for (int i = 0; i < count; i++) {
            final Vector3f base = current.baseTranslations()[i];
            if (base == null) {
                continue;
            }

            final LineSettings settings = current.segments().get(i).settings();
            final TextDisplay.TextAlignment alignment = settings.alignment() != null ? settings.alignment() : textData.getTextAlignment();
            final float space = (maxWidth - widths[i]) / 2f;
            final float dx = switch (alignment) {
                case LEFT -> -space;
                case RIGHT -> space;
                case CENTER -> 0;
            };

            current.displays().get(i).setTranslation(new Vector3f(base).add(dx, 0, 0));
        }
    }

    /**
     * A group of consecutive text lines rendered by one display entity.
     */
    private record Segment(LineSettings settings, String rawText, int lineCount, int firstLine) {
    }

    /**
     * @param baseTranslations translation of each text segment before the per-player alignment offset
     * @param hitboxes         one clickable hitbox around the hologram, or none if the hologram has no click actions
     */
    private record State(List<FS_Display> displays, List<Segment> segments, Vector3f[] baseTranslations, List<InteractionHitbox> hitboxes) {
        static final State EMPTY = new State(List.of(), List.of(), new Vector3f[0], List.of());

        List<FS_Entity> entities() {
            final List<FS_Entity> entities = new ArrayList<>(displays);
            entities.addAll(hitboxes);
            return entities;
        }
    }

}
