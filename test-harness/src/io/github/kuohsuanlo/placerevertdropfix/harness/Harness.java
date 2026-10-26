package io.github.kuohsuanlo.placerevertdropfix.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Reproduces the cancelled-placement dupe with a fake player going through the real server path
 * (ServerPlayerGameMode#useItemOn: interact event, ItemStack#useOn capture, place event, revert).
 *
 * <p>A listener here plays the protection plugin: while {@link #protect} is set it cancels every
 * BlockPlaceEvent of the fake player, exactly what a claim plugin does to someone without build rights.
 * Results go to plugins/PrdfHarness/result.txt, then the server stops.
 */
public final class Harness extends JavaPlugin implements Listener {

    private static final String ALT = "PRDF_Alt";
    private static final int Y = 100;
    private static final int Z = 0;

    private volatile boolean protect;
    private volatile boolean uncancel;
    private final List<String> out = new ArrayList<>();
    private final boolean folia = classExists("io.papermc.paper.threadedregions.RegionizedServer");
    private World world;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        log("server=" + Bukkit.getName() + " " + Bukkit.getVersion() + " folia=" + folia
                + " PlaceRevertDropFix=" + (Bukkit.getPluginManager().getPlugin("PlaceRevertDropFix") != null));
        Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> start(), 100);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent event) {
        if (protect && event.getPlayer().getName().equals(ALT)) event.setCancelled(true);
    }

    // A badly behaved plugin: un-cancels at MONITOR, after PlaceRevertDropFix (we load after it).
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlaceMonitor(BlockPlaceEvent event) {
        if (uncancel && event.getPlayer().getName().equals(ALT)) event.setCancelled(false);
    }

    // ------------------------------------------------------------------ scenarios

    private interface Scenario { void run(int cx); }

    private void start() {
        world = Bukkit.getWorlds().get(0);
        List<Object[]> list = List.of(
                new Object[] {"A portal, not protected (vanilla behaviour)", (Scenario) cx -> portal(cx, false, false)},
                new Object[] {"B portal, protected (the dupe)", (Scenario) cx -> portal(cx, true, false)},
                new Object[] {"C portal, protected, unrelated drops+xp already on the ground", (Scenario) cx -> portal(cx, true, true)},
                new Object[] {"D plain block place, protected, unrelated drops nearby", (Scenario) this::plainPlace},
                new Object[] {"E synthetic cancelled BlockPlaceEvent right after a no-op click", (Scenario) this::stale},
                new Object[] {"F portal, protected, orbs already lying inside the ore cell (merge by count++)", (Scenario) this::orbAbsorb},
                new Object[] {"G hoe on rooted dirt, protected", (Scenario) cx -> hoe(cx, false)},
                new Object[] {"H hoe on rooted dirt, protected, then another plugin un-cancels at MONITOR", (Scenario) cx -> hoe(cx, true)});
        for (int i = 0; i < list.size(); i++) {
            String name = (String) list.get(i)[0];
            Scenario sc = (Scenario) list.get(i)[1];
            int cx = 1000 + i * 48;
            long delay = 1 + i * 80L;
            Location at = new Location(world, cx, Y, Z);
            Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> world.getChunkAtAsync(at, true).thenAccept(chunk ->
                    Bukkit.getRegionScheduler().run(this, at, task -> {
                        chunk.addPluginChunkTicket(this);
                        log("");
                        log("== " + name);
                        try { sc.run(cx); } catch (Throwable e) { log("  ERROR " + e); e.printStackTrace(); }
                    })), delay);
        }
        Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> finish(), 1 + list.size() * 80L + 60);
    }

    /** 12-frame portal around (cx, Y, Z); the alt inserts the last eye on the south-middle frame. */
    private void portal(int cx, boolean protectedClaim, boolean unrelated) {
        floor(cx);
        int cz = Z;
        for (int d = -1; d <= 1; d++) {
            frame(cx + d, cz - 2, BlockFace.SOUTH, true);
            frame(cx + d, cz + 2, BlockFace.NORTH, d != 0);
            frame(cx - 2, cz + d, BlockFace.EAST, true);
            frame(cx + 2, cz + d, BlockFace.WEST, true);
        }
        Block chest = world.getBlockAt(cx, Y, cz);
        chest.setType(Material.CHEST);
        ((Container) chest.getState()).getInventory().addItem(new ItemStack(Material.NETHERITE_INGOT, 64));
        world.getBlockAt(cx - 1, Y, cz - 1).setType(Material.DIAMOND_BLOCK);
        world.getBlockAt(cx + 1, Y, cz - 1).setType(Material.DIAMOND_ORE);   // drops a diamond + xp
        Block shulker = world.getBlockAt(cx + 1, Y, cz + 1);
        shulker.setType(Material.SHULKER_BOX);
        ((Container) shulker.getState()).getInventory().addItem(new ItemStack(Material.DIAMOND, 64));

        if (unrelated) {
            world.dropItem(new Location(world, cx + 0.5, Y + 0.2, cz + 4.5), new ItemStack(Material.COBBLESTONE, 5));
            world.spawn(new Location(world, cx + 1.5, Y + 0.2, cz + 0.5), ExperienceOrb.class, o -> o.setExperience(1000));
        }
        Map<String, Integer> before = ground(cx);
        log("  ground before: " + before);

        protect = protectedClaim;
        InteractionResult r = use(cx + 0.5, Y + 1, cz + 3.5, new BlockPos(cx, Y, cz + 2), Material.ENDER_EYE);
        protect = false;

        Map<String, Integer> now = ground(cx);
        EndPortalFrame clicked = (EndPortalFrame) world.getBlockAt(cx, Y, cz + 2).getBlockData();
        log("  useItemOn=" + r + "  eyeInserted=" + clicked.hasEye()
                + "  center=" + chest.getType() + " " + contents(chest)
                + "  shulker=" + shulker.getType() + " " + contents(shulker)
                + "  diamondBlock=" + world.getBlockAt(cx - 1, Y, cz - 1).getType()
                + "  ore=" + world.getBlockAt(cx + 1, Y, cz - 1).getType());
        log("  ground after:  " + now);
        Map<String, Integer> created = diff(now, before);
        log("  created by the click: " + created);
        if (protectedClaim) {
            boolean restored = chest.getType() == Material.CHEST && contents(chest).contains("NETHERITE_INGOT=64");
            boolean dupe = restored && !created.isEmpty();
            log("  VERDICT " + (dupe ? "DUPED (blocks restored AND drops on the ground)"
                    : restored && created.isEmpty() ? "CLEAN (blocks restored, no drops)" : "UNEXPECTED"));
            if (unrelated) {
                log("  unrelated cobblestone kept=" + (now.getOrDefault("COBBLESTONE", 0) == 5)
                        + "  unrelated xp kept=" + (now.getOrDefault("XP", 0) >= 1000));
            }
        } else {
            log("  VERDICT " + (chest.getType() == Material.END_PORTAL && created.getOrDefault("NETHERITE_INGOT", 0) == 64
                    ? "VANILLA (portal opened, contents dropped once)" : "UNEXPECTED"));
        }
    }

    /** Alt places a stone block, protection cancels it; drops lying next to it must stay. */
    private void plainPlace(int cx) {
        floor(cx);
        world.dropItem(new Location(world, cx + 0.5, Y + 0.2, Z + 0.5), new ItemStack(Material.GOLD_INGOT, 7));
        Map<String, Integer> before = ground(cx);
        protect = true;
        InteractionResult r = use(cx + 0.5, Y + 1, Z + 2.5, new BlockPos(cx, Y - 1, Z + 1), Material.STONE);
        protect = false;
        Map<String, Integer> now = ground(cx);
        log("  useItemOn=" + r + "  placed=" + world.getBlockAt(cx, Y, Z + 1).getType());
        log("  ground before: " + before + "  after: " + now);
        log("  VERDICT " + (now.equals(before) ? "CLEAN (nothing touched)" : "UNEXPECTED"));
    }

    /** A click that places nothing leaves its window open; a later synthetic cancelled place event elsewhere must not delete drops. */
    private void stale(int cx) {
        floor(cx);
        ServerPlayer fake = fake(cx + 0.5, Y + 1, Z + 2.5, Material.STICK);
        BlockPos stone = new BlockPos(cx, Y - 1, Z);
        InteractionResult r = fake.gameMode.useItemOn(fake, level(), fake.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(stone).add(0, 0.5, 0), Direction.UP, stone, false));
        Item dropped = world.dropItem(new Location(world, cx + 3.5, Y + 0.2, Z + 0.5), new ItemStack(Material.EMERALD, 9));
        Block target = world.getBlockAt(cx - 3, Y, Z);
        BlockPlaceEvent synthetic = new BlockPlaceEvent(target, target.getState(), world.getBlockAt(cx - 3, Y - 1, Z),
                new ItemStack(Material.STONE), fake.getBukkitEntity(), true, EquipmentSlot.HAND);
        protect = true;
        Bukkit.getPluginManager().callEvent(synthetic);
        protect = false;
        log("  stick click=" + r + "  synthetic cancelled=" + synthetic.isCancelled() + "  emerald still there=" + dropped.isValid());
        log("  VERDICT " + (dropped.isValid() ? "CLEAN (unrelated drop kept)" : "UNEXPECTED (unrelated drop removed)"));
    }


    /** Orbs already lying in a block the eye destroys: vanilla may add the new xp to them without any event. */
    private void orbAbsorb(int cx) {
        floor(cx);
        int cz = Z;
        for (int d = -1; d <= 1; d++) {
            frame(cx + d, cz - 2, BlockFace.SOUTH, true);
            frame(cx + d, cz + 2, BlockFace.NORTH, d != 0);
            frame(cx - 2, cz + d, BlockFace.EAST, true);
            frame(cx + 2, cz + d, BlockFace.WEST, true);
        }
        Block ore = world.getBlockAt(cx + 1, Y, cz - 1);
        ore.setType(Material.DIAMOND_ORE);
        Location in = ore.getLocation().add(0.5, 0.5, 0.5);
        for (int v : new int[] {1, 3, 7}) world.spawn(in, ExperienceOrb.class, o -> o.setExperience(v));
        Map<String, Integer> before = ground(cx);
        protect = true;
        InteractionResult r = use(cx + 0.5, Y + 1, cz + 3.5, new BlockPos(cx, Y, cz + 2), Material.ENDER_EYE);
        protect = false;
        Map<String, Integer> now = ground(cx);
        log("  useItemOn=" + r + "  ore=" + ore.getType() + "  ground before: " + before + "  after: " + now);
        log("  VERDICT " + (ore.getType() == Material.DIAMOND_ORE && now.equals(before) ? "CLEAN (pre-existing orbs unchanged, no new drops)"
                : ore.getType() == Material.DIAMOND_ORE ? "DUPED (ore restored, xp/drops gained)" : "UNEXPECTED"));
    }

    /** Hoe on rooted dirt pops hanging roots below it; with uncancel a later MONITOR listener lets it through. */
    private void hoe(int cx, boolean lateUncancel) {
        floor(cx);
        Block dirt = world.getBlockAt(cx, Y + 2, Z);
        dirt.setType(Material.ROOTED_DIRT);
        Map<String, Integer> before = ground(cx);
        protect = true;
        uncancel = lateUncancel;
        InteractionResult r = use(cx + 0.5, Y, Z + 1.5, new BlockPos(cx, Y + 2, Z), Material.IRON_HOE);
        protect = false;
        uncancel = false;
        Map<String, Integer> now = ground(cx);
        log("  useItemOn=" + r + "  block=" + dirt.getType() + "  ground before: " + before + "  right after: " + now);
        Location at = dirt.getLocation();
        Bukkit.getRegionScheduler().runDelayed(this, at, t -> {
            Map<String, Integer> later = ground(cx);
            log("  " + name(lateUncancel) + " 5 ticks later: block=" + dirt.getType() + "  ground: " + later);
            if (!lateUncancel) {
                log("  VERDICT " + (dirt.getType() == Material.ROOTED_DIRT && later.isEmpty() ? "CLEAN (dirt restored, no roots)"
                        : dirt.getType() == Material.ROOTED_DIRT ? "DUPED (dirt restored, roots on the ground)" : "UNEXPECTED"));
            } else {
                log("  VERDICT " + (dirt.getType() == Material.DIRT && later.getOrDefault("HANGING_ROOTS", 0) == 1
                        ? "RESTORED (placement went through, its drop was given back)" : "UNEXPECTED"));
            }
        }, 5);
    }

    private static String name(boolean b) { return b ? "[uncancelled]" : "[cancelled]"; }

    // ------------------------------------------------------------------ helpers

    private InteractionResult use(double px, double py, double pz, BlockPos clicked, Material inHand) {
        ServerPlayer fake = fake(px, py, pz, inHand);
        return fake.gameMode.useItemOn(fake, level(), fake.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(clicked).add(0, 0.5, 0), Direction.UP, clicked, false));
    }

    private ServerPlayer fake(double x, double y, double z, Material inHand) {
        ServerLevel level = level();
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + ALT).getBytes(StandardCharsets.UTF_8)), ALT);
        ServerPlayer p = new ServerPlayer(MinecraftServer.getServer(), level, profile, ClientInformation.createDefault());
        p.setPos(x, y, z);
        p.getBukkitEntity().getInventory().setItemInMainHand(new ItemStack(inHand));
        return p;
    }

    private ServerLevel level() { return ((CraftWorld) world).getHandle(); }

    private void floor(int cx) {
        for (int x = cx - 6; x <= cx + 6; x++) for (int z = Z - 6; z <= Z + 6; z++) {
            world.getBlockAt(x, Y - 1, z).setType(Material.STONE);
            for (int y = Y; y <= Y + 3; y++) world.getBlockAt(x, y, z).setType(Material.AIR);
        }
    }

    private void frame(int x, int z, BlockFace facing, boolean eye) {
        Block b = world.getBlockAt(x, Y, z);
        b.setType(Material.END_PORTAL_FRAME);
        EndPortalFrame data = (EndPortalFrame) b.getBlockData();
        data.setFacing(facing);
        data.setEye(eye);
        b.setBlockData(data);
    }

    private Map<String, Integer> ground(int cx) {
        Map<String, Integer> m = new TreeMap<>();
        for (Entity e : world.getNearbyEntities(new Location(world, cx + 0.5, Y, Z + 0.5), 8, 6, 8)) {
            if (e instanceof Item i) m.merge(i.getItemStack().getType().name(), i.getItemStack().getAmount(), Integer::sum);
            else if (e instanceof ExperienceOrb o) m.merge("XP", o.getExperience() * o.getCount(), Integer::sum);
        }
        return m;
    }

    private static Map<String, Integer> diff(Map<String, Integer> now, Map<String, Integer> before) {
        Map<String, Integer> d = new TreeMap<>();
        now.forEach((k, v) -> { int x = v - before.getOrDefault(k, 0); if (x != 0) d.put(k, x); });
        before.forEach((k, v) -> { if (!now.containsKey(k)) d.put(k, -v); });
        return d;
    }

    private static String contents(Block b) {
        if (!(b.getState() instanceof Container c)) return "-";
        Map<String, Integer> m = new TreeMap<>();
        for (ItemStack s : c.getInventory().getContents()) if (s != null) m.merge(s.getType().name(), s.getAmount(), Integer::sum);
        return m.toString();
    }

    private synchronized void log(String s) {
        out.add(s);
        getLogger().info(s);
    }

    private void finish() {
        try {
            getDataFolder().mkdirs();
            Files.write(getDataFolder().toPath().resolve("result.txt"), out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
        Bukkit.shutdown();
    }

    private static boolean classExists(String name) {
        try { Class.forName(name); return true; } catch (ClassNotFoundException e) { return false; }
    }
}
