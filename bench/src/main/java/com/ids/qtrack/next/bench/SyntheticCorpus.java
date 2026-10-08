package com.ids.qtrack.next.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 규모 측정용 합성 코퍼스 (NFR-05: 개발·측정은 공개/합성 코퍼스, 고객 코드는 벤치마크 전용).
 * 도메인 하나 = Controller + Service 인터페이스/구현 + Mapper 인터페이스/XML + DTO.
 * 공통 유틸 호출(문맥 민감성), 동적 SQL, 조건 분기, 필드 경유 흐름, 재귀를 고르게 섞습니다.
 */
public final class SyntheticCorpus {
    private SyntheticCorpus() {}

    public static void generate(Path root, int domains) throws IOException {
        Path java = root.resolve("src/com/bench");
        Path xml = root.resolve("src/mapper");
        Files.createDirectories(java.resolve("common"));
        Files.createDirectories(xml);
        Files.writeString(java.resolve("common/Text.java"), """
                package com.bench.common;

                public class Text {
                    public static String norm(String s) {
                        String t = s.trim();
                        return t.toUpperCase();
                    }

                    public static String depth(String s, int n) {
                        if (n <= 0) {
                            return s;
                        }
                        return depth(s, n - 1);
                    }
                }
                """);
        for (int d = 0; d < domains; d++) {
            String D = "D" + d, t = "T" + d;
            Path p = java.resolve("d" + d);
            Files.createDirectories(p);
            Files.writeString(p.resolve(D + "Dto.java"), """
                    package com.bench.d%1$d;

                    public class %2$sDto {
                        private String id;
                        private String name;
                        private String grade;
                        private long amount;

                        public void setId(String v) { this.id = v; }
                        public void setName(String v) { this.name = v; }
                        public void setGrade(String v) { this.grade = v; }
                        public String getGrade() { return grade; }
                    }
                    """.formatted(d, D));
            Files.writeString(p.resolve(D + "Mapper.java"), """
                    package com.bench.d%1$d;

                    import java.util.List;

                    @Mapper
                    public interface %2$sMapper {
                        List<%2$sDto> search(String id, String name, String grade);
                        int update(%2$sDto dto);
                        %2$sDto find(String id);
                    }
                    """.formatted(d, D));
            Files.writeString(p.resolve(D + "Service.java"), """
                    package com.bench.d%1$d;

                    import java.util.List;

                    public interface %2$sService {
                        List<%2$sDto> search(String id, String name, int score);
                        int save(String id, String name, String grade);
                        String last();
                    }
                    """.formatted(d, D));
            Files.writeString(p.resolve(D + "ServiceImpl.java"), """
                    package com.bench.d%1$d;

                    import com.bench.common.Text;
                    import java.util.List;

                    @Service
                    public class %2$sServiceImpl implements %2$sService {
                        @Autowired
                        private %2$sMapper mapper;
                        private String lastName;

                        public List<%2$sDto> search(String id, String name, int score) {
                            if (id == null) {
                                return null;
                            }
                            String key = Text.norm(id);
                            String grade;
                            if (score > 90) {
                                grade = "A";
                            } else if (score > 50) {
                                grade = "B";
                            } else {
                                grade = "C";
                            }
                            return mapper.search(key, Text.depth(name, 2), grade);
                        }

                        public int save(String id, String name, String grade) {
                            this.lastName = name;
                            %2$sDto dto = new %2$sDto();
                            dto.setId(Text.norm(id));
                            dto.setName(name);
                            dto.setGrade(grade);
                            return mapper.update(dto);
                        }

                        public String last() {
                            return lastName;
                        }
                    }
                    """.formatted(d, D));
            Files.writeString(p.resolve(D + "Controller.java"), """
                    package com.bench.d%1$d;

                    import java.util.List;

                    @RestController
                    @RequestMapping("/d%1$d")
                    public class %2$sController {
                        @Autowired
                        private %2$sService service;

                        @GetMapping("/items")
                        public List<%2$sDto> search(@RequestParam("id") String id, @RequestParam("name") String name,
                                                    @RequestParam("score") int score) {
                            return service.search(id, name, score);
                        }

                        @PostMapping("/items/{id}")
                        public int save(@PathVariable("id") String id, @RequestParam("name") String name,
                                        @RequestParam("grade") String grade) {
                            return service.save(id, name, grade);
                        }

                        @GetMapping("/last")
                        public String last() {
                            return service.last();
                        }
                    }
                    """.formatted(d, D));
            Files.writeString(xml.resolve(D + "Mapper.xml"), """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <mapper namespace="com.bench.d%1$d.%2$sMapper">
                      <sql id="cols">ID, NAME, GRADE, AMOUNT</sql>
                      <select id="search" resultType="com.bench.d%1$d.%2$sDto">
                        SELECT <include refid="cols"/> FROM %3$s
                        <where>
                          <if test="id != null">AND ID = #{id}</if>
                          <if test="name != null">AND NAME LIKE '%%' || #{name} || '%%'</if>
                          <if test="grade != null">AND GRADE = #{grade}</if>
                        </where>
                      </select>
                      <update id="update">
                        UPDATE %3$s SET NAME = #{name}, GRADE = #{grade} WHERE ID = #{id}
                      </update>
                      <select id="find" resultType="com.bench.d%1$d.%2$sDto">
                        SELECT t.ID, t.NAME, s.TOTAL FROM %3$s t, %3$s_SUM s WHERE t.ID = s.ID(+) AND t.ID = #{id}
                      </select>
                    </mapper>
                    """.formatted(d, D, t));
        }
    }
}
