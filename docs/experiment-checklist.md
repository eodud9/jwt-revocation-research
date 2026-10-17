# 실험 실행 체크리스트 (Experiment Checklist)

## 1. 목적

본 문서는 `experiment-design.md`에서 정의한 E1~E4 실험을 동일한 조건에서 재현하기 위한 실행 체크리스트이다.

예비실험 결과를 바탕으로 다음 조건을 고정한다.

- 성능 실험 부하 수준: 20 / 50 / 100 VU
- 성능 측정 시간: 각 Run 30초
- 성능 실험 반복 횟수: 조건별 5회
- Warm-up: 5 VU / 20초
- Warm-up 이후 대기: 30초
- 반복 측정 사이 대기: 30초
- E2/E3 인증 결과 측정: 1 VU / 100 iterations
- E2: Valid Token 100회 + Revoked Token 100회
- E3: Revoked Token 100회

E1과 E4는 성능 측정 실험이므로 Warm-up과 반복 측정을 수행한다.

E2와 E3는 부하 성능이 아니라 장애 및 데이터 불일치 상황에서의 인증 결과를 측정하므로 별도의 Warm-up을 수행하지 않는다.


## 2. 공통 실험 환경 확인

### 애플리케이션 및 인프라

- [ ] MySQL 컨테이너 정상 실행
- [ ] Redis 컨테이너 정상 실행
- [ ] Spring Boot 애플리케이션 정상 실행
- [ ] `/api/protected` 정상 접근 확인
- [ ] 기존 k6 프로세스가 실행 중이지 않은지 확인
- [ ] 실험 결과 저장 디렉터리 존재 확인

```bash
docker compose ps
pgrep -fl java
pgrep -fl k6
```

결과 디렉터리:

```text
experiments/k6/results/
├── e1/
├── e2/
├── e3/
└── e4/
```

### 성능 실험 환경 통제

E1과 E4 측정 중에는 다음 조건을 유지한다.

- [ ] IntelliJ에서 코드 수정 작업을 하지 않는다.
- [ ] 브라우저 및 불필요한 프로그램 사용을 최소화한다.
- [ ] 다른 부하 테스트를 동시에 실행하지 않는다.
- [ ] 동일한 Spring Boot / MySQL / Redis 설정을 유지한다.
- [ ] 실험 도중 애플리케이션 코드를 변경하지 않는다.
- [ ] 비정상적으로 시스템 부하가 발생한 Run은 기록하고 재측정 여부를 판단한다.


## 3. E1 — Redis Denylist 기본 성능 비용

### 목적

Stateless JWT 인증과 Redis Denylist 기반 인증의 성능을 비교한다.

### 공통 조건

- [ ] Redis 정상
- [ ] MySQL 정상
- [ ] 정상 Access Token 사용
- [ ] 20 / 50 / 100 VU 사용
- [ ] 각 부하 수준에서 30초 측정
- [ ] 각 조건 5회 반복
- [ ] 5 VU / 20초 Warm-up
- [ ] Warm-up 후 30초 대기
- [ ] 각 Run 사이 30초 대기

---

### E1-A — Stateless JWT

설정:

```yaml
jwt:
  revocation:
    strategy: stateless
```

실행 전:

- [ ] `strategy = stateless` 확인
- [ ] Spring Boot 재시작
- [ ] Fresh Access Token 발급
- [ ] `ACCESS_TOKEN` 환경변수 설정
- [ ] `/api/protected` 응답 200 확인

실행:

```bash
./experiments/k6/run-e1-stateless.sh 20
./experiments/k6/run-e1-stateless.sh 50
./experiments/k6/run-e1-stateless.sh 100
```

결과 확인:

- [ ] 20 VU 결과 5개
- [ ] 50 VU 결과 5개
- [ ] 100 VU 결과 5개
- [ ] 모든 요청에서 예상하지 못한 HTTP 오류가 없는지 확인

---

### E1-B — Redis Denylist

설정:

```yaml
jwt:
  revocation:
    strategy: fail-open
```

Redis가 정상인 상태에서 Fail-Open 전략의 Redis 조회 경로를 Redis Denylist 조건으로 사용한다.

실행 전:

- [ ] `strategy = fail-open` 확인
- [ ] Redis 정상 확인
- [ ] Spring Boot 재시작
- [ ] Fresh Access Token 발급
- [ ] `ACCESS_TOKEN` 환경변수 설정
- [ ] `/api/protected` 응답 200 확인

실행:

```bash
./experiments/k6/run-e1-redis.sh 20
./experiments/k6/run-e1-redis.sh 50
./experiments/k6/run-e1-redis.sh 100
```

결과 확인:

- [ ] 20 VU 결과 5개
- [ ] 50 VU 결과 5개
- [ ] 100 VU 결과 5개
- [ ] 모든 요청에서 예상하지 못한 HTTP 오류가 없는지 확인

### E1 기록 지표

- [ ] Average Latency
- [ ] P95 Latency
- [ ] Throughput
- [ ] Error Rate


## 4. E2 — Redis 장애 대응

### 목적

Redis Connection Failure 상황에서 S1 Fail-Open, S2 Fail-Closed, S3 DB Fallback의 보안성과 가용성을 비교한다.

### 토큰 및 상태 준비

Redis가 정상인 상태에서 준비한다.

- [ ] Valid Token 발급
- [ ] Revoked Token으로 사용할 별도 토큰 발급
- [ ] `VALID_TOKEN` 환경변수 설정
- [ ] `REVOKED_TOKEN` 환경변수 설정
- [ ] Revoked Token으로 logout 수행
- [ ] MySQL에 Revoked Token의 폐기 정보 존재 확인
- [ ] Redis에 Revoked Token의 폐기 정보 존재 확인

장애 주입:

```bash
docker compose stop redis
```

- [ ] Redis 컨테이너 중단 확인
- [ ] MySQL 정상 확인
- [ ] Redis 중단 이후 토큰을 새로 발급하거나 logout하지 않는다.

---

### E2-A — S1 Fail-Open

- [ ] `strategy = fail-open`
- [ ] Redis DOWN 상태 유지
- [ ] Spring Boot 재시작
- [ ] Redis 장애 상태에서 Spring Boot 정상 실행 확인

실행:

```bash
./experiments/k6/run-e2.sh fail-open
```

예상:

- VTSR 높음
- RTAR 높음

---

### E2-B — S2 Fail-Closed

- [ ] `strategy = fail-closed`
- [ ] Redis DOWN 상태 유지
- [ ] Spring Boot 재시작

실행:

```bash
./experiments/k6/run-e2.sh fail-closed
```

예상:

- VTSR 낮음
- RTAR 낮음

---

### E2-C — S3 DB Fallback

- [ ] `strategy = db-fallback`
- [ ] Redis DOWN 상태 유지
- [ ] MySQL 정상 상태 확인
- [ ] Spring Boot 재시작

실행:

```bash
./experiments/k6/run-e2.sh db-fallback
```

예상:

- VTSR 높음
- RTAR 낮음

### E2 기록 지표

- [ ] Valid Token 요청 100회 수행 확인
- [ ] Revoked Token 요청 100회 수행 확인
- [ ] VTSR 계산
- [ ] RTAR 계산
- [ ] 예상하지 못한 요청 실패 여부 기록

E2 종료 후:

```bash
docker compose start redis
```

- [ ] Redis 정상 복구 확인


## 5. E3 — Redis-MySQL 데이터 불일치

### 목적

Redis 서버 자체는 정상 작동하지만 Redis와 MySQL의 폐기 상태가 불일치할 때 S1~S4의 인증 결과를 비교한다.

### 불일치 상태 준비

Fresh Access Token을 발급한다.

- [ ] `REVOKED_TOKEN` 환경변수 설정
- [ ] 해당 토큰으로 logout 수행
- [ ] MySQL에 폐기 정보 존재 확인
- [ ] Redis에 폐기 정보 존재 확인

다음 상태를 구성한다.

```text
MySQL: revoked 존재
Redis: revoked key 없음
Redis Server: 정상
```

- [ ] Redis의 해당 폐기 정보 제거
- [ ] MySQL 폐기 기록 유지 확인
- [ ] Redis 서버 정상 동작 확인
- [ ] Redis에서 해당 폐기 정보가 없음을 확인
- [ ] 이후 해당 토큰으로 logout을 다시 수행하지 않는다.

연구 전용 Redis DB 전체를 초기화하여 상태를 구성하는 경우:

```bash
redis-cli -p 6380 FLUSHDB
```

실제 실험 방법이 특정 key 삭제인 경우에는 최종 논문에도 실제 사용한 방법을 그대로 기록한다.

---

### E3-A — S1 Fail-Open

- [ ] `strategy = fail-open`
- [ ] Spring Boot 재시작
- [ ] Redis 정상
- [ ] MySQL revoked / Redis MISS 상태 유지

```bash
./experiments/k6/run-e3.sh fail-open
```

---

### E3-B — S2 Fail-Closed

- [ ] `strategy = fail-closed`
- [ ] Spring Boot 재시작
- [ ] Redis 정상
- [ ] MySQL revoked / Redis MISS 상태 유지

```bash
./experiments/k6/run-e3.sh fail-closed
```

---

### E3-C — S3 DB Fallback

- [ ] `strategy = db-fallback`
- [ ] Spring Boot 재시작
- [ ] Redis 정상
- [ ] MySQL revoked / Redis MISS 상태 유지

```bash
./experiments/k6/run-e3.sh db-fallback
```

---

### E3-D — S4 DB Verification

- [ ] `strategy = db-verification`
- [ ] Spring Boot 재시작
- [ ] Redis 정상
- [ ] MySQL revoked / Redis MISS 상태 유지

```bash
./experiments/k6/run-e3.sh db-verification
```

### E3 기록 지표

- [ ] 각 전략에서 Revoked Token 100회 요청
- [ ] RTAR 계산
- [ ] DB Query Count 저장 확인

예상되는 동작 검증:

```text
Strategy           RTAR 예상     DB Query Count 예상
-----------------------------------------------------
Fail-Open             100%               0
Fail-Closed           100%               0
DB Fallback           100%               0
DB Verification         0%             100
```


## 6. E4 — DB Verification 성능 비용

### 목적

Redis와 MySQL이 모두 정상인 상황에서 S4 DB Verification이 S3 DB Fallback보다 발생시키는 추가적인 성능 비용을 측정한다.

### 공통 조건

- [ ] Redis 정상
- [ ] MySQL 정상
- [ ] 정상 Access Token 사용
- [ ] 20 / 50 / 100 VU
- [ ] 각 부하 수준 30초
- [ ] 각 조건 5회 반복
- [ ] 5 VU / 20초 Warm-up
- [ ] Warm-up 후 30초 대기
- [ ] 각 Run 사이 30초 대기

---

### E4-A — S3 DB Fallback

- [ ] `strategy = db-fallback`
- [ ] Spring Boot 재시작
- [ ] Fresh Access Token 발급
- [ ] `ACCESS_TOKEN` 설정
- [ ] `/api/protected` → 200 확인

실행:

```bash
./experiments/k6/run-e4-s3.sh 20
./experiments/k6/run-e4-s3.sh 50
./experiments/k6/run-e4-s3.sh 100
```

확인:

- [ ] 각 부하 수준별 결과 5개
- [ ] 정상 Redis 상태 유지
- [ ] DB Query Count = 0 확인

---

### E4-B — S4 DB Verification

- [ ] `strategy = db-verification`
- [ ] Spring Boot 재시작
- [ ] Fresh Access Token 발급
- [ ] `ACCESS_TOKEN` 설정
- [ ] `/api/protected` → 200 확인

실행:

```bash
./experiments/k6/run-e4-s4.sh 20
./experiments/k6/run-e4-s4.sh 50
./experiments/k6/run-e4-s4.sh 100
```

확인:

- [ ] 각 부하 수준별 결과 5개
- [ ] 정상 Redis 상태 유지
- [ ] 각 Run의 DB Query Count 저장 확인
- [ ] DB Query Count와 HTTP Request 수 비교

### E4 기록 지표

- [ ] Average Latency
- [ ] P95 Latency
- [ ] Throughput
- [ ] Error Rate
- [ ] DB Query Count


## 7. Access Token 유효시간 확인

Access Token 유효시간은 30분이므로 성능 실험 도중 토큰이 만료되지 않도록 한다.

E1/E4에서는 필요하면 각 부하 수준 측정 전에 Fresh Token을 다시 발급한다.

```text
20 VU → Fresh Token
50 VU → Fresh Token
100 VU → Fresh Token
```

단, E2/E3에서는 특정 토큰의 폐기 상태 자체가 실험 조건이므로 실험 도중 임의로 토큰을 교체하지 않는다.

- [ ] 각 실험 시작 전 토큰 만료시간 확인
- [ ] E1/E4 장시간 측정 시 Fresh Token 사용
- [ ] E2/E3 도중 토큰 교체 금지


## 8. 실험 종료 후 결과 확인

### E1

- [ ] Stateless: 20 VU × 5개
- [ ] Stateless: 50 VU × 5개
- [ ] Stateless: 100 VU × 5개
- [ ] Redis Denylist: 20 VU × 5개
- [ ] Redis Denylist: 50 VU × 5개
- [ ] Redis Denylist: 100 VU × 5개

### E2

- [ ] Fail-Open 결과
- [ ] Fail-Closed 결과
- [ ] DB Fallback 결과

### E3

- [ ] Fail-Open 결과 + Metrics
- [ ] Fail-Closed 결과 + Metrics
- [ ] DB Fallback 결과 + Metrics
- [ ] DB Verification 결과 + Metrics

### E4

- [ ] S3: 20 VU × 5개
- [ ] S3: 50 VU × 5개
- [ ] S3: 100 VU × 5개
- [ ] S4: 20 VU × 5개 + Metrics
- [ ] S4: 50 VU × 5개 + Metrics
- [ ] S4: 100 VU × 5개 + Metrics


## 9. 원본 데이터 보존

- [ ] k6 원본 JSON 파일을 수정하지 않는다.
- [ ] Metrics JSON 파일을 수정하지 않는다.
- [ ] 비정상 Run이 발생해도 즉시 삭제하지 않고 별도로 기록한다.
- [ ] 재실험한 경우 기존 결과와 새로운 결과를 구분한다.
- [ ] Pilot 결과와 Final 결과를 혼합하지 않는다.


## 10. 최종 분석 준비

E1과 E4의 반복 측정 결과에 대해 다음 값을 계산한다.

- 평균
- 표준편차
- 필요 시 95% 신뢰구간

E2와 E3에서는 다음 값을 계산한다.

```text
RTAR = 허용된 Revoked Token 요청 수
       / 전체 Revoked Token 요청 수 × 100

VTSR = 성공한 Valid Token 요청 수
       / 전체 Valid Token 요청 수 × 100
```

최종 결과표 작성 전 다음을 확인한다.

- [ ] 모든 전략의 실험 조건이 동일했는지 확인
- [ ] 누락된 Run 확인
- [ ] HTTP Error 확인
- [ ] 토큰 만료가 발생한 Run 확인
- [ ] Redis/MySQL 상태가 의도한 조건과 일치했는지 확인
- [ ] DB Query Count가 예상 실행 경로와 일치하는지 확인
- [ ] 이상치 또는 재측정 Run의 처리 기준 기록