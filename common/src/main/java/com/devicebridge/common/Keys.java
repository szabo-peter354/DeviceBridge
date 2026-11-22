package com.devicebridge.common;

/**
 * Platformfüggetlen billentyűkódok. Az értékek megegyeznek a java.awt.event.KeyEvent VK_* kódjaival,
 * így a PC oldal közvetlenül a java.awt.Robot-nak adhatja tovább őket.
 */
public final class Keys {
    private Keys() {}

    public static final int BACK_SPACE = 8;
    public static final int TAB = 9;
    public static final int ENTER = 10;
    public static final int SHIFT = 16;
    public static final int CONTROL = 17;
    public static final int ALT = 18;
    public static final int ESCAPE = 27;
    public static final int SPACE = 32;
    public static final int PAGE_UP = 33;
    public static final int PAGE_DOWN = 34;
    public static final int END = 35;
    public static final int HOME = 36;
    public static final int LEFT = 37;
    public static final int UP = 38;
    public static final int RIGHT = 39;
    public static final int DOWN = 40;
    public static final int DELETE = 127;
    public static final int F1 = 112;
    public static final int F12 = 123;
    public static final int INSERT = 155;
    public static final int WINDOWS = 524;
    public static final int CONTEXT_MENU = 525;
}
