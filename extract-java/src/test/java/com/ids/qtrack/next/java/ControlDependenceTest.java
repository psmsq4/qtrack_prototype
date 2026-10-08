package com.ids.qtrack.next.java;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ids.qtrack.next.ir.Edge;
import com.ids.qtrack.next.ir.EdgeKind;
import com.ids.qtrack.next.ir.FileIR;
import com.ids.qtrack.next.ir.MethodIR;
import com.ids.qtrack.next.rules.RulePack;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** FR-CF-02~05 [협의]: 조건 노드와 라벨이 붙은 제어 의존 간선 (설계서 3.2, 11장 필수 케이스 9). */
class ControlDependenceTest {
    static final String HEADER = """
            package t;
            import java.util.List;
            class T {
              int f(int v) { return v; }
              int g(int v) { return v; }
              int h(Object v) { return 0; }
              int k() { return 0; }
              int inc(int v) { return v + 1; }
              void update(String s) { }
              void save(String s) { }
            """;

    static MethodIR method(String body, String name) {
        JavaExtractor ex = new JavaExtractor(RulePack.builtin());
        JavaExtractor.Source src = new JavaExtractor.Source(0, "T.java", HEADER + body + "\n}\n");
        ex.index(List.of(src));
        FileIR f = ex.extract(src);
        return f.getMethodsList().stream().filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
    }

    static Set<String> control(MethodIR m) {
        Set<String> s = new TreeSet<>();
        for (Edge e : m.getEdgesList()) {
            if (e.getKind() != EdgeKind.CONTROL) continue;
            String label = e.getLabel().name() + (e.getCaseValue().isEmpty() ? "" : " " + e.getCaseValue());
            s.add(m.getNodes(e.getSrc()).getName() + " -" + label + "-> " + m.getNodes(e.getDst()).getName());
        }
        return s;
    }

    static Set<String> defUse(MethodIR m) {
        Set<String> s = new TreeSet<>();
        for (Edge e : m.getEdgesList())
            if (e.getKind() == EdgeKind.DEF_USE) s.add(m.getNodes(e.getSrc()).getName() + " -> " + m.getNodes(e.getDst()).getName());
        return s;
    }

    static Set<String> set(String... s) {
        return new TreeSet<>(List.of(s));
    }

    /** 설계서 3.2.2 else-if 체인. else에는 조건 노드가 없음. */
    @Test
    void elseIfChain() {
        MethodIR m = method("""
                void m(int a, int b, Object c) {
                  int x, y, z, w;
                  if (a > 10)          { x = f(a); }
                  else if (b == 1)     { y = g(b); }
                  else if (c != null)  { z = h(c); }
                  else                 { w = k();  }
                }""", "m");
        assertEquals(set(
                "a > 10 -TRUE-> f(·)[0]", "a > 10 -TRUE-> f(·)[this]", "a > 10 -TRUE-> f() 결과", "a > 10 -TRUE-> x#1",
                "a > 10 -FALSE-> b == 1",
                "b == 1 -TRUE-> g(·)[0]", "b == 1 -TRUE-> g(·)[this]", "b == 1 -TRUE-> g() 결과", "b == 1 -TRUE-> y#1",
                "b == 1 -FALSE-> c != null",
                "c != null -TRUE-> h(·)[0]", "c != null -TRUE-> h(·)[this]", "c != null -TRUE-> h() 결과", "c != null -TRUE-> z#1",
                "c != null -FALSE-> k() 결과", "c != null -FALSE-> k(·)[this]", "c != null -FALSE-> w#1"), control(m));
        assertEquals(true, defUse(m).containsAll(set("a -> a > 10", "b -> b == 1", "c -> c != null")));
    }

    /** 가드 조기 반환: 블록 밖이지만 FALSE에 종속. */
    @Test
    void guardEarlyReturn() {
        MethodIR m = method("""
                void m(String id) {
                  if (id == null) return;
                  update(id);
                }""", "m");
        assertEquals(set("id == null -FALSE-> update(·)[0]", "id == null -FALSE-> update(·)[this]", "id == null -FALSE-> update() 결과"), control(m));
    }

    /** 중첩 if: 바로 위 조건에만 연결. */
    @Test
    void nestedIfDirectOnly() {
        MethodIR m = method("""
                void m(int a, int b) {
                  int x = 0;
                  if (a > 0) { if (b > 0) { x = f(b); } }
                }""", "m");
        assertEquals(set("a > 0 -TRUE-> b > 0",
                "b > 0 -TRUE-> f(·)[0]", "b > 0 -TRUE-> f(·)[this]", "b > 0 -TRUE-> f() 결과", "b > 0 -TRUE-> x#2"), control(m));
    }

    /** switch: CASE/DEFAULT, fall-through 블록은 여러 case에 종속. */
    @Test
    void switchFallThrough() {
        MethodIR m = method("""
                void m(int t) {
                  int a, b, c;
                  switch (t) {
                    case 1: a = f(t);
                    case 2: b = g(t); break;
                    default: c = k();
                  }
                }""", "m");
        assertEquals(set(
                "switch (t) -CASE 1-> f(·)[0]", "switch (t) -CASE 1-> f(·)[this]", "switch (t) -CASE 1-> f() 결과", "switch (t) -CASE 1-> a#1",
                "switch (t) -CASE 1-> g(·)[0]", "switch (t) -CASE 1-> g(·)[this]", "switch (t) -CASE 1-> g() 결과", "switch (t) -CASE 1-> b#1",
                "switch (t) -CASE 2-> g(·)[0]", "switch (t) -CASE 2-> g(·)[this]", "switch (t) -CASE 2-> g() 결과", "switch (t) -CASE 2-> b#1",
                "switch (t) -DEFAULT-> k() 결과", "switch (t) -DEFAULT-> k(·)[this]", "switch (t) -DEFAULT-> c#1"), control(m));
    }

    /** 반복 조건은 본문과 자기 자신을 제어 (P ─TRUE→ P). */
    @Test
    void whileControlsItself() {
        MethodIR m = method("""
                void m(int i, int n) {
                  while (i < n) { i = inc(i); }
                }""", "m");
        assertEquals(set("i < n -TRUE-> i < n", "i < n -TRUE-> inc(·)[0]", "i < n -TRUE-> inc(·)[this]", "i < n -TRUE-> inc() 결과",
                "i < n -TRUE-> i#2"), control(m));
    }

    /** 반복문 안의 break/continue. */
    @Test
    void breakContinue() {
        MethodIR m = method("""
                void m(List<String> items) {
                  for (String s : items) {
                    if (s == null) continue;
                    if (s.isEmpty()) break;
                    save(s);
                  }
                }""", "m");
        assertEquals(set(
                "for (s : items) -TRUE-> s#1", "for (s : items) -TRUE-> s == null",
                "s == null -TRUE-> for (s : items)",
                "s == null -FALSE-> isEmpty() 결과", "s == null -FALSE-> s.isEmpty()",
                "s.isEmpty() -FALSE-> save(·)[0]", "s.isEmpty() -FALSE-> save(·)[this]", "s.isEmpty() -FALSE-> save() 결과",
                "s.isEmpty() -FALSE-> for (s : items)"), control(m));
    }

    /** 단락 평가: A ─TRUE→ B, B ─TRUE→ T, A ─FALSE→ E, B ─FALSE→ E. */
    @Test
    void shortCircuit() {
        MethodIR m = method("""
                void m(int a, int b) {
                  int t, e;
                  if (a > 0 && b > 0) { t = f(a); } else { e = g(b); }
                }""", "m");
        assertEquals(set("a > 0 -TRUE-> b > 0",
                "b > 0 -TRUE-> f(·)[0]", "b > 0 -TRUE-> f(·)[this]", "b > 0 -TRUE-> f() 결과", "b > 0 -TRUE-> t#1",
                "a > 0 -FALSE-> g(·)[0]", "a > 0 -FALSE-> g(·)[this]", "a > 0 -FALSE-> g() 결과", "a > 0 -FALSE-> e#1",
                "b > 0 -FALSE-> g(·)[0]", "b > 0 -FALSE-> g(·)[this]", "b > 0 -FALSE-> g() 결과", "b > 0 -FALSE-> e#1"), control(m));
    }

    /** 삼항 연산자: if와 같고 결과는 φ (φ는 종속되지 않음). */
    @Test
    void ternary() {
        MethodIR m = method("""
                int m(int a) {
                  int r = a > 0 ? f(a) : g(a);
                  return r;
                }""", "m");
        assertEquals(set("a > 0 -TRUE-> f(·)[0]", "a > 0 -TRUE-> f(·)[this]", "a > 0 -TRUE-> f() 결과",
                "a > 0 -FALSE-> g(·)[0]", "a > 0 -FALSE-> g(·)[this]", "a > 0 -FALSE-> g() 결과"), control(m));
        assertEquals(true, defUse(m).containsAll(set("f() 결과 -> ?: 결과", "g() 결과 -> ?: 결과", "?: 결과 -> r#1", "r#1 -> 반환")));
    }

    /** try/catch 단순화: catch 값은 EXC(try#n)에 EXCEPTION 라벨로 종속. */
    @Test
    void tryCatch() {
        MethodIR m = method("""
                void m(int a) {
                  int x, y;
                  try { x = f(a); } catch (RuntimeException e) { y = g(a); }
                }""", "m");
        assertEquals(set("EXC(try#1) -EXCEPTION-> e#1", "EXC(try#1) -EXCEPTION-> g(·)[0]", "EXC(try#1) -EXCEPTION-> g(·)[this]",
                "EXC(try#1) -EXCEPTION-> g() 결과", "EXC(try#1) -EXCEPTION-> y#1"), control(m));
    }

    /** 조건부 반환은 FORMAL_OUT 기여 값 노드를 거쳐 조건에 종속. */
    @Test
    void conditionalReturn() {
        MethodIR m = method("""
                int m(int a) {
                  if (a > 0) return f(a);
                  return 0;
                }""", "m");
        assertEquals(true, control(m).containsAll(set("a > 0 -TRUE-> f() 결과", "a > 0 -TRUE-> return@L12")), control(m).toString());
        assertEquals(true, defUse(m).containsAll(set("f() 결과 -> return@L12", "return@L12 -> 반환")));
    }

    /** 설계서 부록 A의 list() 메서드: DEF_USE 0→1→2→3, 4→5. */
    @Test
    void appendixDefUse() {
        MethodIR m = method("""
                Object find(String id) { return null; }
                Object list(String custId) {
                  String id = custId.trim();
                  return find(id);
                }""", "list");
        assertEquals(set("custId -> trim() 결과", "trim() 결과 -> id#1", "id#1 -> find(·)[0]", "find() 결과 -> 반환",
                        "this -> find(·)[this]"),
                defUse(m));
        assertEquals(set(), control(m));
    }
}
