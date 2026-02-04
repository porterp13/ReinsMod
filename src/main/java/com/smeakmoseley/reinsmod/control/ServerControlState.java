package com.smeakmoseley.reinsmod.control;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ServerControlState {

    public static class Control {
        public float forward;
        public float strafe;
        public float yaw;
        public boolean sprint;
        public boolean jump;

        // ✅ when we last received input for this player (server tick)
        public int lastInputTick;
    }

    private static final ConcurrentHashMap<UUID, Control> STATE = new ConcurrentHashMap<>();

    public static void update(UUID playerId,
                              float forward, float strafe, float yaw,
                              boolean sprint, boolean jump,
                              int serverTick) {

        Control c = STATE.computeIfAbsent(playerId, id -> new Control());
        c.forward = forward;
        c.strafe = strafe;
        c.yaw = yaw;
        c.sprint = sprint;
        c.jump = jump;
        c.lastInputTick = serverTick;
    }

    public static Control get(UUID playerId) {
        return STATE.get(playerId);
    }

    /** Consider "controlling" if we saw input recently (prevents 1-tick flicker). */
    public static Control getRecent(UUID playerId, int serverTick, int graceTicks) {
        Control c = STATE.get(playerId);
        if (c == null) return null;
        return (serverTick - c.lastInputTick) <= graceTicks ? c : null;
    }

    public static void clear(UUID playerId) {
        STATE.remove(playerId);
    }
}
