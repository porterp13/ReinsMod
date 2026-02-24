package com.smeakmoseley.reinsmod.capability.reined;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public class ReinedAnimal implements IReinedAnimal {

    private boolean hasReins = false;
    private UUID owner = null;

    // Ship leash cache
    private boolean leashedToShip = false;
    private BlockPos shipFencePos = null;
    private Vec3 shipAnchorPos = null;
    private int shipLeashGraceTicks = 0;

    @Override
    public boolean hasReins() {
        return hasReins;
    }

    @Override
    public void setHasReins(boolean value) {
        this.hasReins = value;
    }

    @Override
    public UUID getOwner() {
        return owner;
    }

    @Override
    public void setOwner(UUID uuid) {
        this.owner = uuid;
    }

    @Override
    public boolean isLeashedToShip() {
        return leashedToShip;
    }

    @Override
    public void setLeashedToShip(boolean value) {
        this.leashedToShip = value;
    }

    @Override
    public BlockPos getShipFencePos() {
        return shipFencePos;
    }

    @Override
    public void setShipFencePos(BlockPos pos) {
        this.shipFencePos = pos;
    }

    @Override
    public Vec3 getShipAnchorPos() {
        return shipAnchorPos;
    }

    @Override
    public void setShipAnchorPos(Vec3 pos) {
        this.shipAnchorPos = pos;
    }
}
