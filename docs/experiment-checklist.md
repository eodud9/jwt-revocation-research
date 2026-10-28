# 실험 실행 체크리스트

> **실험 재현용 절차 문서.** 아래 체크박스는 향후 재실험 시 사용하기 위한 것으로, 체크되지 않았다고 해서 과거 실험을 수행하지 않았다는 뜻은 아니다. 실제 결과는 [experiment-results.md](experiment-results.md)에 기록한다.

## 공통 사전 점검

- [ ] JDK 21, Docker Desktop, k6 설치 확인.
- [ ] `.env`에서 `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE=jwt_research`, `JWT_SECRET` 설정.
- [ ] `docker compose up -d` 후 MySQL·Redis 상태 확인: `docker compose ps`.
- [ ] Spring Boot에 `.env`의 환경변수 전달 및 애플리케이션 실행.
- [ ] 전략 변경 시 `application.yaml` 수정 후 애플리케이션 재시작.
- [ ] 다른 부하 테스트 중지, 불필요한 호스트 작업 최소화.
- [ ] JWT가 실험 종료 전 만료되지 않는지 확인(Access Token 유효시간 30분).
- [ ] `experiments/k6/results/e1` ~ `e4` 디렉터리 존재 확인.

## E1 — Stateless vs Redis Denylist

- [ ] Redis/MySQL 정상, 미폐기 Access Token 준비 및 `ACCESS_TOKEN` 설정.
- [ ] `stateless` 전략에서 앱 재시작, `/api/protected` HTTP 200 확인.
- [ ] `./experiments/k6/run-e1-stateless.sh 20` / `50` / `100`.
- [ ] `fail-open` 전략에서 앱 재시작, Redis 정상 확인.
- [ ] `./experiments/k6/run-e1-redis.sh 20` / `50` / `100`.
- [ ] 조건별 JSON 5개씩, 총 30개 확인.
- [ ] 스크립트의 5 VU/20초 Warm-up, 30초 대기, 각 Run 30초 확인.

## E2 — Redis 장애

- [ ] 정상 토큰·폐기 토큰 별도 준비; `VALID_TOKEN`, `REVOKED_TOKEN` 설정.
- [ ] 폐기 토큰을 로그아웃하여 MySQL과 Redis에 기록되었는지 확인.
- [ ] `docker compose stop redis`, `docker compose ps`로 Redis 중단 확인.
- [ ] MySQL 및 Spring Boot 정상 동작 확인.
- [ ] `fail-open` 전략 적용·앱 재시작 후 `./experiments/k6/run-e2.sh fail-open`.
- [ ] `fail-closed` 전략 적용·앱 재시작 후 `./experiments/k6/run-e2.sh fail-closed`.
- [ ] `db-fallback` 전략 적용·앱 재시작 후 `./experiments/k6/run-e2.sh db-fallback`.
- [ ] 전략별 정상 토큰 100회, 폐기 토큰 100회 확인.
- [ ] `docker compose start redis`로 복구.
- [ ] Redis 중단·복구 상태 로그가 있으면 별도로 보존(기존 결과 JSON에는 포함되지 않음).

## E3 — Redis 정상, 폐기 키 누락

- [ ] Redis/MySQL 정상, 폐기 토큰을 로그아웃하여 MySQL·Redis 기록 확인.
- [ ] Redis의 해당 키만 제거: `redis-cli -p 6380 DEL 'revoked:YOUR_JTI'` (실제 JTI로 교체).
- [ ] `EXISTS`가 0이고 MySQL에는 폐기 기록이 남아 있는지 확인.
- [ ] 같은 토큰으로 다시 로그아웃하지 않음.
- [ ] 전략을 각각 `fail-open`, `fail-closed`, `db-fallback`, `db-verification`으로 전환·앱 재시작.
- [ ] 전략마다 `./experiments/k6/run-e3.sh <strategy>` 실행.
- [ ] 각 전략별 요청 100회 및 DB Query Count JSON 확인.
- [ ] `run-e3.sh`가 실험 전 메트릭을 초기화하는지 확인.

## E4 — S3 vs S4 성능

- [ ] Redis/MySQL 정상, 유효한 미폐기 `ACCESS_TOKEN` 설정.
- [ ] `db-fallback` 적용·앱 재시작 후 `./experiments/k6/run-e4-s3.sh 20` / `50` / `100`.
- [ ] `db-verification` 적용·앱 재시작 후 `./experiments/k6/run-e4-s4.sh 20` / `50` / `100`.
- [ ] 조건별 JSON 5개씩, 총 30개 확인.
- [ ] S3 DB 조회 수 0, S4는 Redis MISS 시 요청 수만큼 DB 조회하는지 메트릭 확인.

## 결과 보존 및 해석

- [ ] 원본 k6 JSON과 DB Query Count JSON을 수정하지 않음.
- [ ] 비정상 실행은 삭제·덮어쓰기보다 별도 보관하고 재실험 이유 기록.
- [ ] E1/E4는 5회 반복 평균 및 표본 표준편차로 집계.
- [ ] E2/E3의 `revoked token allowed` 체크 실패는 HTTP 200이 아니라는 뜻임을 확인.
- [ ] E2 초기 DB Fallback 이상 결과와 재실험 결과를 구분.
- [ ] 실제 실험 상태와 독립 로그로 입증된 범위를 구분해 보고.
