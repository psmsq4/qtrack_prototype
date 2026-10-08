package g;
public class D {
    private JdbcTemplate jdbcTemplate;
    void m(String a) {
        jdbcTemplate.update("UPDATE T SET A = ?", a);
        jdbcTemplate.query("SELECT 1", null);
    }
}
