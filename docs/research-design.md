# 연구 설계 (Research Design)

## 1. 연구 제목(가제)

**Redis 기반 JWT 폐기 검증 시스템에서 데이터 일관성 및 장애 대응 전략의
비교 분석**

## 2. 연구 배경

JWT(JSON Web Token)는 서버가 세션 상태를 직접 유지하지 않고도 사용자를
인증할 수 있다는 장점이 있다. 그러나 이미 발급된 Access Token은 만료
전까지 자체적으로 유효하므로, 로그아웃 등의 상황에서 즉시 폐기하기
어렵다.

이를 보완하기 위해 본 연구에서는 폐기된 Access Token의 `jti`(JWT ID)를
Redis Denylist에 저장하고, 보호된 API 요청마다 해당 토큰의 폐기 여부를
확인하는 구조를 사용한다.

그러나 이 구조는 인증 시스템이 Redis라는 외부 상태 저장소에 의존하게
만든다. 특히 다음 두 문제를 구분할 필요가 있다.

1.  **Redis 가용성 장애**: Redis에 연결할 수 없어 폐기 여부 자체를
    조회할 수 없는 경우
2.  **Redis-MySQL 상태 불일치**: Redis는 정상 작동하지만 MySQL에
    존재하는 폐기 정보가 Redis에 반영되지 않은 경우

두 상황은 원인이 다르지만 모두 잘못된 인증 판단으로 이어질 수 있다.

## 3. 연구 목적

본 연구는 JWT Access Token의 즉각적인 폐기를 위해 Redis 기반 Denylist를
사용하는 인증 시스템에서 Redis 장애와 Redis-MySQL 간 폐기 상태 불일치가
인증 결과에 미치는 영향을 분석하고, 서로 다른 저장 및 장애 대응 전략을
적용했을 때 나타나는 **보안성, 가용성 및 성능의 차이를 실험적으로
비교**하는 것을 목적으로 한다.

본 연구는 새로운 JWT 또는 암호 알고리즘을 제안하는 것이 아니라, 통제된
실험 환경에서 기존 인증 및 장애 대응 전략의 trade-off를 정량적으로
비교하는 실증 연구를 지향한다.

## 4. 기본 시스템 설계

-   **Source of Truth:** MySQL
-   **빠른 폐기 검증 저장소:** Redis
-   **폐기 식별자:** JWT의 `jti`
-   **로그아웃 저장 순서:** MySQL → Redis
-   **Redis Key:** `revoked:{jti}`
-   **Redis TTL:** 해당 Access Token의 남은 유효시간

MySQL에 폐기 정보가 존재한다는 것 자체를 해당 `jti`가 폐기되었다는
의미로 정의한다.

## 5. 비교 전략

### S1. Fail-Open

Redis가 정상일 때는 Redis Denylist를 조회한다. Redis 연결에 실패하면
JWT의 서명과 만료시간이 유효한 요청을 허용한다.

-   장점: Redis 장애 중에도 정상 사용자의 요청을 계속 처리할 수 있다.
-   위험: 이미 폐기된 토큰도 허용될 수 있다.

### S2. Fail-Closed

Redis가 정상일 때는 Redis Denylist를 조회한다. Redis 연결에 실패하면
폐기 여부를 확인할 수 없다고 판단하여 요청을 거부한다.

-   장점: Redis 장애로 인해 폐기 토큰이 허용되는 것을 방지한다.
-   위험: 정상 토큰의 요청까지 거부되어 가용성이 낮아질 수 있다.

### S3. DB Fallback

Redis가 정상일 때는 Redis Denylist를 사용하고, Redis 연결 자체가
실패했을 때 MySQL에서 `jti`의 폐기 여부를 조회한다.

-   장점: Redis 장애 상황에서도 정상 토큰과 폐기 토큰을 구분할 수 있다.
-   한계: Redis가 정상적으로 `MISS`를 반환하는 데이터 불일치 상황에서는
    MySQL 조회가 발생하지 않는다.

### S4. DB Verification

Redis에서 폐기 정보가 확인되면 즉시 요청을 거부한다. Redis가 `MISS`를
반환하거나 Redis 연결에 실패하면 MySQL에서 폐기 여부를 추가 확인한다.

-   장점: `MySQL=revoked`, `Redis=MISS`인 불일치 상태에서도 폐기 토큰을
    탐지할 수 있다.
-   한계: 정상 토큰도 Redis에서 대부분 `MISS`가 발생하므로 DB 조회가
    증가하여 성능 비용이 발생할 수 있다.

### 성능 Baseline. Stateless JWT

RQ1의 성능 비교를 위해 폐기 저장소를 조회하지 않고 JWT 서명과 만료시간만
검증하는 Stateless JWT 방식을 별도의 기준선으로 사용한다.

## 6. 연구 질문

### RQ1

**Redis Denylist를 이용한 JWT 폐기 검증은 Stateless JWT 인증과 비교하여
인증 처리 성능에 어떠한 영향을 미치는가?**

### RQ2

**Redis 장애 상황에서 Fail-Open, Fail-Closed 및 DB Fallback 전략은 인증
시스템의 보안성과 가용성에 어떠한 차이를 발생시키는가?**

### RQ3

**Redis와 MySQL 간 JWT 폐기 정보가 불일치하는 상황에서 DB Verification
전략은 기존 장애 대응 전략보다 폐기 토큰의 오허용을 효과적으로
감소시키는가?**

### RQ4

**DB Verification을 통한 폐기 상태 검증 강화는 정상 및 고부하 환경에서
어느 정도의 성능 비용을 발생시키는가?**

## 7. 연구 가설

### H1

Redis Denylist를 사용하는 JWT 인증은 Stateless JWT 인증보다 응답시간이
증가하고 처리량이 감소할 것이다.

### H2-1

Redis 장애 상황에서 Fail-Open 전략은 정상 토큰의 높은 요청 성공률을
유지하지만, 폐기 토큰의 오허용률도 증가할 것이다.

### H2-2

Redis 장애 상황에서 Fail-Closed 전략은 폐기 토큰의 접근을 차단하지만
정상 토큰의 요청 성공률을 감소시킬 것이다.

### H2-3

Redis 장애 상황에서 DB Fallback 전략은 Fail-Open보다 낮은 폐기 토큰
오허용률을, Fail-Closed보다 높은 정상 토큰 요청 성공률을 보일 것이다.

### H3

Redis와 MySQL의 폐기 정보가 불일치하는 상황에서 DB Verification 전략은
Fail-Open, Fail-Closed 및 DB Fallback 전략보다 낮은 폐기 토큰 오허용률을
보일 것이다.

### H4

DB Verification 전략은 Redis `MISS` 시 추가적인 DB 조회가 발생하므로
Redis 중심의 검증 전략보다 응답시간이 증가하고 처리량이 감소할 것이다.

## 8. 평가 지표

### RTAR (Revoked Token Acceptance Rate)

폐기된 토큰 요청 중 시스템이 잘못 허용한 요청의 비율이다.

`RTAR = 허용된 폐기 토큰 요청 수 / 전체 폐기 토큰 요청 수 × 100`

낮을수록 보안 측면에서 바람직하다.

### VTSR (Valid Token Success Rate)

정상 토큰 요청 중 성공적으로 처리된 요청의 비율이다.

`VTSR = 성공한 정상 토큰 요청 수 / 전체 정상 토큰 요청 수 × 100`

높을수록 가용성 측면에서 바람직하다.

### 성능 지표

-   Average Latency
-   P95 Latency
-   Throughput
-   필요 시 Error Rate 및 DB Query Count

## 9. 변수

### 독립변수

-   인증/폐기 검증 전략: Stateless, S1, S2, S3, S4
-   시스템 상태: Normal, Redis Failure, Redis-MySQL Inconsistency
-   부하 수준: 예비실험 후 Low / Medium / High 또는 구체적인 VU로 결정

### 종속변수

-   RTAR
-   VTSR
-   Average Latency
-   P95 Latency
-   Throughput
-   필요 시 Error Rate 및 DB Query Count

### 통제변수

-   동일한 하드웨어 및 실행 환경
-   동일한 Spring Boot 애플리케이션
-   동일한 JVM 및 주요 설정
-   동일한 Redis/MySQL 환경
-   동일한 JWT 알고리즘 및 토큰 구조
-   동일한 Access Token 만료시간
-   동일한 보호 API
-   동일한 요청 데이터와 부하 조건
-   동일한 네트워크 환경

## 10. 연구 범위

본 연구는 Access Token의 폐기 검증에 집중한다. 다음 항목은 연구 범위에서
제외한다.

-   새로운 암호 알고리즘 또는 JWT 규격 제안
-   Refresh Token 아키텍처 비교
-   Redis Cluster
-   Kubernetes
-   Kafka 기반 복구
-   Transactional Outbox
-   분산 트랜잭션
-   복잡한 마이크로서비스 구조
-   대규모 실제 운영 환경 재현

## 11. 현재 확정 및 미확정 사항

### 확정

-   MySQL을 Source of Truth로 사용
-   Redis를 빠른 폐기 검증 저장소로 사용
-   `jti` 단위 Access Token 폐기
-   MySQL → Redis 저장 순서
-   S1\~S4 비교 전략
-   Stateless JWT 성능 Baseline
-   RQ1\~RQ4 및 H1\~H4
-   RTAR, VTSR 및 성능 지표
-   Redis 장애와 Redis-MySQL 상태 불일치를 별개의 실패 상태로 구분

### 예비실험 후 결정

-   구체적인 VU 및 부하 수준
-   요청 지속시간/총 요청 수
-   본실험 반복 횟수
-   통계 분석 방법과 신뢰구간 제시 방식
