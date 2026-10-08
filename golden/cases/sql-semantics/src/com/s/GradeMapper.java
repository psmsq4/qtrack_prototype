package com.s;

import java.util.List;

@Mapper
public interface GradeMapper {
    List<String> byGrade(String grade);

    List<String> byGradeView(String g);
}
