package com.smeakmoseley.reinsmod.vs;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;

/**
 * Immutable data holder for an animal leashed to a VS ship.
 *
 * IMPORTANT:
 *  - anchorPos is treated as SHIPYARD space (the "managed by ship" space).
 *  - Do NOT assume this is world space for ship blocks/entities.
 */
public class ShipLeashInfo {

    public final Animal animal;
    public final BlockPos fencePos;

    /** Leash knot position in SHIPYARD space (ship-managed space) */
    public final Vec3 anchorPos;

    public ShipLeashInfo(Animal animal, BlockPos fencePos, Vec3 anchorPos) {
        this.animal = animal;
        this.fencePos = fencePos.immutable();
        this.anchorPos = anchorPos; // Vec3 immutable
    }
}
