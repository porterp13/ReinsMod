package com.smeakmoseley.reinsmod.control;

public final class ControlTuning {
    private ControlTuning() {}

    // Animal movement (blocks/tick)
    public static final float WALK_SPEED = 0.10f;

    // Increase this if you want sprint to feel meaningfully faster
    public static final float SPRINT_MULT = 2.10f;  // try 2.0–2.3

    // Ship leash physics "commanded" speed should match animal intent
    public static final double CMD_WALK_SPEED = WALK_SPEED;
    public static final double CMD_SPRINT_MULT = SPRINT_MULT;

    // Give extra room while sprinting so it doesn't feel "crowded"
    public static final double SPRINT_SLACK_BONUS = 0.65; // blocks (tune 0.4–1.2)
}
