package com.smeakmoseley.reinsmod.capability.controller;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;

public class AnimalControllerProvider implements ICapabilitySerializable<CompoundTag> {

    public static final Capability<IAnimalController> CAPABILITY =
            CapabilityManager.get(new CapabilityToken<>() {});

    private final IAnimalController instance = new AnimalController();
    private final LazyOptional<IAnimalController> optional =
            LazyOptional.of(() -> instance);

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
        return cap == CAPABILITY ? optional.cast() : LazyOptional.empty();
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Controlling", instance.isControlling());
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        instance.setControlling(tag.getBoolean("Controlling"));
    }
}