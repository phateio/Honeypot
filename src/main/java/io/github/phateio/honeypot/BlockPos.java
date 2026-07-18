package io.github.phateio.honeypot;

import org.bukkit.block.Block;

/**
 * A block position keyed by world <em>name</em> rather than a live World
 * reference, so honeypot data for worlds that are not currently loaded
 * survives load/save cycles.
 */
public record BlockPos(String world, int x, int y, int z) {

    public static BlockPos of(Block block) {
        return new BlockPos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /** Serialized form used in honeypots.yml: {@code world,x,y,z}. */
    public String serialize() {
        return world + "," + x + "," + y + "," + z;
    }

    /** @throws IllegalArgumentException if {@code line} is not {@code world,x,y,z} */
    public static BlockPos deserialize(String line) {
        String[] parts = line.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("expected world,x,y,z: " + line);
        }
        return new BlockPos(parts[0], Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }
}
