package com.x;

import java.util.List;

/** 설계서 부록 A (기획서 s14~s16) 예제: list → find → select. */
@Service
public class OrderService {
    @Autowired
    private OrderMapper orderMapper;

    public List<Order> list(String custId) {
        String id = custId.trim();
        return find(id);
    }

    public List<Order> find(String id) {
        return orderMapper.select(id);
    }
}
