package com.ids.qtrack.next.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Tree;
import java.util.List;
import org.junit.jupiter.api.Test;

class RulePackTest {
    static final String SRC = """
            package com.x;
            @RestController
            @RequestMapping("/api")
            public class OrderController {
                @Autowired private OrderService orderService;
                @GetMapping("/orders")
                public List<Order> list(@RequestParam("custId") String custId) { return orderService.list(custId); }
                @PostMapping(value = "/orders/{id}")
                public void save(@PathVariable String id, @RequestBody Order o) { }
                @RequestMapping(path = "/legacy", method = RequestMethod.PUT)
                public void legacy() { }
            }
            """;

    static List<String> run(RulePack p, String src) {
        try (Parser parser = TreeSitterJava.parser(); Tree t = parser.parse(src).orElseThrow()) {
            return p.apply(t.getRootNode()).canonical(src);
        }
    }

    @Test
    void springWeb() {
        List<String> f = run(RulePack.builtin(), SRC);
        assertTrue(f.contains("endpoint GET /api/orders @L6"), f.toString());
        assertTrue(f.contains("endpoint POST /api/orders/{id} @L8"), f.toString());
        assertTrue(f.contains("endpoint PUT /api/legacy @L10"), f.toString());
        assertTrue(f.contains("endpointParam custId RequestParam @L7"), f.toString());
        assertTrue(f.contains("endpointParam id PathVariable @L9"), f.toString());
        assertTrue(f.contains("bean orderController @L2"), f.toString());
        assertTrue(f.contains("inject  @L5"), f.toString());
    }

    /** FR-IN-08: 엔진 수정 없이 YAML 추가만으로 새 어노테이션(@QueryMapping) 대응. */
    @Test
    void newAnnotationByYamlOnly() {
        RulePack p = RulePack.builtin();
        p.addYaml("test", """
                rules:
                  - id: graphql.query-mapping
                    match: |
                      (method_declaration
                        (modifiers [(annotation name: (_) @a) (marker_annotation name: (_) @a)] @ann
                          (#eq? @a "QueryMapping"))
                        name: (identifier) @n) @m
                    emit:
                      endpoint: { method: "@m", httpMethod: "'GRAPHQL'", path: "concatPath('/graphql', coalesce(annValue(@ann), text(@n)))" }
                """, x -> "");
        List<String> f = run(p, """
                class Q {
                  @QueryMapping
                  public Book bookById(@Argument String id) { return null; }
                }
                """);
        assertEquals(List.of("endpoint GRAPHQL /graphql/bookById @L2"), f);
    }
}
