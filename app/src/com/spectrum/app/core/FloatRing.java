package com.spectrum.app.core;

/**
 * 单生产者单消费者环形缓冲（float）。采集线程写、分析线程读，
 * 只靠两个 volatile 游标，不加锁、不分配。
 */
public final class FloatRing {

    private final float[] buf;
    private final int mask;
    private volatile int writePos;
    private volatile int readPos;

    public FloatRing(int capacityPow2) {
        if (Integer.bitCount(capacityPow2) != 1) {
            throw new IllegalArgumentException("capacity must be a power of 2");
        }
        this.buf = new float[capacityPow2];
        this.mask = capacityPow2 - 1;
    }

    public int available() {
        return writePos - readPos;
    }

    /** @return 实际写入的帧数（写满就丢，返回小于 len） */
    public int write(float[] src, int len) {
        int space = buf.length - (writePos - readPos);
        int n = Math.min(len, space);
        int w = writePos;
        for (int i = 0; i < n; i++) {
            buf[(w + i) & mask] = src[i];
        }
        writePos = w + n;
        return n;
    }

    /** @return 实际读到的帧数（不足就少给，不阻塞） */
    public int read(float[] dst, int off, int len) {
        int avail = writePos - readPos;
        int n = Math.min(len, avail);
        int r = readPos;
        for (int i = 0; i < n; i++) {
            dst[off + i] = buf[(r + i) & mask];
        }
        readPos = r + n;
        return n;
    }
}
