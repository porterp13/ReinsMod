package com.smeakmoseley.reinsmod.item;

import com.smeakmoseley.reinsmod.ReinsMod;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ReinsMod.MODID);

    public static final RegistryObject<Item> REINS =
            ITEMS.register("reins",
                    () -> new ReinsItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> WHIP =
            ITEMS.register("whip",
                    () -> new WhipItem(new Item.Properties().stacksTo(1)));

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
