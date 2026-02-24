package com.smeakmoseley.reinsmod.vs;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public class ShipLeashDetection {

    public static Optional<ShipLeashInfo> detectFenceOnShip(Animal animal) {
        if (!(animal.level() instanceof ServerLevel level)) return Optional.empty();

        Entity holder = animal.getLeashHolder();
        if (!(holder instanceof LeashFenceKnotEntity knot)) return Optional.empty();

        // ✅ Use the registry (shipId + ship-local anchor) instead of re-resolving by position
        var resolvedOpt = KnotShipAnchorRegistry.resolve(level, knot, true);
        if (resolvedOpt.isEmpty()) return Optional.empty();

        var rec = resolvedOpt.get().record();
        if (rec.shipId() < 0 || rec.anchorLocal() == null) return Optional.empty();
        return Optional.of(new ShipLeashInfo(animal, knot.blockPosition(), rec.anchorLocal()));
    }
}