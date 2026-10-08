package com.r;

/** 직접 재귀(walk)와 상호 재귀(even/odd): 요약은 SCC 고정점으로 계산되어야 한다 (FR-GR-06, D-03). */
@RestController
public class RecController {
    @Autowired
    private RecMapper mapper;

    @PostMapping("/rec")
    public int api(@RequestParam("v") String v, @RequestParam("depth") int depth) {
        String r = walk(v, depth);
        String e = even(r, 4);
        return mapper.save(e);
    }

    /** 기저 return s; → s ⇒ 반환. n은 조건으로만 결과에 영향 (제어 의존). */
    String walk(String s, int n) {
        if (n <= 0) {
            return s;
        }
        return walk(s, n - 1);
    }

    String even(String s, int n) {
        if (n == 0) {
            return s;
        }
        return odd(s, n - 1);
    }

    String odd(String s, int n) {
        if (n == 0) {
            return "odd";
        }
        return even(s, n - 1);
    }

    /** 재귀이지만 파라미터가 반환으로 흐르지 않음 (오탐 방지). */
    String noFlow(String s, int n) {
        if (n <= 0) {
            return "const";
        }
        return noFlow(s, n - 1);
    }

    @GetMapping("/noflow")
    public int noflow(@RequestParam("w") String w) {
        String r = noFlow(w, 2);
        return mapper.save(r);
    }
}
