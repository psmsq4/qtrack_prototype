package g;
public class D {
    void m(java.sql.Connection conn) throws Exception {
        conn.prepareStatement("SELECT A FROM T WHERE ID = ?");
    }
}
