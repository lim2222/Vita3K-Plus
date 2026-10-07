package org.vita3k.emulator.overlay;

/**
 * Button type constants for the input overlay.
 * These correspond to the button types used in the overlay system.
 */
public final class ButtonType {
    private ButtonType() {} // Prevent instantiation

    public static final int BUTTON_CROSS = 0;
    public static final int BUTTON_CIRCLE = 1;
    public static final int BUTTON_SQUARE = 2;
    public static final int BUTTON_TRIANGLE = 3;
    public static final int BUTTON_SELECT = 4;
    public static final int BUTTON_START = 5;
    public static final int DPAD_UP = 6;
    public static final int BUTTON_PS = 7;
    public static final int STICK_LEFT = 10;
    public static final int STICK_RIGHT = 15;
    public static final int TRIGGER_L = 20;
    public static final int TRIGGER_R = 21;
    public static final int TRIGGER_L2 = 22;
    public static final int TRIGGER_R2 = 23;
    public static final int TRIGGER_L3 = 24;
    public static final int TRIGGER_R3 = 25;
    public static final int BUTTON_TOUCH_HIDE = 1023;
    public static final int BUTTON_TOUCH_SWITCH = 1024;
    public static final int TATACON = 30;
}