package g;
@Mapper
public interface M {
    @Select("SELECT * FROM T WHERE ID = #{id}")
    T find(String id);
    @Update({"UPDATE T SET A = #{a}", "WHERE ID = #{id}"})
    int upd(T t);
}
