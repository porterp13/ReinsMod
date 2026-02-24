package com.smeakmoseley.reinsmod.vs;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

public final class ShipPullPhysics {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Tuning
    private static final double SLACK = 3.5;
    private static final double SPRING = 80.0;
    private static final double DAMPING = 18.0;
    private static final double MAX_FORCE = 2500.0;

    // Safety
    private static final double MAX_REASONABLE_DIST = 64.0;
    private static final double SEARCH_RADIUS = 64.0;

    private ShipPullPhysics() {}

    /**
     * Called every tick to apply a leash-like pull from a fence knot to the ship that owns the knot position.
     *
     * IMPORTANT:
     * - knot.position() is already WORLD SPACE. Do not shipyardToWorld it.
     * - Find the ship using WORLD SPACE (anchorWorld), not some transformed coordinate.
     */
    public static void tickLeashPull(ServerLevel level, Animal animal, BlockPos fencePos) {
        if (level == null || animal == null || fencePos == null) return;

        // Find the leash knot entity at this fence block
        AABB searchBox = animal.getBoundingBox().inflate(SEARCH_RADIUS);
        LeashFenceKnotEntity knot = level.getEntitiesOfClass(LeashFenceKnotEntity.class, searchBox).stream()
                .filter(k -> k.blockPosition().equals(fencePos))
                .findFirst()
                .orElse(null);

        if (knot == null) return;

        // Try registry first (capture on first sight if missing)
        var resolvedOpt = KnotShipAnchorRegistry.resolve((ServerLevel) level, knot, true);
        if (resolvedOpt.isEmpty()) return;

        var resolved = resolvedOpt.get();
        Vec3 anchorWorld = resolved.anchorWorld();

        // Which ship is managing this world position?
        Object ship = resolved.shipOrNull();
        LOGGER.info(
                "[LEASH][LOOKUP] knotPos={} anchorWorld=({}, {}, {}) returnedShip={}",
                knot.blockPosition(), anchorWorld.x, anchorWorld.y, anchorWorld.z,
                ship == null ? "null" : VsShipAccess.debugShipId(ship)
            );
        LOGGER.info("[LEASH][KNOT-SHIP] knot={} ship={}",
                knot.blockPosition(),
                ship == null ? "null" : VsShipAccess.debugShipId(ship)
                );
        if (ship == null) return;

        // Compute pull in world space (horizontal only)
        Vec3 delta = animal.position().subtract(anchorWorld);
        delta = new Vec3(delta.x, 0.0, delta.z);
        
        LOGGER.info("[LEASH][DELTA] knot={} animal={} delta=({}, {}, {}) dist={}",
                    knot.blockPosition(),
                    animal.blockPosition(),
                    delta.x, delta.y, delta.z,
                    delta.length()
                );

        double dist = delta.length();
        if (dist > MAX_REASONABLE_DIST || dist < 1.0e-6) return;
        if (dist <= SLACK) return;

        Vec3 dir = delta.normalize();
        double stretch = dist - SLACK;

        // Damping uses animal velocity along the pull direction
        double relVel = animal.getDeltaMovement().dot(dir);

        // Spring + damping, clamped to positive (pull only)
        double forceMag = (stretch * SPRING) + (relVel * DAMPING);
        forceMag = Math.max(0.0, Math.min(MAX_FORCE, forceMag));

        Vec3 force = dir.scale(forceMag);

        LOGGER.info("[LEASH][FORCE] knot={} ship={} force=({}, {}, {}) mag={}",
                    knot.blockPosition(),
                    VsShipAccess.debugShipId(ship),
                    force.x, force.y, force.z,
                    force.length()
                );

        LOGGER.info("[LEASH][APPLY] knot={} ship={} point=({}, {}, {})",
                knot.blockPosition(),
                VsShipAccess.debugShipId(ship),
                anchorWorld.x, anchorWorld.y, anchorWorld.z
                );

        // Apply at anchor point if your wrapper supports it.
        // If your VsShipForces.applyWorldForce signature doesn't take a point, change to null.
        if (ship != null) {
            VsShipForces.applyWorldForce(ship, force, anchorWorld);
        }
    }
}