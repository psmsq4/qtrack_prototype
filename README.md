# Q-Track 차세대 엔진 프로토타입 (qtrack-next)

`~/qtrack/QTrack_차세대엔진_요구사항정의서.md`와 `QTrack_차세대엔진_설계서.md`를 구현한 프로토타입입니다.
Java + MyBatis(XML) + Oracle SQL 한 줄기를 끝까지 관통하고, **[협의] 항목(제어 의존: 조건 노드·CONTROL·CONTROL_FLOW·`--control`)과
추가 요구 FR-SQ(SQL 의미 해석)를 모두 구현**했습니다. 요구사항별 구현 위치와 상태는 [`docs/구현_추적표.md`](docs/구현_추적표.md)에 있습니다.

```
 Java 소스 ──▶ tree-sitter(jtreesitter) + Rule Pack(YAML+.scm) ─┐
 MyBatis XML ─▶ StAX + 동적 SQL 전개(상한 64)                   ├─▶ protobuf IR ─▶ 그래프 빌더 ─▶ 스냅샷(mmap) ─▶ 질의(2단계) ─▶ CLI / HTML / Parquet
 SQL(배치·내장) ─▶ JSqlParser + 카탈로그(이름 해석, *, COL_DERIVES) ┘           (SCC 요약, CSR)
```

## 빌드

| 항목 | 값 |
|---|---|
| JDK | `~/apps/jdk-25.0.3+9` (Temurin 25 LTS). `gradle.properties`의 `org.gradle.java.home`으로 고정 |
| 빌드 | Gradle 9.6.1 wrapper (`./gradlew`) |
| 네이티브 | tree-sitter 0.26.1 + tree-sitter-java 0.23.5를 `:native:buildTreeSitterNative`가 소스에서 빌드 (cc). Docker 고정 환경은 `:native:buildTreeSitterNativeDocker` (NFR-03) |

```sh
./gradlew build                 # 전체 컴파일 + 테스트 98개 (골든셋·규칙 골든·제어 의존·CSR 등)
./gradlew :cli:installDist      # cli/build/install/qtrack-next (네이티브 라이브러리 포함)
```

실행 스크립트는 JDK 25가 필요합니다: `export JAVA_HOME=~/apps/jdk-25.0.3+9`

## 사용법 (FR-RP-01)

```sh
Q=cli/build/install/qtrack-next/bin/qtrack-next

# 인덱싱: 소스 → IR(ir/*.pb) → 스냅샷
$Q index --src golden/cases/field-di/src --out index/fdi \
         [--catalog schema.sql|meta.csv] [--datasource-map ds.yaml] [--rules my-rules/] [--limit 64]

# Q1 컬럼 영향도 (역방향 + SELECT로 읽히면 정방향)
$Q query column CUSTOMER.CUST_GRADE --index index/fdi [--min-conf RESOLVED] [--implicit] [--control] [--paths] [--out result/q1]
# Q2 파라미터 흐름
$Q query flow "POST /customers/{id}/grade" grade --index index/fdi --paths
# Q3 메서드 변경 영향 (역방향 호출 그래프 + DI)
$Q query method "com.f.CustomerService#changeGrade" --index index/fdi

$Q report html --query result/q1 --out report/     # HTML 리포트 (경로 단계마다 소스 줄·조건 사슬)
$Q export --index index/fdi --out export/          # node.parquet
$Q stats  --index index/fdi                        # 노드·간선 수, NFR-06 메모리 추정, 빌드 통계
```

질의 옵션
- `--min-conf EXACT|RESOLVED|HEURISTIC`: 신뢰도 필터 (FR-QR-05, 값이 작을수록 확정적 — D-07)
- `--implicit`: WHERE 바인드 → 결과행 같은 **암묵적 흐름** 포함 (FR-SQ-06, D-06)
- `--control`: **제어 의존(CONTROL_FLOW) 경유** 포함 (FR-CF-06). 꺼져 있어도 `--paths`/HTML 경로에는 각 값의 조건 사슬을 표시합니다.

예: `score`가 조건으로만 등급을 정하는 경우 ([golden/cases/control](golden/cases/control))
```
$Q query flow /grade score --index … --control --paths
1  메서드  EXACT  ctl  com.k.GradeController#update(java.lang.String,int)
      FORMAL_IN score  @GradeController.java:10
      PREDICATE score > 90  @GradeController.java:15  [`id == null` = FALSE]
      LOCAL grade#1  @GradeController.java:16  [`id == null` = FALSE → `score > 90` = TRUE]
      …
4  컬럼   RESOLVED ctl  MEMBER.GRADE
```

## 모듈 (설계서 2.3)

| 모듈 | 내용 |
|---|---|
| `native` | tree-sitter 네이티브 빌드 (cc / Docker) |
| `ir` | `qtrack_ir.proto` — 추출기 ↔ 엔진 계약 (FR-IR-01) |
| `rules` | Rule Pack 엔진(YAML + tree-sitter query, 식 언어) + 기본 팩 `rules/{spring,mybatis,jdbc}` |
| `extract-java` | 2패스 추출: 선언 색인 → CST→SSA lowering(Braun), CFG, **PREDICATE/CONTROL(후지배 트리)**, 라이브러리 요약 |
| `extract-mybatis` | StAX, `<include>/<if>/<choose>/<foreach>/<where>/<set>/<trim>/<bind>` 전개, 상한 축약 |
| `extract-sql` | JSqlParser 분석: 범위·이름 해석, `*` 치환, 바인드↔컬럼(절), COL_DERIVES, 합성 메서드 |
| `catalog` | 카탈로그(DDL/CSV/QT_META_POPULATOR) + 데이터소스 매핑 |
| `graph-build` | ID 배정(D-01), LOCAL_FLOW/CONTROL_FLOW, 링크(DI·Mapper), **SCC 고정점 요약 + 플래그 마스크**, CSR(D-02), 스냅샷 |
| `graph-store` | off-heap CSR·노드 표·문자열 사전·정렬 색인·L2 chunk (FFM `MemorySegment` + mmap) |
| `query` | 2단계 탐색, L2 경로 펼침, 조건 사슬 표시, 결과 모델 |
| `report` | DuckDB → Parquet(`impact`, `path_step`, `node`), HTML |
| `cli` | `index` / `query` / `report` / `export` / `stats` |
| `bench` | 합성 코퍼스, `measure`(p50/p95/p99, Go/No-Go 표), JMH, Q-Track·Joern 기준선 스크립트 |
| `golden` | 골든 코퍼스 9케이스 + 규칙 골든 12개 + 부록 A 대조 테스트 |

## 스냅샷 레이아웃 (설계서 5.6)
```
index/<snapshot>/
  meta.json strings.dict files.tsv
  nodes/   kind.u8 owner.i32 name.i32 span.i64 chunk.i32
  methods/ start.i32 end.i32 sig.i32 flags.u8 fo.i32 file.i32 fin.{off,tgt}
  iface/   <KIND>.{fwd,rev}.{off,tgt,conf[,clause,flags,masks,derive]}   (LOCAL_FLOW ARG_IN RET_OUT SUMMARY STORE LOAD BIND_TO MAPS_TO COL_DERIVES CONTROL_FLOW)
           summary.tbl
  l2/      chunks.idx chunks.bin    (메서드별 LOCAL·PREDICATE + DEF_USE/CONTROL 정·역 CSR, label·caseVal)
  idx/     column.idx endpoint.idx endpoint_param.idx method.idx call.{fwd,rev}.*
  delta/   (Phase 3 증분용, 비어 있음)
  ir/      java.pb sql.pb  (길이 접두 protobuf FileIR — 엔진은 이 IR만 읽음)
```

## 검증 (VR)

```sh
./gradlew :golden:test                    # 골든셋 → golden/build/golden/summary.md (태그별 recall/precision)
./gradlew :golden:test -Dgolden.rule=spring.request-mapping   # 규칙 하나만 (VR-03)
./gradlew :bench:measure -Pdomains=200 -Pqueries=300 [-Psrc=<실제 코드>] [-Pjfr]   # bench/build/bench/report.md
./gradlew :bench:jmh "-Pjmh.args=-f 1 -wi 2 -i 3 QueryBench"
bench/baseline/qtrack-baseline.sh, joern-baseline.sh, qtrack-queries.sql     # 기준선 (VR-05, 요구사항서 9장)
```

현재 측정 (합성 200도메인, 메서드 3,802, 노드 20,808, 24 CPU):
인덱싱 4.5초, 스냅샷 6.8MB, 상주 2.25MB(NFR-06 추정 2.04MB), Q1 p95 0.33ms / Q2 0.17ms / Q3 0.05ms (콜드 측정), JMH Q1 p95 30µs.
골든셋 35질의 171항목 recall/precision 1.000.
※ 이 골든셋은 프로토타입 개발 중 함께 만든 코퍼스라 **회귀 기준선**입니다. Go/No-Go의 정확도 판정은 VR-01 코퍼스
(전자정부 공통컴포넌트 + 벤치마크 코드)의 골든셋으로 다시 측정해야 합니다. 인덱싱 피크 힙(688MB)은 `-Xmx2g`에서 G1이 늦게 회수한
최대 사용량이라 실사용량의 상한일 뿐입니다.

## 알려진 한계
[`docs/구현_추적표.md`](docs/구현_추적표.md) 6장 참고. 요약: 람다는 순차 실행 근사, 익명 클래스 본문 미분석, 경로 민감 분석 제외(FR-CF-07),
증분 갱신(Phase 3) 미구현(설계만), `PreparedStatement.setXxx` 바인드 미연결, QT_META_POPULATOR의 컬럼명 열은 가정값(`-Dqtrack.qtmeta.column`).
