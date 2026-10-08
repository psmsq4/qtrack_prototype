package com.ids.qtrack.next.mybatis;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * StAX로 읽은 최소 XML 트리. 원문 위치를 문자 인덱스(rawStart)로 보존하고, {@link Doc#byteOffset}으로
 * 파일 바이트 오프셋으로 바꿉니다 (FR-IR-03, 설계서 5.2 span = fileId &lt;&lt; 32 | offset).
 * JDK StAX의 위치는 이벤트 "끝"을 가리키므로, 텍스트의 시작은 직전 이벤트의 끝, 요소의 시작은 {@code <이름}을 거꾸로 찾아 정합니다.
 */
public final class XNode {
    /** rawStart: 이 텍스트 조각이 원문에서 시작하는 문자 인덱스 (include로 복사돼도 원래 위치 유지). */
    public record Text(String text, int rawStart) {}

    /** 파일 원문과 문자 → 바이트 변환표. */
    public static final class Doc {
        public final XNode root;
        public final String raw;
        private final int[] bytePrefix;     // bytePrefix[i] = 문자 i 앞까지의 바이트 수
        private final int[] lineStart;      // 줄 시작 문자 인덱스
        private final int bom;

        Doc(XNode root, String raw, Charset cs, int bom) {
            this.bom = bom;
            this.root = root;
            this.raw = raw;
            bytePrefix = new int[raw.length() + 1];
            CharsetEncoder enc = cs.newEncoder();
            ByteBuffer bb = ByteBuffer.allocate(16);
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                int len;
                if (Character.isHighSurrogate(c) && i + 1 < raw.length()) {
                    len = encLen(enc, bb, raw.substring(i, i + 2));
                    bytePrefix[i + 1] = bytePrefix[i];             // 서로게이트 쌍의 앞 문자는 0바이트로 두고
                    bytePrefix[i + 2 > raw.length() ? raw.length() : i + 2] = bytePrefix[i] + len;
                    i++;
                    continue;
                }
                len = c < 0x80 ? 1 : encLen(enc, bb, String.valueOf(c));
                bytePrefix[i + 1] = bytePrefix[i] + len;
            }
            List<Integer> ls = new ArrayList<>(List.of(0));
            for (int i = 0; i < raw.length(); i++) if (raw.charAt(i) == '\n') ls.add(i + 1);
            lineStart = ls.stream().mapToInt(Integer::intValue).toArray();
        }

        private static int encLen(CharsetEncoder enc, ByteBuffer bb, String s) {
            bb.clear();
            enc.reset();
            enc.encode(CharBuffer.wrap(s), bb, true);
            return Math.max(1, bb.position());
        }

        public int byteOffset(int charIdx) {
            return bom + bytePrefix[Math.max(0, Math.min(charIdx, raw.length()))];
        }

        /** 1부터 시작하는 줄 번호. */
        public int line(int charIdx) {
            int lo = 0, hi = lineStart.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (lineStart[mid] <= charIdx) lo = mid;
                else hi = mid - 1;
            }
            return lo + 1;
        }
    }

    public final String name;
    public final Map<String, String> attrs = new LinkedHashMap<>();
    public final List<Object> children = new ArrayList<>();
    public final int rawStart;

    public XNode(String name, int rawStart) {
        this.name = name;
        this.rawStart = rawStart;
    }

    public String attr(String n) {
        return attrs.get(n);
    }

    public List<XNode> elements() {
        List<XNode> l = new ArrayList<>();
        for (Object o : children) if (o instanceof XNode x) l.add(x);
        return l;
    }

    public XNode copy() {
        XNode n = new XNode(name, rawStart);
        n.attrs.putAll(attrs);
        for (Object o : children) n.children.add(o instanceof XNode x ? x.copy() : o);
        return n;
    }

    private static final Pattern ENCODING = Pattern.compile("<\\?xml[^>]*encoding\\s*=\\s*[\"']([^\"']+)[\"']");

    public static Doc parse(Path file) throws IOException, XMLStreamException {
        byte[] bytes = Files.readAllBytes(file);
        String head = new String(bytes, 0, Math.min(bytes.length, 200), StandardCharsets.ISO_8859_1);
        Matcher em = ENCODING.matcher(head);
        Charset cs = StandardCharsets.UTF_8;
        if (em.find()) {
            try {
                cs = Charset.forName(em.group(1));
            } catch (RuntimeException ignored) {
                // 알 수 없는 인코딩은 UTF-8로
            }
        }
        String raw = new String(bytes, cs);
        int bom = 0;
        if (!raw.isEmpty() && raw.charAt(0) == 0xFEFF) {                  // BOM은 떼고 바이트 오프셋에 그 길이를 더한다
            raw = raw.substring(1);
            bom = new String(new char[]{0xFEFF}).getBytes(cs).length;
        }
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, true);
        try {
            f.setProperty("http://java.sun.com/xml/stream/properties/ignore-external-dtd", true);
        } catch (IllegalArgumentException ignored) {
            // 구현체가 지원하지 않으면 무시
        }
        f.setProperty(XMLInputFactory.IS_COALESCING, true);
        XMLStreamReader r = f.createXMLStreamReader(new StringReader(raw));
        List<XNode> stack = new ArrayList<>();
        XNode root = null;
        int prevEnd = 0;
        while (r.hasNext()) {
            int ev = r.next();
            int end = Math.max(0, r.getLocation().getCharacterOffset());
            switch (ev) {
                case XMLStreamConstants.START_ELEMENT -> {
                    int st = raw.lastIndexOf("<" + r.getLocalName(), Math.max(0, end - 1));
                    XNode n = new XNode(r.getLocalName(), st < 0 ? prevEnd : st);
                    for (int i = 0; i < r.getAttributeCount(); i++) n.attrs.put(r.getAttributeLocalName(i), r.getAttributeValue(i));
                    if (!stack.isEmpty()) stack.getLast().children.add(n);
                    else root = n;
                    stack.add(n);
                }
                case XMLStreamConstants.END_ELEMENT -> stack.removeLast();
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                    if (!stack.isEmpty()) stack.getLast().children.add(new Text(r.getText(), prevEnd));
                }
                default -> { }
            }
            // 텍스트 이벤트의 위치는 다음 태그의 "</" 뒤까지 가 있을 수 있으므로 그 '<' 앞으로 맞춘다
            prevEnd = ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA || ev == XMLStreamConstants.SPACE
                    ? Math.max(prevEnd, raw.lastIndexOf('<', Math.max(0, end - 1)))
                    : end;
        }
        return new Doc(root, raw, cs, bom);
    }
}
