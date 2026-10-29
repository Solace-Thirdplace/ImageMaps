package net.craftcitizen.imagemaps;

import com.google.common.io.Files;
import net.craftcitizen.imagemaps.core.Utils;
import net.craftcitizen.imagemaps.core.MessageLevel;
import net.craftcitizen.imagemaps.core.MessageUtil;
import net.craftcitizen.imagemaps.core.Tuple;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

// TODO permissions per image or folder
// TODO per-user maps
public class ImageMaps extends JavaPlugin implements Listener {
    private static final String MAPS_YML = "maps.yml";
    private static final String CONFIG_VERSION_KEY = "storageVersion";
    private static final int CONFIG_VERSION = 1;
    private static final long AUTOSAVE_PERIOD = 18000L; // 15 minutes

    public static final String PLACEMENT_METADATA = "imagemaps.place";

    public static final int MAP_WIDTH = 128;
    public static final int MAP_HEIGHT = 128;
    private static final String IMAGES_DIR = "images";

    // Shared between region threads (placement, player commands), the global region thread (console commands,
    // autosave) and async threads (download, save), so both maps must be concurrent.
    private final Map<String, BufferedImage> imageCache = new ConcurrentHashMap<>();
    private final Map<ImageMap, Integer> maps = new ConcurrentHashMap<>();
    // serialises "look up or create the map for this image tile", so two regions never create duplicates
    private final Object mapCreationLock = new Object();
    // serialises writes of maps.yml (autosave, delete and shutdown can overlap)
    private final Object saveLock = new Object();

    private Material toggleItem;

    static {
        ConfigurationSerialization.registerClass(ImageMap.class);
    }

    @Override
    public void onEnable() {
        BaseComponent prefix = new TextComponent(new ComponentBuilder("[").color(ChatColor.GRAY).append("ImageMaps")
                                                                          .color(ChatColor.AQUA).append("]")
                                                                          .color(ChatColor.GRAY).create());
        MessageUtil.registerPlugin(this, prefix, ChatColor.GRAY, ChatColor.YELLOW, ChatColor.RED, ChatColor.DARK_RED,
                                   ChatColor.DARK_AQUA);

        if (!new File(getDataFolder(), IMAGES_DIR).exists())
            new File(getDataFolder(), IMAGES_DIR).mkdirs();

        saveDefaultConfig();

        toggleItem = Material.matchMaterial(getConfig().getString("toggleItem", Material.WOODEN_HOE.name()));
        if (toggleItem == null) {
            toggleItem = Material.WOODEN_HOE;
            getLogger().warning("Given toggleItem is invalid, defaulting to WOODEN_HOE");
        }

        getCommand("imagemap").setExecutor(new ImageMapCommandHandler(this));
        getServer().getPluginManager().registerEvents(this, this);

        loadMaps();

        getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> saveMaps(), AUTOSAVE_PERIOD, AUTOSAVE_PERIOD);
    }

    @Override
    public void onDisable() {
        saveMaps();
    }

    @EventHandler(ignoreCancelled = true)
    public void onToggleFrameProperty(PlayerInteractEntityEvent event) {
        if (!isInvisibilitySupported())
            return;

        if (event.getRightClicked().getType() != EntityType.ITEM_FRAME
            && (!isGlowingSupported() || event.getRightClicked().getType() != EntityType.GLOW_ITEM_FRAME))
            return;

        ItemFrame frame = (ItemFrame) event.getRightClicked();
        Player p = event.getPlayer();

        if (p.getInventory().getItemInMainHand().getType() != toggleItem)
            return;

        if (p.isSneaking()) {
            if (p.hasPermission("imagemaps.toggleFixed")) {
                event.setCancelled(true);
                frame.setFixed(!frame.isFixed());
                MessageUtil.sendMessage(this, p, MessageLevel.INFO,
                                        String.format("Frame set to %s.", frame.isFixed() ? "fixed" : "unfixed"));
            }
        }
        else if (p.hasPermission("imagemaps.toggleVisible")) {
            event.setCancelled(true);
            frame.setVisible(!frame.isVisible());
            MessageUtil.sendMessage(this, p, MessageLevel.INFO,
                                    String.format("Frame set to %s.", frame.isVisible() ? "visible" : "invisible"));
        }
    }

    public boolean isInvisibilitySupported() {
        return Utils.getMCVersion().isAtLeast(1, 16, 0);
    }

    public boolean isGlowingSupported() {
        return Utils.getMCVersion().isAtLeast(1, 17, 0);
    }

    public boolean isUpDownFaceSupported() {
        return Utils.getMCVersion().isAtLeast(1, 14, 4);
    }

    public boolean isSetTrackingSupported() {
        return Utils.getMCVersion().isAtLeast(1, 14, 0);
    }

    private void saveMaps() {
        FileConfiguration config = new YamlConfiguration();
        config.set(CONFIG_VERSION_KEY, CONFIG_VERSION);
        config.set("maps", maps.entrySet().stream().collect(Collectors.toMap(Entry::getValue, Entry::getKey)));

        Runnable saveTask = () -> {
            synchronized (saveLock) {
                try {
                    config.save(new File(getDataFolder(), MAPS_YML));
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };

        if (isEnabled())
            getServer().getAsyncScheduler().runNow(this, task -> saveTask.run());
        else
            saveTask.run();
    }

    private void loadMaps() {
        File configFile = new File(getDataFolder(), MAPS_YML);

        if (!configFile.exists())
            return;

        Configuration config = YamlConfiguration.loadConfiguration(configFile);
        int version = config.getInt(CONFIG_VERSION_KEY, -1);

        if (version == -1)
            config = convertLegacyMaps(config);

        ConfigurationSection section = config.getConfigurationSection("maps");
        if (section != null)
            section.getValues(false).forEach((a, b) -> {
                int id = Integer.parseInt(a);
                ImageMap imageMap = (ImageMap) b;
                @SuppressWarnings("deprecation")
                MapView map = Bukkit.getMap(id);
                BufferedImage image = getImage(imageMap.getFilename());
                maps.put(imageMap, id);

                if (image == null) {
                    getLogger().warning(() -> "Image file " + imageMap.getFilename() + " not found!");
                    return;
                }
                if (map == null) {
                    getLogger().warning(() -> "Map " + id + " referenced but does not exist!");
                    return;
                }

                if (isSetTrackingSupported())
                    map.setTrackingPosition(false);
                map.getRenderers().forEach(map::removeRenderer);
                map.addRenderer(new ImageMapRenderer(this, image, imageMap.getX(), imageMap.getY(),
                                                     imageMap.getScale()));
            });
    }

    public int cleanupMaps() {
        int start = maps.size();

        maps.entrySet().removeIf(a -> {
            @SuppressWarnings("deprecation")
            MapView map = Bukkit.getMap(a.getValue().intValue());
            BufferedImage image = getImage(a.getKey().getFilename());

            return map == null || image == null;
        });

        return start - maps.size();
    }

    private Configuration convertLegacyMaps(Configuration config) {
        getLogger().info("Converting maps from Version <1.0");

        try {
            Files.copy(new File(getDataFolder(), MAPS_YML), new File(getDataFolder(), MAPS_YML + ".backup"));
        }
        catch (IOException e) {
            getLogger().severe("Failed to backup maps.yml!");
            e.printStackTrace();
        }

        Map<Integer, ImageMap> map = new HashMap<>();

        for (String key : config.getKeys(false)) {
            int id = Integer.parseInt(key);
            String image = config.getString(key + ".image");
            int x = config.getInt(key + ".x") / MAP_WIDTH;
            int y = config.getInt(key + ".y") / MAP_HEIGHT;
            double scale = config.getDouble(key + ".scale", 1.0);
            map.put(id, new ImageMap(image, x, y, scale));
        }

        config = new YamlConfiguration();
        config.set(CONFIG_VERSION_KEY, CONFIG_VERSION);
        config.createSection("maps", map);
        return config;
    }

    public boolean hasImage(String filename) {
        if (imageCache.containsKey(filename.toLowerCase()))
            return true;

        File file = new File(getDataFolder(), IMAGES_DIR + File.separatorChar + filename);

        return file.exists() && getImage(filename) != null;
    }

    // TODO stop returning null, begin throwing exception
    public BufferedImage getImage(String filename) {
        if (filename.contains("/") || filename.contains("\\") || filename.contains(":")) {
            getLogger().warning("Someone tried to get image with illegal characters in file name.");
            return null;
        }

        if (imageCache.containsKey(filename.toLowerCase()))
            return imageCache.get(filename.toLowerCase());

        File file = new File(getDataFolder(), IMAGES_DIR + File.separatorChar + filename);
        BufferedImage image = null;

        if (!file.exists())
            return null;

        try {
            image = ImageIO.read(file);
            if (image != null) // ConcurrentHashMap rejects null values
                imageCache.put(filename.toLowerCase(), image);
        }
        catch (IOException e) {
            getLogger().log(Level.SEVERE, String.format("Error while trying to read image %s.", file.getName()), e);
        }

        if (image == null)
            getLogger().log(Level.WARNING, () -> String.format("Failed to read file as image %s.", file.getName()));

        return image;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (!player.hasMetadata(PLACEMENT_METADATA))
            return;

        if (event.getAction() == Action.RIGHT_CLICK_AIR) {
            player.removeMetadata(PLACEMENT_METADATA, this);
            MessageUtil.sendMessage(this, player, MessageLevel.NORMAL, "Image placement cancelled.");
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK)
            return;

        PlacementData data = (PlacementData) player.getMetadata(PLACEMENT_METADATA).get(0).value();
        PlacementResult result = placeImage(player, event.getClickedBlock(), event.getBlockFace(), data);

        switch (result) {
            case INVALID_FACING:
                MessageUtil.sendMessage(this, player, MessageLevel.WARNING,
                                        "You can't place an image on this block face.");
                break;
            case INVALID_DIRECTION:
                MessageUtil.sendMessage(this, player, MessageLevel.WARNING, "Couldn't calculate how to place the map.");
                break;
            case EVENT_CANCELLED:
                MessageUtil.sendMessage(this, player, MessageLevel.NORMAL,
                                        "Image placement cancelled by another plugin.");
                break;
            case INSUFFICIENT_SPACE:
                MessageUtil.sendMessage(this, player, MessageLevel.NORMAL,
                                        "Map couldn't be placed, the space is blocked.");
                break;
            case INSUFFICIENT_WALL:
                MessageUtil.sendMessage(this, player, MessageLevel.NORMAL,
                                        "Map couldn't be placed, the supporting wall is too small.");
                break;
            case OVERLAPPING_ENTITY:
                MessageUtil.sendMessage(this, player, MessageLevel.NORMAL,
                                        "Map couldn't be placed, there is another entity in the way.");
                break;
            case AREA_NOT_OWNED:
                MessageUtil.sendMessage(this, player, MessageLevel.NORMAL,
                                        "Map couldn't be placed, the image reaches too far from you.");
                break;
            case SUCCESS:
                break;
        }

        player.removeMetadata(PLACEMENT_METADATA, this);
        event.setCancelled(true);
    }

    private PlacementResult placeImage(Player player, Block block, BlockFace face, PlacementData data) {
        if (!isAxisAligned(face)) {
            getLogger().severe("Someone tried to create an image with an invalid block facing");
            return PlacementResult.INVALID_FACING;
        }

        if (face.getModY() != 0 && !isUpDownFaceSupported())
            return PlacementResult.INVALID_FACING;

        Block b = block.getRelative(face);
        BufferedImage image = getImage(data.getFilename());
        Tuple<Integer, Integer> size = getImageSize(data.getFilename(), data.getSize());
        BlockFace widthDirection = calculateWidthDirection(player, face);
        BlockFace heightDirection = calculateHeightDirection(player, face);

        if (widthDirection == null || heightDirection == null)
            return PlacementResult.INVALID_DIRECTION;

        // On Folia this runs on the clicking player's region thread, which may only touch blocks and entities in
        // chunks it owns. A very large image can reach past that region; refuse it rather than read or spawn
        // across threads. On Paper/Purpur the main thread owns everything, so this never fails there.
        for (int x = 0; x < size.getKey(); x++)
            for (int y = 0; y < size.getValue(); y++)
                if (!getServer().isOwnedByCurrentRegion(block.getRelative(widthDirection, x)
                                                             .getRelative(heightDirection, y))
                    || !getServer().isOwnedByCurrentRegion(b.getRelative(widthDirection, x)
                                                            .getRelative(heightDirection, y)))
                    return PlacementResult.AREA_NOT_OWNED;

        // check for space
        for (int x = 0; x < size.getKey(); x++)
            for (int y = 0; y < size.getValue(); y++) {
                Block frameBlock = b.getRelative(widthDirection, x).getRelative(heightDirection, y);

                if (!block.getRelative(widthDirection, x).getRelative(heightDirection, y).getType().isSolid())
                    return PlacementResult.INSUFFICIENT_WALL;
                if (frameBlock.getType().isSolid())
                    return PlacementResult.INSUFFICIENT_SPACE;
                if (!b.getWorld().getNearbyEntities(frameBlock.getLocation().add(0.5, 0.5, 0.5), 0.5, 0.5, 0.5,
                                                    Hanging.class::isInstance)
                      .isEmpty())
                    return PlacementResult.OVERLAPPING_ENTITY;
            }

        ImagePlaceEvent event = new ImagePlaceEvent(player, block, widthDirection, heightDirection, size.getKey(),
                                                    size.getValue(), data);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled())
            return PlacementResult.EVENT_CANCELLED;

        // spawn item frame
        for (int x = 0; x < size.getKey(); x++)
            for (int y = 0; y < size.getValue(); y++) {
                Class<? extends ItemFrame> itemFrameClass = data.isGlowing() ? GlowItemFrame.class : ItemFrame.class;
                ItemFrame frame = block.getWorld().spawn(
                                                         b.getRelative(widthDirection, x)
                                                          .getRelative(heightDirection, y).getLocation(),
                                                         itemFrameClass);
                frame.setFacingDirection(face);
                frame.setItem(getMapItem(image, x, y, data));
                frame.setRotation(facingToRotation(heightDirection, widthDirection));

                if (isInvisibilitySupported()) {
                    frame.setFixed(data.isFixed());
                    frame.setVisible(!data.isInvisible());
                }
            }

        return PlacementResult.SUCCESS;
    }

    public boolean deleteImage(String filename) {
        File file = new File(getDataFolder(), IMAGES_DIR + File.separatorChar + filename);

        boolean fileDeleted = false;
        if (file.exists()) {
            fileDeleted = file.delete();
        }

        imageCache.remove(filename.toLowerCase());

        Iterator<Entry<ImageMap, Integer>> it = maps.entrySet().iterator();
        while (it.hasNext()) {
            Entry<ImageMap, Integer> entry = it.next();
            ImageMap imageMap = entry.getKey();
            if (!imageMap.getFilename().equalsIgnoreCase(filename)) {
                continue;
            }

            @SuppressWarnings("deprecation")
            MapView map = Bukkit.getMap(entry.getValue());

            if (map == null) {
                continue;
            }
            map.getRenderers().forEach(map::removeRenderer);
            it.remove();
        }

        saveMaps();
        return fileDeleted;
    }

    @SuppressWarnings("deprecation")
    public boolean reloadImage(String filename) {
        if (!imageCache.containsKey(filename.toLowerCase()))
            return false;

        imageCache.remove(filename.toLowerCase());
        BufferedImage image = getImage(filename);

        if (image == null) {
            getLogger().warning(() -> "Failed to reload image: " + filename);
            return false;
        }

        maps.entrySet().stream().filter(a -> a.getKey().getFilename().equalsIgnoreCase(filename))
            .map(a -> Bukkit.getMap(a.getValue())).flatMap(a -> a.getRenderers().stream())
            .filter(ImageMapRenderer.class::isInstance).forEach(a -> ((ImageMapRenderer) a).recalculateInput(image));
        return true;
    }

    @SuppressWarnings("deprecation")
    private ItemStack getMapItem(BufferedImage image, int x, int y, PlacementData data) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);

        ImageMap imageMap = new ImageMap(data.getFilename(), x, y, getScale(image, data.getSize()));
        synchronized (mapCreationLock) {
            Integer existingId = maps.get(imageMap);
            if (existingId != null) {
                MapMeta meta = (MapMeta) item.getItemMeta();
                meta.setMapId(existingId);
                item.setItemMeta(meta);
                return item;
            }

            MapView map = getServer().createMap(getServer().getWorlds().get(0));
            map.getRenderers().forEach(map::removeRenderer);
            map.addRenderer(new ImageMapRenderer(this, image, x, y, getScale(image, data.getSize())));
            if (isSetTrackingSupported())
                map.setTrackingPosition(false);

            MapMeta meta = ((MapMeta) item.getItemMeta());
            meta.setMapView(map);
            item.setItemMeta(meta);
            maps.put(imageMap, map.getId());
        }

        return item;
    }

    public Tuple<Integer, Integer> getImageSize(String filename, Tuple<Integer, Integer> size) {
        BufferedImage image = getImage(filename);

        if (image == null)
            return new Tuple<>(0, 0);

        double finalScale = getScale(image, size);
        int finalX = (int) ((MAP_WIDTH - 1 + Math.ceil(image.getWidth() * finalScale)) / MAP_WIDTH);
        int finalY = (int) ((MAP_HEIGHT - 1 + Math.ceil(image.getHeight() * finalScale)) / MAP_HEIGHT);

        return new Tuple<>(finalX, finalY);
    }

    public double getScale(String filename, Tuple<Integer, Integer> size) {
        return getScale(getImage(filename), size);
    }

    public double getScale(BufferedImage image, Tuple<Integer, Integer> size) {
        if (image == null)
            return 1.0;

        int baseX = image.getWidth();
        int baseY = image.getHeight();

        double finalScale = 1D;

        if (size != null) {
            int targetX = size.getKey() * MAP_WIDTH;
            int targetY = size.getValue() * MAP_HEIGHT;

            double scaleX = size.getKey() > 0 ? (double) targetX / baseX : Double.MAX_VALUE;
            double scaleY = size.getValue() > 0 ? (double) targetY / baseY : Double.MAX_VALUE;

            finalScale = Math.min(scaleX, scaleY);
            if (finalScale >= Double.MAX_VALUE)
                finalScale = 1D;
        }

        return finalScale;
    }

    private static Rotation facingToRotation(BlockFace heightDirection, BlockFace widthDirection) {
        switch (heightDirection) {
            case WEST:
                return Rotation.CLOCKWISE_45;
            case NORTH:
                return widthDirection == BlockFace.WEST ? Rotation.CLOCKWISE : Rotation.NONE;
            case EAST:
                return Rotation.CLOCKWISE_135;
            case SOUTH:
                return widthDirection == BlockFace.WEST ? Rotation.CLOCKWISE : Rotation.NONE;
            default:
                return Rotation.NONE;
        }
    }

    private static BlockFace calculateWidthDirection(Player player, BlockFace face) {
        float yaw = (360.0f + player.getLocation().getYaw()) % 360.0f;
        switch (face) {
            case NORTH:
                return BlockFace.WEST;
            case SOUTH:
                return BlockFace.EAST;
            case EAST:
                return BlockFace.NORTH;
            case WEST:
                return BlockFace.SOUTH;
            case UP:
            case DOWN:
                if (Utils.isBetween(yaw, 45.0, 135.0))
                    return BlockFace.NORTH;
                else if (Utils.isBetween(yaw, 135.0, 225.0))
                    return BlockFace.EAST;
                else if (Utils.isBetween(yaw, 225.0, 315.0))
                    return BlockFace.SOUTH;
                else
                    return BlockFace.WEST;
            default:
                return null;
        }
    }

    private static BlockFace calculateHeightDirection(Player player, BlockFace face) {
        float yaw = (360.0f + player.getLocation().getYaw()) % 360.0f;
        switch (face) {
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
                return BlockFace.DOWN;
            case UP:
                if (Utils.isBetween(yaw, 45.0, 135.0))
                    return BlockFace.EAST;
                else if (Utils.isBetween(yaw, 135.0, 225.0))
                    return BlockFace.SOUTH;
                else if (Utils.isBetween(yaw, 225.0, 315.0))
                    return BlockFace.WEST;
                else
                    return BlockFace.NORTH;
            case DOWN:
                if (Utils.isBetween(yaw, 45.0, 135.0))
                    return BlockFace.WEST;
                else if (Utils.isBetween(yaw, 135.0, 225.0))
                    return BlockFace.NORTH;
                else if (Utils.isBetween(yaw, 225.0, 315.0))
                    return BlockFace.EAST;
                else
                    return BlockFace.SOUTH;
            default:
                return null;
        }
    }

    private static boolean isAxisAligned(BlockFace face) {
        switch (face) {
            case DOWN:
            case UP:
            case WEST:
            case EAST:
            case SOUTH:
            case NORTH:
                return true;
            default:
                return false;
        }
    }
}
