package com.c;

@RestController
public class AController {
    @Autowired
    private CommonMapper mapper;

    @GetMapping("/a")
    public String a(@RequestParam("x") String x) {
        String n = Util.normalize(x);
        return mapper.findA(n);
    }
}
