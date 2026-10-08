-- 현행 Q-Track 기준선 질의 (요구사항서 9장). Q-Track에는 Endpoint 개념이 없으므로 "프로그램"까지로 대응시킨다.
-- 바인드: :prj_id, :table_name, :col_name, :func_id
-- 각 질의를 SET TIMING ON(Oracle) 또는 \timing(PostgreSQL)으로 반복 실행해 p50/p95를 기록한다.

-- [Q1 대응 ①] 컬럼 → 프로그램 (AIS0053 CRUD)
SELECT a.FILE_ID, a.OBJ_ID, a.FUNC_ID, a.CRUD_C, a.CRUD_R, a.CRUD_U
  FROM AIS0053 a
 WHERE a.PRJ_ID = :prj_id
   AND a.TABLE_NAME = :table_name
   AND a.COL_NAME = :col_name;

-- [Q1 대응 ②] 컬럼 흐름 (AIS0081: 컬럼 → 컬럼)
SELECT f.SRC_CAPS_TABLE_NAME, f.SRC_CAPS_COL_NAME, f.TGT_OWNER_NAME
  FROM AIS0081 f
 WHERE f.SRC_PRJ_ID = :prj_id
   AND f.SRC_CAPS_TABLE_NAME = :table_name
   AND f.SRC_CAPS_COL_NAME = :col_name;

-- [Q1 대응 ③] 테이블 흐름 (AIS0080)
SELECT t.TGT_CAPS_TABLE_NAME, t.TGT_TABLE_TYPE
  FROM AIS0080 t
 WHERE t.SRC_PRJ_ID = :prj_id
   AND t.SRC_CAPS_TABLE_NAME = :table_name;

-- [Q3 대응] 호출 관계: 함수 → 호출자 (AIS0052 / AIS0054), 재귀적으로 상위 호출자까지
WITH RECURSIVE callers (FUNC_ID, DEPTH) AS (
    SELECT c.FUNC_ID, 1 FROM AIS0052 c WHERE c.PRJ_ID = :prj_id AND c.CALL_FUNC_ID = :func_id
    UNION ALL
    SELECT c.FUNC_ID, r.DEPTH + 1
      FROM AIS0052 c JOIN callers r ON c.CALL_FUNC_ID = r.FUNC_ID
     WHERE c.PRJ_ID = :prj_id AND r.DEPTH < 20
)
SELECT FUNC_ID, MIN(DEPTH) FROM callers GROUP BY FUNC_ID;
-- Oracle은 WITH RECURSIVE 대신 CONNECT BY PRIOR c.FUNC_ID = c.CALL_FUNC_ID START WITH c.CALL_FUNC_ID = :func_id 사용

-- [저장 용량] 주요 AIS 테이블 크기 (Oracle)
-- SELECT segment_name, ROUND(SUM(bytes)/1024/1024, 1) MB FROM user_segments
--  WHERE segment_name IN ('AIS0052','AIS0053','AIS0054','AIS0080','AIS0081','AIS0112','AIS0113') GROUP BY segment_name;
