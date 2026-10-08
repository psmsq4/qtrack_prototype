package com.f;

@Mapper
public interface CustomerMapper {
    int updateGrade(Customer c);

    @Select("SELECT CUST_GRADE FROM CUSTOMER WHERE CUST_ID = #{id}")
    String findGrade(@Param("id") String id);
}
