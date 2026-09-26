package de.oliver.fancyholograms.hologram.version;

import de.oliver.fancyholograms.api.data.*;
import de.oliver.fancyholograms.api.data.property.LineSettings;
import de.oliver.fancyholograms.api.events.HologramHideEvent;
import de.oliver.fancyholograms.api.events.HologramShowEvent;
import de.oliver.fancyholograms.api.hologram.Hologram;
import de.oliver.fancysitula.api.entities.*;
import de.oliver.fancysitula.factories.FancySitula;
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
            this.state = new State(createDisplays(segments.size()), segments);
        } else {
            this.state = new State(createDisplays(1), List.of());
        }

        update();
    }

    @Override
    public void delete() {
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

        State previous = null;
        if (data instanceof TextHologramData textData) {
            final var segments = computeSegments(textData);
            if (segments.size() != current.displays().size()) {
                // the amount of display entities changed -> they have to be respawned for the viewers
                previous = current;
                current = new State(createDisplays(segments.size()), segments);
            } else {
                current = new State(current.displays(), segments);
            }
        }

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
        }

        this.state = current;

        if (previous != null) {
            respawnForViewers(previous.displays(), displays);
        }
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
            return List.of(new Segment(LineSettings.parse(""), text, Math.max(1, lines.size())));
        }

        final List<Segment> segments = new ArrayList<>();
        LineSettings groupSettings = null;
        List<String> groupLines = new ArrayList<>();
        for (LineSettings line : lines) {
            if (groupSettings != null && !groupSettings.sameSettings(line)) {
                segments.add(new Segment(groupSettings, String.join("\n", groupLines), groupLines.size()));
                groupLines = new ArrayList<>();
            }
            if (groupLines.isEmpty()) {
                groupSettings = line;
            }
            groupLines.add(line.text());
        }
        segments.add(new Segment(groupSettings, String.join("\n", groupLines), groupLines.size()));

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

    private void respawnForViewers(List<FS_Display> oldDisplays, List<FS_Display> newDisplays) {
        for (UUID viewer : getViewers()) {
            final Player player = Bukkit.getPlayer(viewer);
            if (player == null) {
                continue;
            }

            final FS_RealPlayer fsPlayer = new FS_RealPlayer(player);
            oldDisplays.forEach(display -> FancySitula.ENTITY_FACTORY.despawnEntityFor(fsPlayer, display));
            newDisplays.forEach(display -> FancySitula.ENTITY_FACTORY.spawnEntityFor(fsPlayer, display));
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
        displays.forEach(display -> FancySitula.ENTITY_FACTORY.spawnEntityFor(fsPlayer, display));

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
        displays.forEach(display -> FancySitula.ENTITY_FACTORY.despawnEntityFor(fsPlayer, display));

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
                textDisplay.setText(i < current.segments().size()
                        ? getShownText(player, current.segments().get(i).rawText())
                        : getShownText(player));
            }

            FancySitula.ENTITY_FACTORY.setEntityDataFor(fsPlayer, fsDisplay);
        }
    }

    /**
     * A group of consecutive text lines rendered by one display entity.
     */
    private record Segment(LineSettings settings, String rawText, int lineCount) {
    }

    private record State(List<FS_Display> displays, List<Segment> segments) {
        static final State EMPTY = new State(List.of(), List.of());
    }

}
