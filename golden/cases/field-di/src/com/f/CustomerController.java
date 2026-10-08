package com.f;

@RestController
@RequestMapping("/customers")
public class CustomerController {
    @Autowired
    @Qualifier("mainCustomerService")
    private CustomerService service;

    @Autowired
    private CustomerMapper mapper;

    @PostMapping("/{id}/grade")
    public int grade(@PathVariable("id") String id, @RequestParam("grade") String grade) {
        return service.changeGrade(id, grade);
    }

    @GetMapping("/last-grade")
    public String last() {
        return service.lastGrade();
    }

    @GetMapping("/{id}")
    public String find(@PathVariable("id") String id) {
        return mapper.findGrade(id);
    }
}
