package com.c;

@RestController
public class BController {
    @Autowired
    private CommonMapper mapper;

    @GetMapping("/b")
    public String b(@RequestParam("y") String y) {
        String n = Util.normalize(y);
        return mapper.findB(n);
    }
}
