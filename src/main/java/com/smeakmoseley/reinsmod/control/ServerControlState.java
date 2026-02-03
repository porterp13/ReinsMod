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
    }

    private static final ConcurrentHashMap<UUID, Control> STATE =
            new ConcurrentHashMap<>();

    public static void update(UUID playerId, float forward, float strafe, float yaw, boolean sprint, boolean jump) {
        Control c = STATE.computeIfAbsent(playerId, id -> new Control());
        c.forward = forward;
        c.strafe = strafe;
        c.yaw = yaw;
        c.sprint = sprint;
        c.jump = jump;
    }

    public static Control get(UUID playerId) {
        return STATE.get(playerId);
    }

    public static void clear(UUID playerId) {
        STATE.remove(playerId);
    }
}
