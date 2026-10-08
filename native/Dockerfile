# 팀 공통 네이티브 빌드 환경 고정 (NFR-03). ./gradlew :native:buildTreeSitterNativeDocker 로 사용
FROM gcc:14.2.0
WORKDIR /work
ENTRYPOINT ["sh", "/work/build-native.sh", "/work/src", "/work/out"]
