package com.ids.qtrack.next.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** FFM 기반 파일 쓰기·mmap 읽기 (설계서 5.6: 힙에 올리지 않고 MemorySegment로 접근). */
public final class OffHeap {
    private OffHeap() {}

    public static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    public static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    public static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    public static final MemorySegment EMPTY = MemorySegment.ofArray(new byte[0]);

    /** size 바이트 파일을 만들고 READ_WRITE로 map한 세그먼트를 넘겨 채우게 합니다. */
    public static void write(Path file, long size, java.util.function.Consumer<MemorySegment> filler) {
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel fc = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                 Arena arena = Arena.ofConfined()) {
                if (size == 0) return;
                MemorySegment seg = fc.map(FileChannel.MapMode.READ_WRITE, 0, size, arena);
                filler.accept(seg);
                seg.force();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void writeInts(Path file, int[] a) {
        writeInts(file, a, a.length);
    }

    public static void writeInts(Path file, int[] a, int len) {
        write(file, 4L * len, seg -> {
            for (int i = 0; i < len; i++) seg.setAtIndex(INT, i, a[i]);
        });
    }

    public static void writeLongs(Path file, long[] a) {
        write(file, 8L * a.length, seg -> {
            for (int i = 0; i < a.length; i++) seg.setAtIndex(LONG, i, a[i]);
        });
    }

    public static void writeBytes(Path file, byte[] a) {
        write(file, a.length, seg -> MemorySegment.copy(a, 0, seg, BYTE, 0, a.length));
    }

    /** READ_ONLY mmap. 파일이 없거나 비어 있으면 빈 세그먼트. */
    public static MemorySegment map(Path file, Arena arena) {
        try {
            if (!Files.exists(file) || Files.size(file) == 0) return EMPTY;
            try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ)) {
                return fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size(), arena);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static int getInt(MemorySegment s, long index) {
        return s.getAtIndex(INT, index);
    }

    public static long getLong(MemorySegment s, long index) {
        return s.getAtIndex(LONG, index);
    }

    public static int getU8(MemorySegment s, long index) {
        return s.byteSize() == 0 ? 0 : Byte.toUnsignedInt(s.get(BYTE, index));
    }
}
