package com.x;

import java.util.List;

@Mapper
public interface OrderMapper {
    List<Order> select(String id);
}
