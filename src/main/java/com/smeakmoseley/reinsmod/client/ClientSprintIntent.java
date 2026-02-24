package com.smeakmoseley.reinsmod.client;

import net.minecraft.client.Minecraft;

/**
 * Tracks "sprint intent" in a way that works for both Hold Sprint and Toggle Sprint.
 * This is separate from player.isSprinting(), which may never become true while movement is locked.
 */
public final class ClientSprintIntent {

    private static boolean toggled = false;

    private ClientSprintIntent() {}

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();

        // Forge 1.20.1: toggleSprint() is an OptionInstance<Boolean>
        boolean toggleSprint = mc.options.toggleSprint().get();

        if (toggleSprint) {
            // Toggle-to-sprint mode: flip when key is pressed
            if (mc.options.keySprint.consumeClick()) {
                toggled = !toggled;
            }
        } else {
            // Hold-to-sprint mode
            toggled = mc.options.keySprint.isDown();
        }
    }

    public static boolean get() {
        return toggled;
    }

    public static void reset() {
        toggled = false;
    }
}
