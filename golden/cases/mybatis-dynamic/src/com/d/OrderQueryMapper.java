package com.d;

import java.util.List;

@Mapper
public interface OrderQueryMapper {
    List<OrderRow> search(String custId, String status, List<String> ids, String sortColumn);

    List<OrderRow> tree(String root);

    List<OrderRow> pivot(String custId);

    List<OrderRow> huge(String a, String b, String c, String d, String e, String f, String g);
}
