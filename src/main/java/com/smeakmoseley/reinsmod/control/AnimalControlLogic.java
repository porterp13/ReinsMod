package com.smeakmoseley.reinsmod.control;

import com.smeakmoseley.reinsmod.network.AnimalControlInputPacket;
import net.minecraft.server.level.ServerPlayer;

public class AnimalControlLogic {

    public static void handleInput(ServerPlayer player, AnimalControlInputPacket msg) {
        ServerControlState.update(
                player.getUUID(),
                msg.forward,
                msg.strafe,
                msg.yaw,
                msg.sprint,
                msg.jump
        );
    }
}
