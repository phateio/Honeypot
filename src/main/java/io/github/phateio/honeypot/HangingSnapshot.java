package io.github.phateio.honeypot;

import java.util.ArrayList;
import java.util.List;

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

    /** How far a hanging's position sits from its block centre, toward its support. */
    private static final double WALL_OFFSET = 0.46875;
    /** Widest and tallest vanilla art, so nothing reaches further than this. */
    public static final int MAX_SPAN = 4;

    /**
     * The block a hanging is anchored to — {@code block_pos} in its NBT.
     *
     * <p>A hanging's position is not its block centre. It sits {@value
     * #WALL_OFFSET} toward the block it hangs on, and a painting whose width or
     * height is even is shifted a further half block along the wall and in Y.
     * Flooring the location therefore lands one block off for most vanilla arts,
     * so the offsets are undone here instead. Measured against Paper 26.2 for all
     * six item-frame facings and for 1x1 / 2x1 / 1x2 / 2x2 / 4x2 / 4x3 paintings
     * on both horizontal axes.
     */
    public static BlockPos blockPosOf(Hanging hanging) {
        Location location = hanging.getLocation();
        BlockFace facing = hanging.getFacing();
        BlockFace across = counterClockWise(facing);
        double alongWall = evenOffset(widthOf(hanging));
        double x = location.getX() + facing.getModX() * WALL_OFFSET - alongWall * across.getModX();
        double y = location.getY() + facing.getModY() * WALL_OFFSET - evenOffset(heightOf(hanging));
        double z = location.getZ() + facing.getModZ() * WALL_OFFSET - alongWall * across.getModZ();
        return new BlockPos(location.getWorld().getName(),
                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
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
     * Every block this hanging occupies. A honeypot covers the hanging when any
     * of them is marked, so a painting is caught wherever it happens to be
     * marked rather than only on its anchor block.
     */
    public List<BlockPos> covered() {
        int width = blockWidth();
        int height = blockHeight();
        if (width == 1 && height == 1) {
            return List.of(pos);
        }
        // A run of `size` blocks centred on the anchor the way vanilla centres
        // the art: an even size extends one further in the positive direction.
        BlockFace across = counterClockWise(facing);
        int firstAcross = -((width - 1) / 2);
        int firstUp = -((height - 1) / 2);
        List<BlockPos> out = new ArrayList<>(width * height);
        for (int i = 0; i < width; i++) {
            int step = firstAcross + i;
            for (int j = 0; j < height; j++) {
                out.add(new BlockPos(pos.world(),
                        pos.x() + across.getModX() * step,
                        pos.y() + firstUp + j,
                        pos.z() + across.getModZ() * step));
            }
        }
        return out;
    }

    /** True when this hanging rests on {@code block}, so a break there drops it. */
    public boolean hangsOn(BlockPos block) {
        BlockFace support = facing.getOppositeFace();
        for (BlockPos covered : covered()) {
            if (covered.relative(support).equals(block)) {
                return true;
            }
        }
        return false;
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
        // getNearbyEntities walks loaded chunks only and never loads one, so an
        // unloaded chunk would hide a hanging that is still there and spawn a
        // duplicate on top of it. The offline sweep reaches exactly that state:
        // stolen item, no block records to pull the chunk in, nobody nearby.
        for (BlockPos covered : covered()) {
            world.getChunkAt(covered.x() >> 4, covered.z() >> 4);
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
     * taken, and a rollback must not stack a second one on top of it. A large
     * painting's position sits well away from its anchor, so the search covers
     * the largest art and every candidate is matched on its own anchor.
     */
    private Hanging findExisting(World world, Location location) {
        for (Entity entity : world.getNearbyEntities(location, MAX_SPAN, MAX_SPAN, MAX_SPAN)) {
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
        // Paper does not refuse to place a hanging that has nothing behind it:
        // since SPIGOT-6387 it falls back to a default face and leaves it in
        // midair, and vanilla then discards it a few seconds later and spills
        // whatever it held. Check the support up front so a rollback reports the
        // failure instead of logging a success the world quietly undoes.
        BlockFace support = facing.getOppositeFace();
        for (BlockPos covered : covered()) {
            BlockPos behind = covered.relative(support);
            if (!world.getBlockAt(behind.x(), behind.y(), behind.z()).getType().isSolid()) {
                return null;
            }
        }
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
            // Only ever put something back, never clear: rollback is per player,
            // so an empty snapshot must not wipe an item that another player's
            // rollback already restored into this same frame. Rotation goes with
            // the item — vanilla resets it when the item comes out, so applying
            // it from an empty snapshot would leave a restored map facing the
            // wrong way.
            if (item != null) {
                frame.setItem(item, false);
                if (rotation != null) {
                    frame.setRotation(rotation);
                }
            }
            frame.setVisible(visible);
            frame.setFixed(fixed);
        } else if (hanging instanceof Painting painting && art != null) {
            painting.setArt(art, true);
        }
    }

    private int blockWidth() {
        return art == null ? 1 : art.getBlockWidth();
    }

    private int blockHeight() {
        return art == null ? 1 : art.getBlockHeight();
    }

    private static int widthOf(Hanging hanging) {
        return hanging instanceof Painting painting ? painting.getArt().getBlockWidth() : 1;
    }

    private static int heightOf(Hanging hanging) {
        return hanging instanceof Painting painting ? painting.getArt().getBlockHeight() : 1;
    }

    private static double evenOffset(int size) {
        return size % 2 == 0 ? 0.5 : 0.0;
    }

    /** Matches vanilla {@code Direction#getCounterClockWise()} for the horizontal faces. */
    private static BlockFace counterClockWise(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            // UP/DOWN carry item frames only, which are 1x1, so the offset is zero.
            default -> BlockFace.SELF;
        };
    }
}
