package com.ids.qtrack.next.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ids.qtrack.next.rules.RulePack;
import com.ids.qtrack.next.rules.TreeSitterJava;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Tree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Rule Pack 규칙별 골든 테스트 (VR-03, 설계서 3.3). golden/rules/&lt;rule-id&gt;/Input.java + expected.txt.
 * 규칙을 추가할 때는 그 규칙의 디렉터리만 추가하면 되고, 이 테스트는 해당 규칙만 실행합니다
 * ({@code -Dgolden.rule=<rule-id>}로 하나만 실행 가능).
 */
class RuleGoldenTest {
    static final Path ROOT = Path.of(System.getProperty("golden.rules", "golden/rules"));

    @TestFactory
    Stream<DynamicTest> rules() throws Exception {
        String only = System.getProperty("golden.rule");
        List<DynamicTest> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(ROOT)) {
            for (Path dir : s.filter(Files::isDirectory).sorted().toList()) {
                String id = dir.getFileName().toString();
                if (only != null && !only.equals(id)) continue;
                out.add(DynamicTest.dynamicTest(id, () -> check(id, dir)));
            }
        }
        return out.stream();
    }

    static List<String> facts(RulePack p, String src) {
        try (Parser parser = TreeSitterJava.parser(); Tree t = parser.parse(src).orElseThrow()) {
            return p.apply(t.getRootNode()).canonical(src);
        }
    }

    private void check(String id, Path dir) throws Exception {
        RulePack all = RulePack.builtin();
        assertTrue(all.rules().stream().anyMatch(r -> r.id().equals(id)), "규칙 없음: " + id);
        String src = Files.readString(dir.resolve("Input.java"));
        List<String> got = new ArrayList<>(facts(all.only(id), src));
        for (String base : facts(all.baselineFor(id), src)) got.remove(base);
        List<String> expected = Files.readAllLines(dir.resolve("expected.txt")).stream()
                .map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).sorted().toList();
        assertEquals(expected, got.stream().sorted().toList());
    }

    /** 모든 내장 규칙에 골든 테스트가 있어야 한다. */
    @Test
    void everyRuleHasGolden() {
        for (RulePack.Rule r : RulePack.builtin().rules())
            assertTrue(Files.exists(ROOT.resolve(r.id()).resolve("expected.txt")), "골든 없음: " + r.id());
    }
}
