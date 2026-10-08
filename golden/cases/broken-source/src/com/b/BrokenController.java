package com.b;

/** 구문 오류가 있어도 오류 노드만 건너뛰고 나머지 메서드는 추출해야 한다 (FR-IN-01). */
@RestController
public class BrokenController {
    @Autowired
    private NoteMapper mapper;

    public int broken(String x) {
        int y = x.length( + ;
        return y;
    }

    @PostMapping("/notes")
    public int save(@RequestParam("text") String text) {
        return mapper.insert(text);
    }
}
