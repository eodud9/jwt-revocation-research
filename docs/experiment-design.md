# 실험 설계 (Experiment Design)

## 1. 실험 목적

본 문서는 연구 질문 RQ1\~RQ4와 가설 H1\~H4를 검증하기 위한 실험
시나리오를 정의한다. 모든 실험은 비교 전략 외의 조건을 가능한 한
동일하게 유지한다.

## 2. 실험 구성

  실험   검증 대상   시스템 상태          주요 비교
  ------ ----------- -------------------- ---------------------------------
  E1     H1          정상                 Stateless JWT vs Redis Denylist
  E2     H2          Redis 장애           S1 vs S2 vs S3
  E3     H3          Redis-MySQL 불일치   S1 vs S2 vs S3 vs S4
  E4     H4          정상 + 부하 증가     S3 vs S4

## 3. E1 --- Redis Denylist 기본 성능 비용

### 목적

Redis Denylist 조회가 Stateless JWT 인증에 추가하는 성능 비용을
측정한다.

### 비교 조건

-   E1-A: Stateless JWT
-   E1-B: JWT + Redis Denylist

모든 요청에는 정상 Access Token을 사용한다.

### 처리 흐름

**Stateless JWT**

`Request → JWT 서명/만료 검증 → Protected API`

**Redis Denylist**

`Request → JWT 서명/만료 검증 → Redis 조회(MISS) → Protected API`

### 측정 지표

-   Average Latency
-   P95 Latency
-   Throughput

## 4. E2 --- Redis 장애 대응

### 목적

Redis에 연결할 수 없는 상황에서 S1 Fail-Open, S2 Fail-Closed, S3 DB
Fallback의 보안성과 가용성을 비교한다.

### 사전 상태

정상 토큰: - MySQL: 폐기 정보 없음 - Redis: 폐기 정보 없음

폐기 토큰: - MySQL: revoked - Redis: revoked

장애 발생 전 MySQL과 Redis의 폐기 상태를 일치시킨다.

### 장애 주입

Docker 환경에서 Redis 컨테이너를 중단하여 Redis Connection Failure를
재현한다.

예시:

`docker compose stop redis`

MySQL과 Spring Boot 애플리케이션은 정상 상태를 유지한다.

### 전략별 예상

**S1 Fail-Open** - 정상 토큰: 허용 예상 - 폐기 토큰: 허용 가능 - 예상:
VTSR 높음 / RTAR 높음

**S2 Fail-Closed** - 정상 토큰: 거부 예상 - 폐기 토큰: 거부 예상 - 예상:
VTSR 낮음 / RTAR 낮음

**S3 DB Fallback** - Redis 실패 후 MySQL 조회 - 정상 토큰: DB에 폐기
정보 없음 → 허용 - 폐기 토큰: DB에 폐기 정보 존재 → 거부 - 예상: VTSR
높음 / RTAR 낮음

### 측정 지표

-   RTAR
-   VTSR
-   P95 Latency
-   필요 시 Average Latency / Throughput

## 5. E3 --- Redis-MySQL 데이터 불일치

### 목적

Redis가 정상 작동하지만 폐기 정보가 MySQL과 불일치할 때 각 전략의 인증
정확성을 비교한다.

### 불일치 상태

특정 폐기 토큰 `ABC`에 대해 다음 상태를 구성한다.

-   MySQL: `ABC = revoked`
-   Redis: `revoked:ABC = MISS`
-   Redis 서버 자체는 정상 작동

### 불일치 재현 방법

1.  정상적으로 토큰 `ABC`를 로그아웃하여 MySQL과 Redis에 폐기 정보를
    저장한다.
2.  실험 직전에 Redis의 `revoked:ABC` 키만 제거한다.
3.  MySQL의 폐기 기록은 유지한다.

이는 **DB 저장 성공 후 Redis 반영 실패로 발생할 수 있는 폐기 상태
불일치를 모사**하기 위한 통제된 실험이다. 모든 종류의 데이터 불일치를
일반화하지 않는다.

### 전략별 예상

  전략                 Redis 응답   추가 DB 확인   폐기 토큰 ABC 예상 결과
  -------------------- ------------ -------------- -------------------------
  S1 Fail-Open         MISS         없음           허용
  S2 Fail-Closed       MISS         없음           허용
  S3 DB Fallback       MISS         없음           허용
  S4 DB Verification   MISS         있음           거부

Fail-Closed는 Redis 연결 실패에 대한 정책이므로 Redis가 정상적으로
`MISS`를 반환하면 요청을 차단하지 않는다. DB Fallback 역시 연결 실패가
없으므로 실행되지 않는다.

### 핵심 지표

-   RTAR

필요 시 응답시간도 함께 기록한다.

## 6. E4 --- DB Verification 성능 비용

### 목적

데이터 일관성 검증을 강화하는 S4가 정상 및 고부하 환경에서 발생시키는
성능 비용을 측정한다.

### 비교

-   S3 DB Fallback
-   S4 DB Verification

Redis와 MySQL 모두 정상 상태로 유지한다.

### 정상 토큰 처리 차이

**S3**

`JWT → Redis → MISS → 허용`

**S4**

`JWT → Redis → MISS → MySQL → MISS → 허용`

따라서 S4는 정상 토큰에서도 추가적인 DB 조회가 발생한다.

### 부하 수준

Low / Medium / High의 3단계 구조를 사용하되 구체적인 VU, 요청 수,
지속시간은 구현 후 예비실험 결과에 따라 결정한다.

### 측정 지표

-   Average Latency
-   P95 Latency
-   Throughput
-   Error Rate
-   가능하면 DB Query Count

## 7. Warm-up

JVM/JIT, Connection Pool 등의 초기화 효과가 본 측정에 미치는 영향을
줄이기 위해 본실험 전에 동일한 Warm-up Traffic을 적용한다.

`Application Start → Warm-up(측정 제외) → Measurement(측정 포함)`

모든 전략과 반복 실험에서 동일한 Warm-up 조건을 사용한다.

## 8. 반복 실험

각 조건은 한 번만 측정하지 않고 여러 차례 독립적으로 반복한다. 정확한
반복 횟수는 예비실험에서 결과 변동성을 확인한 후 결정한다.

최종 분석에서는 단일 측정값보다 반복 측정 결과를 사용하고, 필요에 따라
평균, 표준편차 및 신뢰구간을 제시한다.

## 9. 실행 순서 통제

항상 S1 → S2 → S3 → S4의 동일한 순서로 실행하여 시간 경과에 따른 환경
변화가 특정 전략에 편향되지 않도록, 반복 실험에서는 전략 실행 순서를
변경하거나 무작위화하는 방식을 고려한다.

## 10. 결과 기록 템플릿

### E1

  Strategy           Avg Latency   P95 Latency   Throughput
  ---------------- ------------- ------------- ------------
  Stateless                                    
  Redis Denylist                               

### E2

  Strategy        RTAR   VTSR   P95 Latency
  ------------- ------ ------ -------------
  Fail-Open                   
  Fail-Closed                 
  DB Fallback                 

### E3

  Strategy            RTAR
  ----------------- ------
  Fail-Open         
  Fail-Closed       
  DB Fallback       
  DB Verification   

### E4

  ------------------------------------------------------------------------
  Strategy   Load       Avg Latency  P95 Latency   Throughput   Error Rate
  ---------- --------- ------------ ------------ ------------ ------------
  S3         Low                                              

  S3         Medium                                           

  S3         High                                             

  S4         Low                                              

  S4         Medium                                           

  S4         High                                             
  ------------------------------------------------------------------------

## 11. 실험 전 최종 결정 사항

예비실험 후 다음 항목을 고정한다.

-   Low / Medium / High 부하의 구체적인 VU
-   각 실험의 요청 지속시간 또는 총 요청 수
-   Warm-up 지속시간
-   반복 횟수
-   통계 분석 방법
-   실험 환경의 하드웨어/소프트웨어 버전 기록 방식
