package com.smeakmoseley.reinsmod.event;

import com.smeakmoseley.reinsmod.client.ClientSprintIntent;
import com.smeakmoseley.reinsmod.item.ModItems;
import com.smeakmoseley.reinsmod.network.AnimalControlInputPacket;
import com.smeakmoseley.reinsmod.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;

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

        // ✅ MUST update sprint intent every tick while whip is held
        ClientSprintIntent.tick();

        float forward = 0f;
        if (mc.options.keyUp.isDown()) forward += 1f;
        if (mc.options.keyDown.isDown()) forward -= 1f;

        float strafe = 0f;
        if (mc.options.keyLeft.isDown())  strafe -= 1f;
        if (mc.options.keyRight.isDown()) strafe += 1f;

        boolean sprint = ClientSprintIntent.get();
        boolean jump = mc.options.keyJump.isDown();

        // ============================================================
        // ✅ Seat/VS-proof yaw:
        // derive yaw from the camera's LOOK VECTOR (world-space direction)
        // ============================================================
        float yaw = computeYawFromCameraLook(mc);

        NetworkHandler.CHANNEL.sendToServer(
                new AnimalControlInputPacket(forward, strafe, yaw, sprint, jump)
        );
    }

    /**
     * Computes a stable world-yaw from the camera look direction.
     * Works even when player yaw is clamped/overridden by seats, especially on VS ships.
     */
    private static float computeYawFromCameraLook(Minecraft mc) {
        // Camera look vector is JOML Vector3f in 1.20.1
        Vector3f look = mc.gameRenderer.getMainCamera().getLookVector();

        float lx = look.x();
        float lz = look.z();

        // If looking almost straight up/down, yaw becomes unstable. Fallback to camera yaw.
        float xzLen2 = lx * lx + lz * lz;
        if (xzLen2 < 1.0e-6f) {
            return mc.gameRenderer.getMainCamera().getYRot();
        }

        // Minecraft forward convention: forward = (-sin(yaw), 0, cos(yaw))
        // Invert that: yaw = atan2(-x, z)
        float yaw = (float) Math.toDegrees(Math.atan2(-lx, lz));
        return Mth.wrapDegrees(yaw);
    }
}
