package com.smeakmoseley.reinsmod.capability.reined;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.*;
import net.minecraftforge.common.util.LazyOptional;

public class ReinedAnimalProvider implements ICapabilitySerializable<CompoundTag> {

    public static final Capability<IReinedAnimal> CAPABILITY =
            CapabilityManager.get(new CapabilityToken<>() {});

    private final IReinedAnimal instance = new ReinedAnimal();
    private final LazyOptional<IReinedAnimal> optional = LazyOptional.of(() -> instance);

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
        return cap == CAPABILITY ? optional.cast() : LazyOptional.empty();
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();

        tag.putBoolean("HasReins", instance.hasReins());
        if (instance.getOwner() != null) {
            tag.putUUID("Owner", instance.getOwner());
        }

        tag.putBoolean("ShipLeashed", instance.isLeashedToShip());
        if (instance.getShipFencePos() != null) {
            tag.put("ShipFencePos", NbtUtils.writeBlockPos(instance.getShipFencePos()));
        }
        if (instance.getShipAnchorPos() != null) {
            Vec3 a = instance.getShipAnchorPos();
            tag.putDouble("AnchorX", a.x);
            tag.putDouble("AnchorY", a.y);
            tag.putDouble("AnchorZ", a.z);
        }

        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        instance.setHasReins(tag.getBoolean("HasReins"));
        if (tag.hasUUID("Owner")) {
            instance.setOwner(tag.getUUID("Owner"));
        }

        instance.setLeashedToShip(tag.getBoolean("ShipLeashed"));

        if (tag.contains("ShipFencePos")) {
            instance.setShipFencePos(NbtUtils.readBlockPos(tag.getCompound("ShipFencePos")));
        }

        if (tag.contains("AnchorX")) {
            instance.setShipAnchorPos(
                    new Vec3(
                            tag.getDouble("AnchorX"),
                            tag.getDouble("AnchorY"),
                            tag.getDouble("AnchorZ")
                    )
            );
        }
    }
}
