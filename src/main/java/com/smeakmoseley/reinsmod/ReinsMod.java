package com.smeakmoseley.reinsmod;

import com.mojang.logging.LogUtils;
import com.smeakmoseley.reinsmod.event.ShipLeashPhysicsTick;
import com.smeakmoseley.reinsmod.item.ModItems;
import com.smeakmoseley.reinsmod.network.NetworkHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(ReinsMod.MODID)
public class ReinsMod {

    public static final String MODID = "reinsmod";
    private static final Logger LOGGER = LogUtils.getLogger();

    public ReinsMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModItems.register(modEventBus);
        NetworkHandler.init();

        LOGGER.info("ReinsMod loaded");
    }
}
