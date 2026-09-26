package de.oliver.fancyholograms.storage;

import de.oliver.fancyanalytics.logger.ExtendedFancyLogger;
import de.oliver.fancyholograms.FancyHolograms;
import de.oliver.fancyholograms.api.HologramStorage;
import de.oliver.fancyholograms.api.data.BlockHologramData;
import de.oliver.fancyholograms.api.data.DisplayHologramData;
import de.oliver.fancyholograms.api.data.HologramData;
import de.oliver.fancyholograms.api.data.ItemHologramData;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.hologram.Hologram;
import de.oliver.fancyholograms.api.hologram.HologramType;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * Stores every hologram in its own yaml file inside the {@code plugins/FancyHolograms/holograms/} folder.
 * <p>
 * The folder can contain any number of subfolders (with unlimited depth) to organize the holograms in groups.
 * The name of a hologram is the name of its file (without extension), unless a {@code name} key is set in the file.
 * Holograms keep the file they were loaded from, so they can be moved freely between folders (followed by a reload).
 * <p>
 * The old {@code holograms.yml} file is migrated automatically into the folder.
 */
public class FlatFileHologramStorage implements HologramStorage {

    public static final File HOLOGRAMS_FOLDER = new File("plugins/FancyHolograms/holograms");
    private static final File LEGACY_HOLOGRAMS_FILE = new File("plugins/FancyHolograms/holograms.yml");
    private static final int CONFIG_VERSION = 2;
    private static final ReadWriteLock lock = new ReentrantReadWriteLock();

    private boolean migrated = false;

    private static ExtendedFancyLogger logger() {
        return FancyHolograms.get().getFancyLogger();
    }

    @Override
    public void saveBatch(Collection<Hologram> holograms, boolean override) {
        if (override) {
            Set<Path> keep = new HashSet<>();
            for (Hologram hologram : holograms) {
                keep.add(getFile(hologram.getData()).toPath().toAbsolutePath().normalize());
            }

            for (Path file : listHologramFiles()) {
                if (!keep.contains(file.toAbsolutePath().normalize())) {
                    deleteFile(file.toFile());
                }
            }
        }

        for (Hologram hologram : holograms) {
            writeHologram(hologram.getData());
        }

        logger().debug("Saved " + holograms.size() + " holograms to files (override=" + override + ")");
    }

    @Override
    public void save(Hologram hologram) {
        writeHologram(hologram.getData());
        logger().debug("Saved hologram " + hologram.getData().getName() + " to file");
    }

    @Override
    public void delete(Hologram hologram) {
        deleteFile(getFile(hologram.getData()));
        logger().debug("Deleted hologram " + hologram.getData().getName() + " from file");
    }

    @Override
    public Collection<Hologram> loadAll() {
        List<Hologram> holograms = readHolograms(null);
        logger().debug("Loaded " + holograms.size() + " holograms from files");
        return holograms;
    }

    @Override
    public Collection<Hologram> loadAll(String world) {
        List<Hologram> holograms = readHolograms(world);
        logger().debug("Loaded " + holograms.size() + " holograms from files (world=" + world + ")");
        return holograms;
    }

    /**
     * @param world The world to load the holograms from. (null for all worlds)
     */
    private List<Hologram> readHolograms(@Nullable String world) {
        migrateLegacyFile();

        lock.readLock().lock();
        try {
            List<Hologram> holograms = new ArrayList<>();
            Map<String, Path> seenNames = new HashMap<>();

            for (Path path : listHologramFiles()) {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(path.toFile());
                String relativePath = toRelativePath(path);
                String name = config.getString("name", fileName(relativePath));

                Path duplicate = seenNames.putIfAbsent(name.toLowerCase(Locale.ROOT), path);
                if (duplicate != null) {
                    logger().warn("Skipping hologram '" + name + "' in '" + relativePath + "', because there is already a hologram with this name in '" + toRelativePath(duplicate) + "'");
                    continue;
                }

                int configVersion = config.getInt("version", CONFIG_VERSION);
                if (configVersion != CONFIG_VERSION) {
                    logger().warn("Config version of hologram '" + relativePath + "' is not " + CONFIG_VERSION + ", skipping");
                    continue;
                }

                if (world != null && !world.equals(config.getString("location.world"))) {
                    continue;
                }

                DisplayHologramData displayData = createData(config, name);
                if (displayData == null) {
                    logger().warn("Could not parse hologram type of '" + relativePath + "' - skipping hologram");
                    continue;
                }

                if (!displayData.read(config, name)) {
                    logger().warn("Could not read hologram data of '" + relativePath + "' - skipping hologram");
                    continue;
                }

                displayData.setFilePath(relativePath);

                Hologram hologram = FancyHolograms.get().getHologramManager().create(displayData);
                holograms.add(hologram);
            }

            return holograms;
        } finally {
            lock.readLock().unlock();
        }
    }

    private @Nullable DisplayHologramData createData(ConfigurationSection section, String name) {
        String typeName = section.getString("type");
        if (typeName == null) {
            return null;
        }

        HologramType type = HologramType.getByName(typeName);
        if (type == null) {
            return null;
        }

        return switch (type) {
            case TEXT -> new TextHologramData(name, new Location(null, 0, 0, 0));
            case ITEM -> new ItemHologramData(name, new Location(null, 0, 0, 0));
            case BLOCK -> new BlockHologramData(name, new Location(null, 0, 0, 0));
        };
    }

    private void writeHologram(HologramData data) {
        File file = getFile(data);

        // load the existing file to keep comments and custom keys
        YamlConfiguration config;
        lock.readLock().lock();
        try {
            config = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        } finally {
            lock.readLock().unlock();
        }

        config.set("version", CONFIG_VERSION);
        config.setInlineComments("version", List.of("DO NOT CHANGE"));
        config.set("name", fileName(data.getFilePath()).equals(data.getName()) ? null : data.getName());
        data.write(config, data.getName());

        saveConfig(config, file);
    }

    private void saveConfig(YamlConfiguration config, File file) {
        FancyHolograms.get().getFileStorageExecutor().execute(() -> {
            lock.writeLock().lock();
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    logger().error("Could not create folder " + parent.getPath());
                }
                config.save(file);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                lock.writeLock().unlock();
            }

            if (!FancyHolograms.canGet()) {
                return;
            }

            logger().debug("Saved " + file.getPath());
        });
    }

    private void deleteFile(File file) {
        FancyHolograms.get().getFileStorageExecutor().execute(() -> {
            lock.writeLock().lock();
            try {
                Files.deleteIfExists(file.toPath());
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                lock.writeLock().unlock();
            }
        });
    }

    /**
     * Searches the holograms folder (including all subfolders) for a stored hologram that would conflict with a new one.
     * This also finds holograms of worlds that are not loaded, which are not registered in the hologram manager.
     *
     * @param name     the name of the new hologram
     * @param filePath the file path of the new hologram (relative, without extension), or null for the root folder
     * @return the relative path of the conflicting file, or null if there is none
     */
    public @Nullable String findConflictingFile(@NotNull String name, @Nullable String filePath) {
        String targetPath = filePath == null || filePath.isBlank() ? sanitizeFileName(name) : filePath;

        lock.readLock().lock();
        try {
            for (Path path : listHologramFiles()) {
                String relativePath = toRelativePath(path);
                if (relativePath.equalsIgnoreCase(targetPath) || storedName(path, relativePath).equalsIgnoreCase(name)) {
                    return relativePath;
                }
            }
        } finally {
            lock.readLock().unlock();
        }

        return null;
    }

    /**
     * @return the name of the hologram stored in the file (the "name" key, or the file name)
     */
    private static String storedName(Path path, String relativePath) {
        try {
            // only parse the yaml if the file has a top-level name key
            boolean hasNameKey = Files.readAllLines(path).stream().anyMatch(line -> line.startsWith("name:"));
            if (hasNameKey) {
                String name = YamlConfiguration.loadConfiguration(path.toFile()).getString("name");
                if (name != null) {
                    return name;
                }
            }
        } catch (IOException ignored) {
        }

        return fileName(relativePath);
    }

    /**
     * Returns the file of the hologram. If the hologram has no file yet, it will be stored in the root folder.
     */
    private File getFile(HologramData data) {
        String relativePath = data.getFilePath();
        if (relativePath == null || relativePath.isBlank() || !isInsideFolder(relativePath)) {
            relativePath = sanitizeFileName(data.getName());
            data.setFilePath(relativePath);
        }

        return new File(HOLOGRAMS_FOLDER, relativePath + ".yml");
    }

    private static boolean isInsideFolder(String relativePath) {
        Path root = HOLOGRAMS_FOLDER.toPath().toAbsolutePath().normalize();
        return root.resolve(relativePath).normalize().startsWith(root);
    }

    private static List<Path> listHologramFiles() {
        if (!HOLOGRAMS_FOLDER.isDirectory()) {
            return List.of();
        }

        try (Stream<Path> stream = Files.walk(HOLOGRAMS_FOLDER.toPath())) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return fileName.endsWith(".yml") || fileName.endsWith(".yaml");
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            logger().error("Could not list hologram files");
            e.printStackTrace();
            return List.of();
        }
    }

    /**
     * @return the path relative to the holograms folder, with '/' as separator and without extension
     */
    private static String toRelativePath(Path file) {
        String relative = HOLOGRAMS_FOLDER.toPath().relativize(file).toString().replace(File.separatorChar, '/');
        int dot = relative.lastIndexOf('.');
        return dot > relative.lastIndexOf('/') ? relative.substring(0, dot) : relative;
    }

    private static String fileName(@Nullable String relativePath) {
        if (relativePath == null) {
            return "";
        }
        return relativePath.substring(relativePath.lastIndexOf('/') + 1);
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /**
     * Splits the old holograms.yml file into one file per hologram.
     */
    private synchronized void migrateLegacyFile() {
        if (migrated) {
            return;
        }
        migrated = true;

        if (!HOLOGRAMS_FOLDER.exists() && !HOLOGRAMS_FOLDER.mkdirs()) {
            logger().error("Could not create folder " + HOLOGRAMS_FOLDER.getPath());
        }

        if (!LEGACY_HOLOGRAMS_FILE.exists()) {
            return;
        }

        lock.writeLock().lock();
        try {
            YamlConfiguration legacy = YamlConfiguration.loadConfiguration(LEGACY_HOLOGRAMS_FILE);

            if (legacy.getInt("version", 1) != CONFIG_VERSION) {
                logger().warn("holograms.yml has an old config version, it will not be migrated to the holograms folder");
                return;
            }

            ConfigurationSection hologramsSection = legacy.getConfigurationSection("holograms");
            int count = 0;
            if (hologramsSection != null) {
                logger().info("Migrating holograms.yml to the holograms folder (one file per hologram)...");

                for (String name : hologramsSection.getKeys(false)) {
                    ConfigurationSection holoSection = hologramsSection.getConfigurationSection(name);
                    if (holoSection == null) {
                        continue;
                    }

                    String fileName = sanitizeFileName(name);
                    File file = new File(HOLOGRAMS_FOLDER, fileName + ".yml");
                    if (file.exists()) {
                        logger().warn("Could not migrate hologram '" + name + "', because the file " + file.getPath() + " already exists");
                        continue;
                    }

                    YamlConfiguration config = new YamlConfiguration();
                    config.set("version", CONFIG_VERSION);
                    config.setInlineComments("version", List.of("DO NOT CHANGE"));
                    if (!fileName.equals(name)) {
                        config.set("name", name);
                    }
                    for (String key : holoSection.getKeys(true)) {
                        if (!holoSection.isConfigurationSection(key)) {
                            config.set(key, holoSection.get(key));
                        }
                    }

                    config.save(file);
                    count++;
                }
            }

            File backup = new File(LEGACY_HOLOGRAMS_FILE.getParentFile(), "holograms-old.yml");
            if (!LEGACY_HOLOGRAMS_FILE.renameTo(backup)) {
                logger().error("Failed to rename holograms.yml to holograms-old.yml");
            }

            logger().info("Migrated " + count + " holograms to the holograms folder");
        } catch (IOException e) {
            logger().error("Could not migrate holograms.yml");
            e.printStackTrace();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
