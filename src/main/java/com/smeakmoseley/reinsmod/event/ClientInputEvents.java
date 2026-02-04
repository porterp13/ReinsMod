package com.smeakmoseley.reinsmod.event;

import com.smeakmoseley.reinsmod.client.ClientSprintIntent;
import com.smeakmoseley.reinsmod.item.ModItems;
import com.smeakmoseley.reinsmod.network.AnimalControlInputPacket;
import com.smeakmoseley.reinsmod.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClientInputEvents {

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        boolean holdingWhip = player.getMainHandItem().is(ModItems.WHIP.get());
        if (!holdingWhip) {
            ClientSprintIntent.reset();
            return;
        }

        // Update sprint intent each tick (supports toggle sprint users)
        ClientSprintIntent.tick();

        float forward = 0f;
        if (mc.options.keyUp.isDown()) forward += 1f;
        if (mc.options.keyDown.isDown()) forward -= 1f;

        float strafe = 0f;
        if (mc.options.keyLeft.isDown())  strafe -= 1f;
        if (mc.options.keyRight.isDown()) strafe += 1f;

        float yaw = mc.gameRenderer.getMainCamera().getYRot();

        boolean sprint = ClientSprintIntent.get();
        boolean jump = mc.options.keyJump.isDown();

        NetworkHandler.CHANNEL.sendToServer(
                new AnimalControlInputPacket(forward, strafe, yaw, sprint, jump)
        );
    }
}
