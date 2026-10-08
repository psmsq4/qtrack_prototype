package com.ids.qtrack.next.mybatis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** StAX로 읽은 최소 XML 트리. 텍스트 조각은 {@link Text}, 원문 위치(offset, line)를 보존합니다 (FR-IR-03). */
public final class XNode {
    public record Text(String text, int offset, int line) {}

    public final String name;
    public final Map<String, String> attrs = new LinkedHashMap<>();
    public final List<Object> children = new ArrayList<>();
    public final int offset, line;

    public XNode(String name, int offset, int line) {
        this.name = name;
        this.offset = offset;
        this.line = line;
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
        XNode n = new XNode(name, offset, line);
        n.attrs.putAll(attrs);
        for (Object o : children) n.children.add(o instanceof XNode x ? x.copy() : o);
        return n;
    }

    public static XNode parse(Path file) throws IOException, XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, true);
        try {
            f.setProperty("http://java.sun.com/xml/stream/properties/ignore-external-dtd", true);
        } catch (IllegalArgumentException ignored) {
            // 구현체가 지원하지 않으면 무시
        }
        f.setProperty(XMLInputFactory.IS_COALESCING, true);
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader r = f.createXMLStreamReader(in);
            List<XNode> stack = new ArrayList<>();
            XNode root = null;
            while (r.hasNext()) {
                int ev = r.next();
                switch (ev) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        XNode n = new XNode(r.getLocalName(), r.getLocation().getCharacterOffset(), r.getLocation().getLineNumber());
                        for (int i = 0; i < r.getAttributeCount(); i++) n.attrs.put(r.getAttributeLocalName(i), r.getAttributeValue(i));
                        if (!stack.isEmpty()) stack.getLast().children.add(n);
                        else root = n;
                        stack.add(n);
                    }
                    case XMLStreamConstants.END_ELEMENT -> stack.removeLast();
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                        if (!stack.isEmpty())
                            stack.getLast().children.add(new Text(r.getText(), r.getLocation().getCharacterOffset(),
                                    r.getLocation().getLineNumber()));
                    }
                    default -> { }
                }
            }
            return root;
        }
    }
}
