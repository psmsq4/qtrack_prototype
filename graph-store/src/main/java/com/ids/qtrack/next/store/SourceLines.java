package com.ids.qtrack.next.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * span.i64(fileId &lt;&lt; 32 | 바이트 오프셋, 설계서 5.2)을 사람이 읽는 줄 번호로 바꿉니다.
 * 파일마다 줄 시작 바이트 표를 처음 필요할 때 한 번 만듭니다 (리포트·경로 표시 전용, 탐색에는 쓰지 않음).
 */
public final class SourceLines {
    private final Snapshot g;
    private final Map<Integer, int[]> starts = new ConcurrentHashMap<>();

    public SourceLines(Snapshot g) {
        this.g = g;
    }

    public static int fileId(long span) {
        return span < 0 ? -1 : (int) (span >>> 32);
    }

    public static int offset(long span) {
        return span < 0 ? -1 : (int) (span & 0xffffffffL);
    }

    public String file(long span) {
        int f = fileId(span);
        return f < 0 ? "" : g.file(f);
    }

    /** 1부터 시작하는 줄 번호. 위치가 없거나 파일을 읽을 수 없으면 0. */
    public int line(long span) {
        int f = fileId(span);
        if (f < 0) return 0;
        int[] s = starts.computeIfAbsent(f, k -> {
            try {
                byte[] b = Files.readAllBytes(Path.of(g.file(k)));
                int n = 1;
                for (byte x : b) if (x == '\n') n++;
                int[] t = new int[n];
                int j = 1;
                for (int i = 0; i < b.length; i++) if (b[i] == '\n') t[j++] = i + 1;
                return t;
            } catch (Exception e) {
                return new int[0];
            }
        });
        if (s.length == 0) return 0;
        int pos = Arrays.binarySearch(s, offset(span));
        return pos >= 0 ? pos + 1 : -pos - 1;
    }
}
