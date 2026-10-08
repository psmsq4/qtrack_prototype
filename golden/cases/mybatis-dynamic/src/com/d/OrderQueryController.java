package com.d;

import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderQueryController {
    @Autowired
    private OrderQueryMapper mapper;

    @GetMapping("/search")
    public List<OrderRow> search(@RequestParam("custId") String custId, @RequestParam("status") String status,
                                 @RequestParam("ids") List<String> ids, @RequestParam("sortColumn") String sortColumn) {
        return mapper.search(custId, status, ids, sortColumn);
    }

    @GetMapping("/tree")
    public List<OrderRow> tree(@RequestParam("root") String root) {
        return mapper.tree(root);
    }

    @GetMapping("/pivot")
    public List<OrderRow> pivot(@RequestParam("custId") String custId) {
        return mapper.pivot(custId);
    }

    @GetMapping("/huge")
    public List<OrderRow> huge(@RequestParam("a") String a, @RequestParam("b") String b) {
        return mapper.huge(a, b, null, null, null, null, null);
    }
}
