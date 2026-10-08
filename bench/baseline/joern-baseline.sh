#!/bin/sh
# Joern 기준선 (VR-05): 같은 코퍼스로 CPG 생성 시간·메모리, 데이터 흐름 질의 지연을 측정한다.
# 사전 준비: joern 설치 (https://joern.io), JAVA_HOME = JDK 21+
# 사용법: ./joern-baseline.sh <소스 디렉터리> <결과 디렉터리>
set -eu
SRC="$1"
OUT="${2:-joern-out}"
mkdir -p "$OUT"
# CPG 생성: 시간과 최대 RSS
/usr/bin/time -v joern-parse "$SRC" --output "$OUT/cpg.bin" 2> "$OUT/parse-time.txt"
grep -E "Elapsed|Maximum resident" "$OUT/parse-time.txt"
# Q2 대응: Endpoint 파라미터 → SQL 실행 호출까지의 흐름 (reachableByFlows)
cat > "$OUT/q2.sc" <<'EOF'
importCpg(sys.env("CPG"))
val t0 = System.nanoTime
val src = cpg.method.where(_.annotation.name(".*Mapping")).parameter
val sink = cpg.call.name("update|query|select.*|insert.*").argument
val flows = sink.reachableByFlows(src).l
println(s"flows=${flows.size} ms=${(System.nanoTime - t0) / 1e6}")
EOF
CPG="$OUT/cpg.bin" /usr/bin/time -v joern --script "$OUT/q2.sc" 2> "$OUT/q2-time.txt" | tee "$OUT/q2.txt"
grep -E "Elapsed|Maximum resident" "$OUT/q2-time.txt"
echo "결과를 bench/build/bench/report.md 의 비교 열에 기입한다. (Joern은 MyBatis XML을 해석하지 않으므로 SQL 구간은 비교 대상에서 분리)"
