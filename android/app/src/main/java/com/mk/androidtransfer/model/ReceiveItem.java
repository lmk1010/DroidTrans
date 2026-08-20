package com.mk.androidtransfer.model;

/** 电脑「发到手机」队列里的一个文件。 */
public class ReceiveItem {

    public enum State { PENDING, DOWNLOADING, DONE, FAILED }

    public final String id;
    public final String name;
    public final String rel;
    public final long size;

    public State state = State.PENDING;
    public String savedTo;

    public ReceiveItem(String id, String name, String rel, long size) {
        this.id = id;
        this.name = name;
        this.rel = rel;
        this.size = size;
    }
}
