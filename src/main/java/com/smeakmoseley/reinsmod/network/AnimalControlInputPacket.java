package com.smeakmoseley.reinsmod.network;

import net.minecraft.network.FriendlyByteBuf;

public class AnimalControlInputPacket {

    public final float forward;
    public final float strafe;
    public final float yaw;

    public final boolean sprint;
    public final boolean jump;

    public AnimalControlInputPacket(float forward, float strafe, float yaw, boolean sprint, boolean jump) {
        this.forward = forward;
        this.strafe = strafe;
        this.yaw = yaw;
        this.sprint = sprint;
        this.jump = jump;
    }

    public static void encode(AnimalControlInputPacket msg, FriendlyByteBuf buf) {
        buf.writeFloat(msg.forward);
        buf.writeFloat(msg.strafe);
        buf.writeFloat(msg.yaw);
        buf.writeBoolean(msg.sprint);
        buf.writeBoolean(msg.jump);
    }

    public static AnimalControlInputPacket decode(FriendlyByteBuf buf) {
        return new AnimalControlInputPacket(
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readBoolean(),
                buf.readBoolean()
        );
    }
}
