# 실험 설계 (Experiment Design)

이 문서는 **사전에 정의한 실험 설계**를 기록한다. 확정된 관측 결과는 [experiment-results.md](experiment-results.md)에 별도로 정리했다.

## 공통 구성

- 서버: Java 21 / Spring Boot 4.1.1, MySQL 8.4, Redis 7.4.
- 부하 도구: k6 2.3.0. 서버와 부하 도구는 동일 로컬 호스트에서 실행.
- 인증: HS256 JWT Access Token(30분), `GET /api/protected`.
- 전략은 `jwt.revocation.strategy`로 전환하며 애플리케이션 재시작 필요.
- E1/E4의 성능 지표는 5회 반복 평균, 표준편차, 평균 지연, P95, 처리량.
- E2/E3는 1 VU·100 iterations, 인증 판단 결과를 측정하며 Warm-up 없음.

## E1 — Redis Denylist의 기본 성능 비용

- **목적:** Stateless JWT와 Redis Denylist 조회의 성능 차이.
- **조건:** Redis/MySQL 정상, 유효한 미폐기 Access Token.
- **비교:** Stateless 기준선 vs S1 Fail-Open의 정상 Redis 조회 경로.
- **부하:** 20/50/100 VU, 각 조건 30초 × 5회, Warm-up 5 VU/20초, Warm-up 후 및 Run 사이 30초 대기.
- **지표:** 평균 응답시간, P95, 처리량, 오류율.

## E2 — Redis 연결 장애 대응

- **목적:** Redis 접근 불가 시 보안성·가용성 비교.
- **사전 조건:** 정상 토큰은 미폐기, 폐기 토큰은 MySQL/Redis에 폐기 정보 저장.
- **장애 주입:** `docker compose stop redis`; MySQL과 Spring Boot는 정상 유지.
- **비교:** S1 Fail-Open, S2 Fail-Closed, S3 DB Fallback.
- **요청:** 전략별 100 iterations, 매 iteration 정상 토큰 1회 + 폐기 토큰 1회.
- **지표:** VTSR, RTAR. Redis 재시작은 모든 전략 종료 후 수행.

## E3 — Redis–MySQL 폐기 정보 불일치

- **목적:** Redis가 정상적으로 `MISS`를 반환할 때 폐기 토큰 탐지 비교.
- **사전 조건:** MySQL에 폐기 기록 존재. Redis에서 해당 `revoked:{jti}` 키를 제거하여 `MISS` 구성.
- **비교:** S1~S4.
- **요청:** 전략별 폐기 토큰 100회.
- **지표:** RTAR, DB Query Count. 각 전략 시작 전 메트릭 초기화.
- **주의:** 이는 키 누락 상태의 통제된 모사이며 실제 저장 실패를 직접 주입한 실험은 아니다.

## E4 — DB Verification의 정상 부하 비용

- **목적:** S3 대비 S4의 추가 DB 검증 비용.
- **조건:** Redis/MySQL 정상, 유효한 미폐기 토큰.
- **비교:** S3 DB Fallback vs S4 DB Verification.
- **부하:** 20/50/100 VU, 각 조건 30초 × 5회, Warm-up 5 VU/20초, 대기 30초.
- **지표:** 평균 응답시간, P95, 처리량, 오류율, DB Query Count.

## 집계 및 해석 원칙

1. E1/E4는 조건별 반복 5회의 산술평균과 표본 표준편차를 사용한다.
2. 처리량 감소율 = `(비교 기준 처리량 - 대상 처리량) / 비교 기준 처리량 × 100`.
3. E2/E3의 k6 `revoked token allowed`는 **HTTP 200이면 체크 성공**이므로, 체크 실패를 곧바로 실험 실패로 해석하지 않는다.
4. E2에서 최초 S3 결과가 예상과 달랐고 재실험을 수행했으며 초기 이상 결과의 원인은 미확정이다.
5. 독립 장애 상태 로그가 결과 JSON에 포함되어 있지 않으므로 실험 상태는 절차 기록과 결과를 구분해 기술한다.
6. 로컬 환경의 성능 결과를 실제 운영환경의 처리량이나 장애 확률로 일반화하지 않는다.

실행 명령과 사전 점검: [experiment-checklist.md](experiment-checklist.md). 확정 결과: [experiment-results.md](experiment-results.md).
