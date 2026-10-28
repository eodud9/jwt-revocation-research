# 시스템 아키텍처 및 구현 제약

## 1. 목적과 범위

본 프로젝트는 상용 인증 플랫폼이 아니라 **JWT 폐기 검증 전략의 비교를 위한 최소 실험 시스템**이다. MySQL을 폐기 정보의 기준 저장소로, Redis를 빠른 조회용 Denylist로 사용한다.

## 2. 구성

```text
Client / k6
    |
    v
Spring Security / JwtAuthenticationFilter
    |
    v
RevocationStrategy
    |-- RedisRevocationStore (revoked:{jti})
    `-- RevokedTokenRepository (MySQL)
    |
    v
GET /api/protected
```

- Java 21, Spring Boot 4.1.1, Spring Security, JPA, MySQL 8.4, Redis 7.4
- Access Token: HS256, UUID `jti`, 30분 만료
- Redis 키: `revoked:{jti}`, 값: `1`, TTL: Access Token 남은 유효기간
- MySQL `revoked_tokens`: `jti` 존재 여부를 폐기 상태로 해석

## 3. API

| 메서드 | 경로 | 용도 |
|---|---|---|
| POST | `/auth/login` | 이메일·비밀번호 로그인 및 Access Token 발급 |
| POST | `/auth/logout` | Bearer 토큰의 `jti` 폐기 기록 |
| GET | `/api/protected` | 인증·폐기 전략 검증용 보호 API |
| POST | `/api/experiment/metrics/reset` | DB 조회 횟수 초기화 |
| GET | `/api/experiment/metrics` | DB 조회 횟수 조회 |

실험 메트릭 API는 성능 측정 대상인 보호 API와 구분한다. JWT 필터는 폐기 토큰의 인증 객체를 설정하지 않고 다음 필터로 전달하며, 최종 접근 거부는 Spring Security에 의해 처리된다. E2/E3는 HTTP 200을 허용, 그 외 상태를 비허용으로 분류한다.

## 4. 로그아웃 저장 흐름과 한계

```text
POST /auth/logout
  -> JWT 파싱 및 jti/exp 추출
  -> MySQL existsByJti(jti)
      -> 존재하면 즉시 반환
      -> 없으면 MySQL INSERT
  -> 잔여 유효시간 > 0 이면 Redis SET + TTL
```

**구현상 제약:** MySQL 저장과 Redis 저장은 하나의 원자적 트랜잭션으로 묶여 있지 않다. MySQL 저장 성공 후 Redis 저장이 실패하면 `MySQL=revoked`, `Redis=MISS`가 발생할 수 있다. 또한 이미 MySQL에 `jti`가 있으면 `AuthService.logout()`이 조기 반환하므로, 같은 토큰으로 로그아웃을 재호출해도 Redis 키가 자동 복구되지 않는다. 자동 재시도·보상 트랜잭션·재동기화 작업은 구현하지 않았다.

## 5. 폐기 검증 전략

| 전략 | Redis HIT | Redis MISS | Redis `DataAccessException` |
|---|---|---|---|
| Stateless | Redis 조회 없음 | 해당 없음 | 해당 없음 |
| S1 Fail-Open | 폐기 | 허용 | 허용 |
| S2 Fail-Closed | 폐기 | 허용 | 폐기 |
| S3 DB Fallback | 폐기 | 허용 | MySQL `existsByJti(jti)` |
| S4 DB Verification | 폐기 | MySQL `existsByJti(jti)` | MySQL `existsByJti(jti)` |

전략은 `jwt.revocation.strategy` 설정값으로 선택하며 변경 후 Spring Boot를 재시작한다. 지원 값: `stateless`, `fail-open`, `fail-closed`, `db-fallback`, `db-verification`.

DB 조회 횟수는 연구용 `RevocationMetrics`의 `AtomicLong`으로 기록한다. 이는 해당 전략의 DB 조회 코드 경로를 계측한 값이며 DB 서버 전체 쿼리 수나 분산 모니터링 지표를 의미하지 않는다.

## 6. 장애 및 불일치 재현

### E2 — Redis 연결 실패

```bash
docker compose stop redis
# Redis DOWN, MySQL 및 Spring Boot는 정상 상태 유지
# 각 전략의 k6 실행

docker compose start redis
```

E2는 컨테이너 중단에 따른 Redis 접근 실패를 다룬다. 모든 네트워크 장애 또는 Redis 내부 오류를 검증한 것은 아니다.

### E3 — Redis–MySQL 불일치

1. 폐기 토큰으로 로그아웃하여 MySQL과 Redis에 `jti` 기록을 생성한다.
2. MySQL의 폐기 기록은 유지하고 Redis의 **해당 `revoked:{jti}` 키만 제거**한다.
3. Redis 컨테이너는 정상 동작하는 상태를 유지한다.
4. 각 전략에서 동일한 폐기 토큰으로 100회 요청한다.

```bash
# 예시: 실제 jti로 교체해야 함. 운영 Redis에서 실행 금지.
redis-cli -p 6380 DEL 'revoked:YOUR_JTI'
redis-cli -p 6380 EXISTS 'revoked:YOUR_JTI' # 0이어야 함
```

키 누락을 만드는 실험이며, 실제 로그아웃 과정에서 Redis 저장 실패를 자동으로 발생시킨 실험은 아니다. 키 삭제 명령의 독립 실행 로그는 결과 JSON에 포함되어 있지 않다. 연구 전용 Redis DB 전체를 삭제하는 `FLUSHDB`는 불필요한 다른 키까지 삭제할 수 있으므로 기본 재현 절차로 권장하지 않는다.

## 7. 실행 및 측정

- E1/E4: 20/50/100 VU, 30초, 각 조건 5회 반복, Warm-up 5 VU/20초
- E2: 정상 토큰 100회 + 폐기 토큰 100회
- E3: 폐기 토큰 100회, DB Query Count 계측
- `experiments/k6/run-e3.sh`는 실험 시작 전 DB 조회 메트릭을 초기화하고 종료 후 저장한다.

실험 결과: [experiment-results.md](experiment-results.md). 실행 순서: [experiment-checklist.md](experiment-checklist.md).

## 8. 비적용 범위

Redis Cluster, 자동 복구·재동기화, Outbox, Kafka, 분산 트랜잭션, Refresh Token 비교, 다중 호스트 운영환경은 본 연구 범위에 포함되지 않는다.
