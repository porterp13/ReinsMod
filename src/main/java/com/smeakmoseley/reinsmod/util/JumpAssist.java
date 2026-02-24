package com.smeakmoseley.reinsmod.util;

import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;

public final class JumpAssist {

    private static volatile boolean RESOLVED = false;
    private static volatile Field JUMPING_FIELD = null;

    private JumpAssist() {}

    private static void resolve(LivingEntity e) {
        if (RESOLVED) return;
        RESOLVED = true;

        Class<?> c = e.getClass();
        while (c != null) {
            try {
                JUMPING_FIELD = c.getDeclaredField("jumping");
                JUMPING_FIELD.setAccessible(true);
                return;
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
    }

    public static void setJumping(LivingEntity e, boolean jumping) {
        if (e == null) return;
        resolve(e);
        try {
            if (JUMPING_FIELD != null) {
                JUMPING_FIELD.setBoolean(e, jumping);
            }
        } catch (Throwable ignored) {}
    }
}
