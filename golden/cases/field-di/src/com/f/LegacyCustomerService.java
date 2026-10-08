package com.f;

/** 같은 인터페이스의 두 번째 구현. @Qualifier로 배제되어야 한다. 내장 SQL(JdbcTemplate) 사용. */
@Service("legacyCustomerService")
public class LegacyCustomerService implements CustomerService {
    private static final String UPDATE_SQL = "UPDATE CUSTOMER_OLD SET GRADE = ? WHERE ID = ?";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public int changeGrade(String id, String grade) {
        return jdbcTemplate.update(UPDATE_SQL, grade, id);
    }

    @Override
    public String lastGrade() {
        return null;
    }
}
