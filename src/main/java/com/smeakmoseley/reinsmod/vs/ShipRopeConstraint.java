package com.smeakmoseley.reinsmod.vs;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.phys.Vec3;

public final class ShipRopeConstraint {

    private static final double ROPE_LEN = 2.5;
    private static final double EPS = 1.0e-4;

    private static final double MAX_REASONABLE_DIST = 256.0;

    private ShipRopeConstraint() {}

    /**
     * Rigid rein: clamp the animal's move so that after moving, horizontal distance to anchorWorld <= ROPE_LEN.
     *
     * @param fencePos       the fence block that owns the knot (selects correct knot)
     * @param anchorShipyard cached anchor in SHIPYARD space (ship-managed space)
     */
    public static Vec3 applyRigid(ServerLevel level, Animal animal, BlockPos fencePos, Vec3 anchorShipyard, Vec3 desiredMove) {
        if (level == null || animal == null || fencePos == null || anchorShipyard == null || desiredMove == null) return desiredMove;

        // Find the knot for THIS fence pos (avoid “wrong knot” when multiple exist)
        LeashFenceKnotEntity knot = level.getEntitiesOfClass(
                LeashFenceKnotEntity.class,
                animal.getBoundingBox().inflate(64)
        ).stream()
                .filter(k -> k.blockPosition().equals(fencePos))
                .findFirst()
                .orElse(null);

        if (knot == null) return desiredMove;

        // Resolve ship using the knot's position (ship-managed space)
        Object ship0 = VsShipAccess.getShipManagingPos(level, knot.position()).orElse(null);
        if (ship0 == null) return desiredMove;

        // Convert SHIPYARD anchor -> WORLD anchor
        Vec3 anchorWorld = VsShipTransforms.shipyardToWorld(ship0, anchorShipyard);
        if (anchorWorld == null) return desiredMove;

        Vec3 pos0 = animal.position();
        Vec3 pos1 = pos0.add(desiredMove);

        // Horizontal constraint
        Vec3 d = pos1.subtract(anchorWorld);
        d = new Vec3(d.x, 0.0, d.z);

        double dist = d.length();
        if (dist < EPS) return desiredMove;
        if (dist > MAX_REASONABLE_DIST) return desiredMove; // sanity
        if (dist <= ROPE_LEN + EPS) return desiredMove;

        Vec3 dir = d.scale(1.0 / dist);

        Vec3 clampedPos1 = new Vec3(
                anchorWorld.x + dir.x * ROPE_LEN,
                pos1.y,
                anchorWorld.z + dir.z * ROPE_LEN
        );

        return clampedPos1.subtract(pos0);
    }
}
