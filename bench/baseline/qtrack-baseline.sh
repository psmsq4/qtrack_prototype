#!/bin/sh
# 현행 Q-Track 기준선 측정 (요구사항서 9장, VR-05). 같은 코드·같은 장비에서 실행한다.
#
# 측정 항목
#   1) 인덱싱 시간: 수집(CollectionManager) / 분석(ImpactManager) / 연관(AnalysisManager + MTE) 단계별
#   2) 메모리: 각 JVM의 피크 힙 (-Xlog:gc 로그의 최대 사용량, 기동 스크립트 기본 -Xmx512m)
#   3) 저장 용량: AIS 테이블별 크기 (qtrack-queries.sql 마지막 질의)
#   4) 질의 지연: qtrack-queries.sql 의 Q1/Q3 대응 SQL을 N회 반복
#   5) 정확도: golden/cases 와 같은 골든셋에 대한 recall/precision (결과를 "유형|대상" 목록으로 변환해 비교)
#
# 사용법: QTRACK_HOME=… JDBC_URL=… DB_USER=… DB_PASS=… ./qtrack-baseline.sh <결과 디렉터리>
set -eu
OUT="${1:-baseline-out}"
mkdir -p "$OUT"
: "${QTRACK_HOME:?QTRACK_HOME (lia-engine 설치 위치) 필요}"

# 1~2) 단계별 시간과 GC 로그. 각 단계 기동 스크립트에 아래 JVM 옵션을 추가해 실행한다.
#   -Xlog:gc*:file=$OUT/<단계>-gc.log:time,uptime -XX:+UnlockDiagnosticVMOptions
for phase in collection impact analysis; do
  echo "[$phase] 기동 스크립트를 실행하고 시작/종료 시각을 기록하세요: $QTRACK_HOME/bin/${phase}*.sh" >&2
done
echo "GC 로그에서 피크 힙: grep -o 'Pause.*->[0-9]*M' \$OUT/*-gc.log | sort -t'>' -k2 -n | tail -1" >&2

# 4) 질의 지연: sqlplus/psql 로 qtrack-queries.sql 반복 실행 (예: Oracle)
if command -v sqlplus >/dev/null 2>&1 && [ -n "${JDBC_URL:-}" ]; then
  for i in $(seq 1 "${REPEAT:-30}"); do
    sqlplus -s "$DB_USER/$DB_PASS@$JDBC_URL" @"$(dirname "$0")/qtrack-queries.sql" >> "$OUT/query-timing.log"
  done
  echo "질의 지연 로그: $OUT/query-timing.log (Elapsed 줄을 모아 p50/p95 계산)"
fi
echo "결과를 bench/build/bench/report.md 의 '기준선(Q-Track)' 열에 기입한다."
