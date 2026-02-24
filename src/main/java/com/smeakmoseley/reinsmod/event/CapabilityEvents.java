package com.smeakmoseley.reinsmod.event;

import com.smeakmoseley.reinsmod.ReinsMod;
import com.smeakmoseley.reinsmod.capability.controller.AnimalControllerProvider;
import com.smeakmoseley.reinsmod.capability.reined.ReinedAnimalProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(
    modid = ReinsMod.MODID,
    bus = Mod.EventBusSubscriber.Bus.FORGE
)
public class CapabilityEvents {

    @SubscribeEvent
    public static void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {

        if (event.getObject() instanceof Animal) {
            event.addCapability(
                    new ResourceLocation(ReinsMod.MODID, "reined_animal"),
                    new ReinedAnimalProvider()
            );
        }

        if (event.getObject() instanceof Player) {
            event.addCapability(
                    new ResourceLocation(ReinsMod.MODID, "animal_controller"),
                    new AnimalControllerProvider()
            );
        }
    }
}
