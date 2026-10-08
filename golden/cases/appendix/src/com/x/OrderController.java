package com.x;

import java.util.List;

@RestController
@RequestMapping("/api")
public class OrderController {
    @Autowired
    private OrderService orderService;

    @GetMapping("/orders")
    public List<Order> orders(@RequestParam("custId") String custId) {
        return orderService.list(custId);
    }
}
