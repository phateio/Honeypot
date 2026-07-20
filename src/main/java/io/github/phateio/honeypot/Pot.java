package io.github.phateio.honeypot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A named honeypot: any mix of individually marked blocks and cuboid regions.
 * The name is stored as data (it is the key in honeypots.yml), so it survives
 * every save and shows up in listings — no YAML comments needed.
 */
public record Pot(String name, Set<BlockPos> blocks, List<Region> regions) {

    public Pot(String name) {
        this(name, new HashSet<>(), new ArrayList<>());
    }

    public boolean isEmpty() {
        return blocks.isEmpty() && regions.isEmpty();
    }
}
