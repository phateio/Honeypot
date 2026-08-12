package io.github.phateio.honeypot;

import org.bukkit.Art;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.inventory.ItemStack;

/**
 * Enough of a hanging entity — item frame, glow item frame or painting — to put
 * it back the way it was. Stored as plain data rather than an {@link Entity}
 * reference so a rollback still works after the entity is gone and its chunk
 * has been unloaded.
 *
 * <p>Two different offenses produce a snapshot: taking the item out of a frame
 * (the frame survives, only {@link #item} is lost) and breaking the hanging
 * itself. {@link #restore()} handles both by reusing an existing hanging at the
 * position when there is one and spawning a replacement otherwise.
 */
public record HangingSnapshot(
        BlockPos pos,
        EntityType type,
        BlockFace facing,
        ItemStack item,
        Rotation rotation,
        Art art,
        boolean visible,
        boolean fixed,
        boolean invulnerable) {

    /**
     * The block a hanging occupies. Its entity position is offset 0.46875 toward
     * the block it hangs on, so flooring the location always lands back on the
     * hanging's own block.
     */
    public static BlockPos blockPosOf(Hanging hanging) {
        Location location = hanging.getLocation();
        return new BlockPos(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public static HangingSnapshot of(Hanging hanging) {
        ItemStack item = null;
        Rotation rotation = null;
        Art art = null;
        boolean visible = true;
        boolean fixed = false;
        if (hanging instanceof ItemFrame frame) {
            ItemStack held = frame.getItem();
            item = held.getType().isAir() ? null : held.clone();
            rotation = frame.getRotation();
            visible = frame.isVisible();
            fixed = frame.isFixed();
        } else if (hanging instanceof Painting painting) {
            art = painting.getArt();
        }
        return new HangingSnapshot(blockPosOf(hanging), hanging.getType(), hanging.getFacing(),
                item, rotation, art, visible, fixed, hanging.isInvulnerable());
    }

    /**
     * The material this hanging scores as in {@code offense-point-map}, so a
     * display piece can be weighted differently from the blocks around it.
     */
    public Material material() {
        return switch (type) {
            case GLOW_ITEM_FRAME -> Material.GLOW_ITEM_FRAME;
            case PAINTING -> Material.PAINTING;
            default -> Material.ITEM_FRAME;
        };
    }

    /** Human-readable form for log lines, e.g. {@code GLOW_ITEM_FRAME holding FILLED_MAP}. */
    public String describe() {
        if (item == null) {
            return material().name();
        }
        return material().name() + " holding " + item.getType().name();
    }

    /**
     * Puts the hanging back. Must run <em>after</em> block rollback so the block
     * it hangs on exists again.
     *
     * @return false if the world is gone or the hanging could not be placed
     */
    public boolean restore() {
        World world = Bukkit.getWorld(pos.world());
        if (world == null) {
            return false;
        }
        Location location = new Location(world, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5);
        Hanging hanging = findExisting(world, location);
        if (hanging == null) {
            hanging = spawn(world, location);
            if (hanging == null) {
                return false;
            }
        }
        apply(hanging);
        return true;
    }

    /**
     * The hanging that is still there — the frame survives when only its item was
     * taken, and a rollback must not stack a second frame on top of it.
     */
    private Hanging findExisting(World world, Location location) {
        for (Entity entity : world.getNearbyEntities(location, 0.5, 0.5, 0.5)) {
            if (entity instanceof Hanging hanging
                    && hanging.getType() == type
                    && hanging.getFacing() == facing
                    && blockPosOf(hanging).equals(pos)) {
                return hanging;
            }
        }
        return null;
    }

    private Hanging spawn(World world, Location location) {
        try {
            return switch (type) {
                case ITEM_FRAME -> world.spawn(location, ItemFrame.class, this::prepare);
                case GLOW_ITEM_FRAME -> world.spawn(location, GlowItemFrame.class, this::prepare);
                case PAINTING -> world.spawn(location, Painting.class, this::prepare);
                default -> null;
            };
        } catch (IllegalArgumentException e) {
            // Nothing to hang it on: the supporting block is still missing, or a
            // painting no longer fits. Rollback reports the failure; it must not
            // abort the rest of the restore.
            return null;
        }
    }

    /** Runs before the entity is added to the world, where facing is still settable. */
    private void prepare(Hanging hanging) {
        hanging.setFacingDirection(facing, true);
        apply(hanging);
    }

    private void apply(Hanging hanging) {
        hanging.setInvulnerable(invulnerable);
        if (hanging instanceof ItemFrame frame) {
            frame.setItem(item, false);
            if (rotation != null) {
                frame.setRotation(rotation);
            }
            frame.setVisible(visible);
            frame.setFixed(fixed);
        } else if (hanging instanceof Painting painting && art != null) {
            painting.setArt(art, true);
        }
    }
}
