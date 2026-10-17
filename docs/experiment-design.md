# 실험 설계 (Experiment Design)

## 1. 실험 목적

본 문서는 연구 질문 RQ1\~RQ4와 가설 H1\~H4를 검증하기 위한 최종 실험
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
-   E1-B: JWT + Redis Denylist(S1 Fail-Open, Redis 정상 상태)

모든 요청에는 정상 Access Token을 사용한다. E1-B에서는 Redis가 정상
상태이므로 Fail-Open의 장애 처리 경로는 실행되지 않으며, 정상적인 Redis
Denylist 조회 비용을 측정한다.

### 처리 흐름

**Stateless JWT**

`Request → JWT 서명/만료 검증 → Protected API`

**Redis Denylist**

`Request → JWT 서명/만료 검증 → Redis 조회(MISS) → Protected API`

### 부하 및 반복 조건

-   부하: 20 / 50 / 100 VU
-   측정 시간: 각 Run 30초
-   반복: 각 조건 5회
-   Warm-up: 5 VU / 20초
-   Warm-up 이후 대기: 30초
-   Run 사이 대기: 30초

### 측정 지표

-   Average Latency
-   P95 Latency
-   Throughput
-   Error Rate

## 4. E2 --- Redis 장애 대응

### 목적

Redis에 연결할 수 없는 상황에서 S1 Fail-Open, S2 Fail-Closed, S3 DB
Fallback의 보안성과 가용성을 비교한다.

### 사전 상태

정상 토큰:

-   MySQL: 폐기 정보 없음
-   Redis: 폐기 정보 없음

폐기 토큰:

-   MySQL: revoked
-   Redis: revoked

장애 발생 전 MySQL과 Redis의 폐기 상태를 일치시킨다.

### 장애 주입

Docker 환경에서 Redis 컨테이너를 중단하여 Redis Connection Failure를
재현한다.

`docker compose stop redis`

MySQL과 Spring Boot 애플리케이션은 정상 상태를 유지한다.

### 요청 조건

-   1 VU
-   100 iterations
-   각 iteration에서 Valid Token 요청 1회 + Revoked Token 요청 1회
-   총 Valid Token 100회, Revoked Token 100회
-   성능 부하 실험이 아니므로 별도의 Warm-up을 적용하지 않는다.

### 전략별 예상

**S1 Fail-Open**

-   정상 토큰: 허용 예상
-   폐기 토큰: 허용 예상
-   예상: VTSR 높음 / RTAR 높음

**S2 Fail-Closed**

-   정상 토큰: 거부 예상
-   폐기 토큰: 거부 예상
-   예상: VTSR 낮음 / RTAR 낮음

**S3 DB Fallback**

-   Redis 실패 후 MySQL 조회
-   정상 토큰: DB에 폐기 정보 없음 → 허용
-   폐기 토큰: DB에 폐기 정보 존재 → 거부
-   예상: VTSR 높음 / RTAR 낮음

### 측정 지표

-   RTAR
-   VTSR

E2의 목적은 장애 상황에서의 인증 판단 비교이므로 응답시간과 처리량은
핵심 평가 지표로 사용하지 않는다.

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
2.  실험 직전에 Redis의 폐기 정보를 제거한다.
3.  MySQL의 폐기 기록은 유지한다.
4.  Redis 서버는 계속 정상 상태로 유지한다.

이는 **DB 저장 성공 후 Redis 반영 실패로 발생할 수 있는 폐기 상태
불일치를 모사**하기 위한 통제된 실험이다. 모든 종류의 데이터 불일치를
일반화하지 않는다.

실제 최종 실험에서 특정 키 삭제 또는 연구 전용 Redis DB의 `FLUSHDB` 중
사용한 방법을 실험 기록에 명시한다.

### 요청 조건

-   1 VU
-   100 iterations
-   Revoked Token 100회 요청
-   성능 부하 실험이 아니므로 별도의 Warm-up을 적용하지 않는다.

### 전략별 예상

  전략                 Redis 응답   추가 DB 확인   폐기 토큰 ABC 예상 결과
  -------------------- ------------ -------------- -------------------------
  S1 Fail-Open         MISS         없음           허용
  S2 Fail-Closed       MISS         없음           허용
  S3 DB Fallback       MISS         없음           허용
  S4 DB Verification   MISS         있음           거부

Fail-Closed는 Redis 연결 실패에 대한 정책이므로 Redis가 정상적으로
`MISS`를 반환하면 요청을 차단하지 않는다. DB Fallback 역시 연결 실패가
없으므로 DB Fallback 경로가 실행되지 않는다.

### 측정 지표

-   RTAR
-   DB Query Count

DB Query Count는 전략별 실행 경로가 의도대로 동작했는지 확인하는 보조
지표로 사용한다.

## 6. E4 --- DB Verification 성능 비용

### 목적

데이터 일관성 검증을 강화하는 S4가 정상 및 부하 증가 환경에서 발생시키는
성능 비용을 측정한다.

### 비교

-   S3 DB Fallback
-   S4 DB Verification

Redis와 MySQL 모두 정상 상태로 유지하며 정상 Access Token을 사용한다.

### 정상 토큰 처리 차이

**S3**

`JWT → Redis → MISS → 허용`

**S4**

`JWT → Redis → MISS → MySQL → MISS → 허용`

따라서 S4는 정상 토큰에서도 추가적인 DB 조회가 발생한다.

### 부하 및 반복 조건

-   Low: 20 VU
-   Medium: 50 VU
-   High: 100 VU
-   측정 시간: 각 Run 30초
-   반복: 각 조건 5회
-   Warm-up: 5 VU / 20초
-   Warm-up 이후 대기: 30초
-   Run 사이 대기: 30초

### 측정 지표

-   Average Latency
-   P95 Latency
-   Throughput
-   Error Rate
-   DB Query Count

정상 Redis 상태에서 S3는 Redis MISS 시 DB를 조회하지 않으므로 DB Query
Count가 0이어야 한다. S4는 Redis MISS 시 MySQL을 확인하므로 DB Query
Count가 요청 수와 유사하게 증가할 것으로 예상한다.

## 7. Warm-up

JVM/JIT, Connection Pool 등의 초기화 효과가 성능 측정에 미치는 영향을
줄이기 위해 **E1과 E4의 성능 실험에만** 동일한 Warm-up Traffic을
적용한다.

`Application Start → Warm-up 5 VU / 20초(측정 제외) → 30초 대기 → Measurement`

E2와 E3는 장애 및 불일치 상태에서의 인증 결과를 측정하는 실험이므로
Warm-up을 적용하지 않는다.

## 8. 반복 실험 및 통계 처리

E1과 E4의 각 조건은 5회 반복 측정한다. 최종 분석에서는 단일 측정값이
아니라 반복 결과를 사용한다.

기본적으로 다음 값을 제시한다.

-   평균
-   표준편차
-   필요 시 95% 신뢰구간

E2와 E3는 정해진 상태에서 100회의 인증 요청 결과를 바탕으로 RTAR/VTSR을
산출한다.

## 9. 실행 환경 및 순서 통제

-   동일한 하드웨어와 소프트웨어 환경을 유지한다.
-   E1/E4 성능 측정 중에는 코드 수정, 브라우저 작업 등 불필요한 시스템
    부하를 최소화한다.
-   각 비교 실험 내부의 조건은 가능한 한 동일한 세션과 환경에서 연속
    측정한다.
-   전략 변경 시 Spring Boot를 재시작하고 설정값을 확인한다.
-   Access Token 만료가 측정에 개입하지 않도록 E1/E4에서는 필요 시 부하
    수준별로 Fresh Token을 발급한다.
-   E2/E3에서는 토큰의 폐기 상태 자체가 실험 조건이므로 실험 도중 토큰을
    임의로 교체하지 않는다.
-   Pilot 결과와 Final 결과는 분리하여 관리한다.

## 10. 결과 기록 템플릿

### E1

  ----------------------------------------------------------------------------
  Strategy            Load  Avg Latency  P95 Latency   Throughput   Error Rate
  ----------- ------------ ------------ ------------ ------------ ------------
  Stateless             20                                        

  Stateless             50                                        

  Stateless            100                                        

  Redis                 20                                        
  Denylist                                                        

  Redis                 50                                        
  Denylist                                                        

  Redis                100                                        
  Denylist                                                        
  ----------------------------------------------------------------------------

### E2

  Strategy        RTAR   VTSR
  ------------- ------ ------
  Fail-Open            
  Fail-Closed          
  DB Fallback          

### E3

  Strategy            RTAR   DB Query Count
  ----------------- ------ ----------------
  Fail-Open                
  Fail-Closed              
  DB Fallback              
  DB Verification          

### E4

  ------------------------------------------------------------------------------
  Strategy         Load        Avg        P95   Throughput Error Rate   DB Query
                           Latency    Latency                              Count
  ---------- ---------- ---------- ---------- ------------ ---------- ----------
  S3                 20                                               

  S3                 50                                               

  S3                100                                               

  S4                 20                                               

  S4                 50                                               

  S4                100                                               
  ------------------------------------------------------------------------------

## 11. 최종 실험 조건 요약

  항목              최종 조건
  ----------------- ------------------------------------------------
  E1/E4 부하        20 / 50 / 100 VU
  E1/E4 측정 시간   Run당 30초
  E1/E4 반복 횟수   조건별 5회
  E1/E4 Warm-up     5 VU / 20초
  Warm-up 후 대기   30초
  Run 사이 대기     30초
  E2                1 VU / 100 iterations, Valid 100 + Revoked 100
  E3                1 VU / 100 iterations, Revoked 100
  성능 분석         평균, 표준편차, 필요 시 95% 신뢰구간
