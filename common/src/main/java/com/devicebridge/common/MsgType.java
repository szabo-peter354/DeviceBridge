package com.devicebridge.common;

/** Üzenettípusok a titkosított csatornán belül. */
public final class MsgType {
    private MsgType() {}

    // Kézfogás
    public static final int HS_AUTH = 1;
    public static final int HS_TRUST = 2;
    public static final int HS_PAIR_DECISION = 3;

    // Munkamenet
    public static final int PING = 10;
    public static final int PONG = 11;
    public static final int BYE = 12;

    // Vezérlés
    public static final int CTRL_STATUS = 100;
    public static final int CTRL_CLIPBOARD = 101;
    public static final int CTRL_OPEN_URL = 102;
    public static final int CTRL_RING = 103;
    public static final int CTRL_LOCK = 104;
    public static final int CTRL_MEDIA = 105;
    public static final int CTRL_NOTIFY = 106;
    public static final int CTRL_SSH_INFO = 107;

    // Fájlátvitel
    public static final int FILE_OFFER = 200;
    public static final int FILE_ACCEPT = 201;
    public static final int FILE_REJECT = 202;
    public static final int FILE_CHUNK = 203;
    public static final int FILE_END = 204;
    public static final int FILE_CANCEL = 205;
    public static final int FILE_DONE = 206;

    // Távoli asztal
    public static final int RD_START = 300;
    public static final int RD_STOP = 301;
    public static final int RD_STOPPED = 302;
    public static final int RD_STATUS = 303;
    public static final int RD_FRAME_INFO = 304;
    public static final int RD_TILE = 305;
    public static final int RD_FRAME_END = 306;
    public static final int RD_ACK = 307;
    public static final int RD_MOUSE_MOVE = 310;
    public static final int RD_MOUSE_BUTTON = 311;
    public static final int RD_WHEEL = 312;
    public static final int RD_KEY = 313;
    public static final int RD_TEXT = 314;
    public static final int RD_ACTION = 315;
}
