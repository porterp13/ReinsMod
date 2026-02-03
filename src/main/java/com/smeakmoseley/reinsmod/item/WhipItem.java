package com.smeakmoseley.reinsmod.item;

import com.smeakmoseley.reinsmod.capability.controller.AnimalControllerProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class WhipItem extends Item {

    public WhipItem(Properties properties) {
        super(properties);
    }

    @Override
    public void inventoryTick(
            ItemStack stack,
            Level level,
            Entity entity,
            int slot,
            boolean selected) {

        if (level.isClientSide) return;
        if (!(entity instanceof Player player)) return;

        player.getCapability(AnimalControllerProvider.CAPABILITY).ifPresent(cap -> {

            // Only react if state actually changes
            if (cap.isControlling() != selected) {
                cap.setControlling(selected);

                if (selected) {
                    player.sendSystemMessage(
                            Component.literal("Whip control: ON")
                    );
                } else {
                    player.sendSystemMessage(
                            Component.literal("Whip control: OFF")
                    );
                }
            }
        });
    }
}
