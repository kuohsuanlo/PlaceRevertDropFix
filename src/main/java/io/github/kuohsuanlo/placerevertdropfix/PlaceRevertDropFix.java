package io.github.kuohsuanlo.placerevertdropfix;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import com.destroystokyo.paper.event.entity.ExperienceOrbMergeEvent;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * Removes the drops that a cancelled item-on-block use leaves behind.
 *
 * <h2>The bug</h2>
 * When a player uses an item on a block, CraftBukkit snapshots every block the item changes, lets
 * vanilla run, then fires {@link BlockPlaceEvent} / {@link BlockMultiPlaceEvent}
 * ({@link BlockFertilizeEvent} for bone meal). If any plugin cancels it, every snapshot is put back,
 * containers included. Vanilla already ran, though, and whatever it dropped while running is still on
 * the ground: the snapshots only cover blocks.
 *
 * <p>The worst case is the last Eye of Ender on an End Portal frame. Vanilla breaks the 3x3 inside the
 * frame with drops before placing the portal; when a protection plugin cancels, the blocks inside come
 * back (a chest with its contents) while what they dropped stays on the floor. It does not matter which
 * plugin cancels (claims, regions, towns...).
 *
 * <h2>The fix</h2>
 * One right-click is one synchronous call on one thread: interact event, vanilla item use, place event,
 * revert. We open a "window" per thread at the interact event and remember every item and experience
 * orb spawned while it is open. When that click's place event arrives cancelled, we remove the ones that
 * were spawned inside a block that is about to be reverted. Blocks back, their drops gone: nothing
 * happened. If the place event is not cancelled we do nothing at all, so normal play stays exactly
 * vanilla. This plugin never sends anything to players.
 *
 * <p>Folia: the whole window lives on the region thread that owns the clicked block, so a
 * {@link ThreadLocal} is enough and needs no locking. No global scheduler is used.
 */
public final class PlaceRevertDropFix extends JavaPlugin implements Listener {

    /** Cheap pre-filter at spawn time; the real test is {@link #insideAny}. */
    private static final double RECORD_RADIUS_SQUARED = 32 * 32;

    /**
     * How far outside a reverted block a drop may appear and still count as "its" drop. Vanilla spawns
     * block drops, container contents and experience inside the block; popping from a face (hoe on
     * rooted dirt) puts the item just past that face.
     */
    private static final double CELL_MARGIN = 0.5;

    /** An item or orb spawned while the window was open. */
    private static final class Spawned {
        final WeakReference<Entity> entity;
        final double x, y, z;

        Spawned(Entity entity, Location at) {
            this.entity = new WeakReference<>(entity);
            this.x = at.getX();
            this.y = at.getY();
            this.z = at.getZ();
        }
    }

    /**
     * An orb that was already lying in a block the click destroyed. Vanilla may add the new experience
     * to it ({@code ExperienceOrb#tryMergeToExisting}: {@code count++}, no event), so remember its count.
     */
    private static final class Absorber {
        final WeakReference<ExperienceOrb> orb;
        final int count;

        Absorber(ExperienceOrb orb) {
            this.orb = new WeakReference<>(orb);
            this.count = orb.getCount();
        }
    }

    private static final class Window {
        final UUID player;
        final String playerName;
        final UUID world;
        final int x, y, z;
        final List<Spawned> spawned = new ArrayList<>(0);
        final List<Absorber> absorbers = new ArrayList<>(0);
        /** Set on the next tick by the player's scheduler (or when the player is gone). */
        volatile boolean closed;

        Window(Player player, Block block) {
            this.player = player.getUniqueId();
            this.playerName = player.getName();
            this.world = block.getWorld().getUID();
            this.x = block.getX();
            this.y = block.getY();
            this.z = block.getZ();
        }

        boolean isClicked(Block b) {
            return b.getX() == x && b.getY() == y && b.getZ() == z && b.getWorld().getUID().equals(world);
        }

        boolean isNear(Location loc) {
            World w = loc.getWorld();
            if (w == null || !w.getUID().equals(world)) return false;
            double dx = loc.getX() - (x + 0.5), dy = loc.getY() - (y + 0.5), dz = loc.getZ() - (z + 0.5);
            return dx * dx + dy * dy + dz * dz <= RECORD_RADIUS_SQUARED;
        }

        boolean holds(Entity entity) {
            for (Spawned s : spawned) if (s.entity.get() == entity) return true;
            return false;
        }

        boolean watches(Entity entity) {
            for (Absorber a : absorbers) if (a.orb.get() == entity) return true;
            return false;
        }
    }

    /** What a rollback took away, so it can be given back if the event ends up not reverted after all. */
    private record Taken(Location at, ItemStack item, int xp, WeakReference<ExperienceOrb> orb, int extraCount) {}

    private final ThreadLocal<Window> current = new ThreadLocal<>();
    private boolean logRollbacks;

    @Override
    public void onEnable() {
        if (!supported()) {
            getLogger().severe("This server lacks the Paper API this plugin needs (Paper/Folia 1.20.6 or newer). "
                    + "The cancelled-placement duplication is NOT fixed here.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        logRollbacks = getConfig().getBoolean("log-rollbacks", true);
        getServer().getPluginManager().registerEvents(this, this);
    }

    private static boolean supported() {
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
            Class.forName("com.destroystokyo.paper.event.block.BlockDestroyEvent");
            Class.forName("com.destroystokyo.paper.event.entity.ExperienceOrbMergeEvent");
            ExperienceOrb.class.getMethod("getCount");
            ExperienceOrb.class.getMethod("setCount", int.class);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private Window live() {
        Window w = current.get();
        if (w != null && w.closed) {
            current.remove();
            return null;
        }
        return w;
    }

    // MONITOR: by now every plugin has decided whether the item may be used at all.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        current.remove();   // any new interaction on this thread ends the previous window
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.useItemInHand() == Event.Result.DENY) return;   // vanilla will not run the item
        ItemStack item = event.getItem();
        Block clicked = event.getClickedBlock();
        if (item == null || item.getType().isAir() || clicked == null) return;

        Window w = new Window(event.getPlayer(), clicked);
        ScheduledTask closer = event.getPlayer().getScheduler().run(this, task -> w.closed = true, () -> w.closed = true);
        if (closer != null) current.set(w);   // null: the player is already gone, nothing will be placed
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        Window w = live();
        if (w == null) return;
        Entity entity = event.getEntity();
        if (!(entity instanceof Item) && !(entity instanceof ExperienceOrb)) return;
        Location at = event.getLocation();
        if (!w.isNear(at)) return;
        if (!w.holds(entity)) w.spawned.add(new Spawned(entity, at));   // orbs fire this event twice
    }

    /** Fires before the block drops anything: note the orbs already lying in it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDestroy(BlockDestroyEvent event) {
        Window w = live();
        if (w == null) return;
        Block block = event.getBlock();
        if (!w.isNear(block.getLocation())) return;
        BoundingBox cell = BoundingBox.of(block).expand(CELL_MARGIN);
        for (Entity e : block.getWorld().getNearbyEntities(cell, e -> e instanceof ExperienceOrb)) {
            if (!w.holds(e) && !w.watches(e)) w.absorbers.add(new Absorber((ExperienceOrb) e));
        }
    }

    /**
     * Spigot's merge-on-spawn can make an orb spawned in the window absorb orbs that were already lying
     * there. Removing it later would then delete experience that has nothing to do with this click.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOrbMerge(ExperienceOrbMergeEvent event) {
        Window w = live();
        if (w == null || w.spawned.isEmpty()) return;
        if (w.holds(event.getMergeTarget()) && !w.holds(event.getMergeSource())) event.setCancelled(true);
    }

    // Also receives BlockMultiPlaceEvent, which shares this handler list.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent event) {
        Window w = live();
        // CraftBukkit passes the clicked block as "placed against"; anything else is not this click.
        if (w == null || !event.getPlayer().getUniqueId().equals(w.player) || !w.isClicked(event.getBlockAgainst())) return;
        current.remove();
        if (!event.isCancelled() && event.canBuild()) return;
        List<BlockState> reverted = event instanceof BlockMultiPlaceEvent multi
                ? multi.getReplacedBlockStates()
                : List.of(event.getBlockReplacedState());
        rollback(w, reverted, event.getEventName(), () -> event.isCancelled() || !event.canBuild());
    }

    // Bone meal goes through this event instead of a place event.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onFertilize(BlockFertilizeEvent event) {
        Window w = live();
        Player player = event.getPlayer();
        if (w == null || player == null || !player.getUniqueId().equals(w.player) || !w.isClicked(event.getBlock())) return;
        current.remove();
        if (event.isCancelled()) rollback(w, event.getBlocks(), event.getEventName(), event::isCancelled);
    }

    private void rollback(Window w, Collection<BlockState> reverted, String cause, BooleanSupplier stillReverted) {
        List<Taken> taken = new ArrayList<>();
        int items = 0, xp = 0;
        for (Spawned s : w.spawned) {
            Entity entity = s.entity.get();
            if (entity == null || !entity.isValid() || !insideAny(s.x, s.y, s.z, reverted)) continue;
            if (entity instanceof Item item) {
                items += item.getItemStack().getAmount();
                taken.add(new Taken(entity.getLocation(), item.getItemStack().clone(), 0, null, 0));
            } else if (entity instanceof ExperienceOrb orb) {
                int amount = orb.getExperience() * orb.getCount();
                xp += amount;
                taken.add(new Taken(entity.getLocation(), null, amount, null, 0));
            }
            remove(entity);
        }
        for (Absorber a : w.absorbers) {
            ExperienceOrb orb = a.orb.get();
            if (orb == null || !orb.isValid()) continue;
            Location at = orb.getLocation();
            int extra = orb.getCount() - a.count;
            if (extra <= 0 || !insideAny(at.getX(), at.getY(), at.getZ(), reverted)) continue;
            orb.setCount(a.count);
            xp += extra * orb.getExperience();
            taken.add(new Taken(at, null, 0, a.orb, extra));
        }
        if (taken.isEmpty()) return;
        if (logRollbacks) {
            getLogger().info(String.format("Removed %d item(s) and %d xp left by a cancelled %s: %s at %s %d,%d,%d",
                    items, xp, cause, w.playerName, worldName(w.world), w.x, w.y, w.z));
        }
        // Every MONITOR listener sees the event before the server reverts the blocks, and in theory one of
        // them could still un-cancel it. Check once the event is over and give everything back if so.
        Location clicked = new Location(Bukkit.getWorld(w.world), w.x, w.y, w.z);
        Bukkit.getRegionScheduler().run(this, clicked, task -> {
            if (stillReverted.getAsBoolean()) return;
            getLogger().warning("A plugin un-cancelled " + cause + " after MONITOR; restoring the drops that were removed.");
            for (Taken t : taken) giveBack(t);
        });
    }

    private void remove(Entity entity) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) entity.remove();
        else entity.getScheduler().run(this, task -> entity.remove(), null);
    }

    private static void giveBack(Taken t) {
        World world = t.at.getWorld();
        if (world == null) return;
        if (t.item != null) {
            world.dropItem(t.at, t.item);
        } else if (t.orb != null) {
            ExperienceOrb orb = t.orb.get();
            if (orb != null && orb.isValid()) orb.setCount(orb.getCount() + t.extraCount);
            else world.spawn(t.at, ExperienceOrb.class, o -> o.setExperience(t.extraCount));
        } else {
            world.spawn(t.at, ExperienceOrb.class, o -> o.setExperience(t.xp));
        }
    }

    private static boolean insideAny(double x, double y, double z, Collection<BlockState> blocks) {
        for (BlockState b : blocks) {
            if (x >= b.getX() - CELL_MARGIN && x <= b.getX() + 1 + CELL_MARGIN
                    && y >= b.getY() - CELL_MARGIN && y <= b.getY() + 1 + CELL_MARGIN
                    && z >= b.getZ() - CELL_MARGIN && z <= b.getZ() + 1 + CELL_MARGIN) return true;
        }
        return false;
    }

    private static String worldName(UUID id) {
        World world = Bukkit.getWorld(id);
        return world == null ? id.toString() : world.getName();
    }
}
