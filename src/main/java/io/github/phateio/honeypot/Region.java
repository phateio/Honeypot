package io.github.phateio.honeypot;

/** A cuboid honeypot area, keyed by world name like {@link BlockPos}. */
public record Region(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Region {
        int t;
        if (minX > maxX) { t = minX; minX = maxX; maxX = t; }
        if (minY > maxY) { t = minY; minY = maxY; maxY = t; }
        if (minZ > maxZ) { t = minZ; minZ = maxZ; maxZ = t; }
    }

    public boolean contains(BlockPos pos) {
        return world.equals(pos.world())
                && pos.x() >= minX && pos.x() <= maxX
                && pos.y() >= minY && pos.y() <= maxY
                && pos.z() >= minZ && pos.z() <= maxZ;
    }

    /** Serialized form used in honeypots.yml: {@code world,minX,minY,minZ,maxX,maxY,maxZ}. */
    public String serialize() {
        return world + "," + minX + "," + minY + "," + minZ + "," + maxX + "," + maxY + "," + maxZ;
    }

    /** @throws IllegalArgumentException if {@code line} is not {@code world,minX,minY,minZ,maxX,maxY,maxZ} */
    public static Region deserialize(String line) {
        String[] parts = line.split(",");
        if (parts.length != 7) {
            throw new IllegalArgumentException("expected world,minX,minY,minZ,maxX,maxY,maxZ: " + line);
        }
        return new Region(parts[0],
                Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                Integer.parseInt(parts[4]), Integer.parseInt(parts[5]), Integer.parseInt(parts[6]));
    }
}
