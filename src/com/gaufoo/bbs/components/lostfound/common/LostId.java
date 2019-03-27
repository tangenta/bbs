package com.gaufoo.bbs.components.lostfound.common;

public class LostId {
    public final String value;

    public LostId(String value) {
        this.value = value;
    }

    public static LostId of(String value) {
        return new LostId(value);
    }
}