package com.smeakmoseley.reinsmod.capability.reined;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public interface IReinedAnimal {

    boolean hasReins();
    void setHasReins(boolean value);

    UUID getOwner();
    void setOwner(UUID uuid);

    // 🔹 Ship leash state (CACHED)
    boolean isLeashedToShip();
    void setLeashedToShip(boolean value);

    BlockPos getShipFencePos();
    void setShipFencePos(BlockPos pos);

    Vec3 getShipAnchorPos();
    void setShipAnchorPos(Vec3 pos);
}