package com.k;

/** 제어 의존 (FR-CF, [협의]): 가드 조기 반환, else-if 체인. score는 조건으로만 grade를 결정한다. */
@RestController
public class GradeController {
    @Autowired
    private ScoreMapper mapper;

    @PostMapping("/grade")
    public int update(@RequestParam("id") String id, @RequestParam("score") int score) {
        if (id == null) {
            return 0;
        }
        String grade;
        if (score > 90) {
            grade = "A";
        } else if (score > 70) {
            grade = "B";
        } else {
            grade = "C";
        }
        return mapper.update(id, grade);
    }

    @PostMapping("/level")
    public int level(@RequestParam("id") String id, @RequestParam("kind") int kind, @RequestParam("flag") boolean flag) {
        String level = "N";
        switch (kind) {
            case 1:
                level = "L1";
            case 2:
                level = level + "L2";
                break;
            default:
                level = "X";
        }
        String mark = flag && kind > 0 ? "Y" : "N";
        return mapper.update(id, level + mark);
    }
}
