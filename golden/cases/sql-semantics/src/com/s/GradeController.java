package com.s;

import java.util.List;

@RestController
public class GradeController {
    @Autowired
    private GradeMapper mapper;

    @GetMapping("/grades")
    public List<String> byGrade(@RequestParam("grade") String grade) {
        return mapper.byGrade(grade);
    }

    @GetMapping("/grades/view")
    public List<String> byGradeView(@RequestParam("g") String g) {
        return mapper.byGradeView(g);
    }
}
