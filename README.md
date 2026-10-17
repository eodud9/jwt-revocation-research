# JWT Revocation Research

Redis 기반 JWT 폐기 검증 시스템에서 데이터 일관성 및 장애 대응 전략을 비교하기 위한 실험 프로젝트입니다.

JWT Access Token을 즉시 폐기하기 위해 Redis Denylist를 사용하는 환경에서 다음 두 가지 문제에 초점을 맞춥니다.

- Redis 장애로 인해 폐기 여부를 조회할 수 없는 상황
- Redis는 정상 동작하지만 MySQL과 Redis의 폐기 상태가 불일치하는 상황

각 상황에서 서로 다른 폐기 검증 전략을 적용하고 보안성, 가용성 및 성능의 차이를 실험적으로 비교합니다.

---

## Research Questions

본 연구에서는 다음 네 가지 연구 질문을 다룹니다.

**RQ1.** Redis Denylist를 이용한 JWT 폐기 검증은 Stateless JWT 인증과 비교하여 인증 처리 성능에 어떠한 영향을 미치는가?

**RQ2.** Redis 장애 상황에서 Fail-Open, Fail-Closed 및 DB Fallback 전략은 인증 시스템의 보안성과 가용성에 어떠한 차이를 발생시키는가?

**RQ3.** Redis와 MySQL 간 JWT 폐기 정보가 불일치하는 상황에서 DB Verification 전략은 기존 장애 대응 전략보다 폐기 토큰의 오허용을 효과적으로 감소시키는가?

**RQ4.** DB Verification을 통한 폐기 상태 검증 강화는 정상 및 고부하 환경에서 어느 정도의 성능 비용을 발생시키는가?

---

## Revocation Strategies

### Stateless Baseline

JWT의 서명과 만료시간만 검증하며 Redis와 MySQL의 폐기 정보를 조회하지 않습니다.

### S1. Fail-Open

Redis가 정상일 때 Denylist를 조회하고, Redis 접근에 실패하면 요청을 허용합니다.

### S2. Fail-Closed

Redis가 정상일 때 Denylist를 조회하고, Redis 접근에 실패하면 요청을 거부합니다.

### S3. DB Fallback

Redis 접근에 실패한 경우 MySQL에서 `jti`의 폐기 여부를 확인합니다.

```text
Redis HIT     → Reject
Redis MISS    → Allow
Redis Failure → MySQL Verification
```

### S4. DB Verification

Redis가 `MISS`를 반환하거나 Redis 접근에 실패하면 MySQL에서 폐기 여부를 추가 확인합니다.

```text
Redis HIT     → Reject
Redis MISS    → MySQL Verification
Redis Failure → MySQL Verification
```

---

## Architecture

```text
Client / k6
      |
      v
Spring Boot
      |
      v
Spring Security / JWT Filter
      |
      v
RevocationStrategy
   |          |
   v          v
 Redis      MySQL
              |
              v
      Source of Truth
```

MySQL을 JWT 폐기 상태의 **Source of Truth**로 사용하고, Redis를 빠른 폐기 검증을 위한 보조 저장소로 사용합니다.

로그아웃 시 폐기 정보는 다음 순서로 저장합니다.

```text
MySQL → Redis
```

Redis에는 다음 형식으로 폐기된 Access Token의 `jti`를 저장합니다.

```text
Key   : revoked:{jti}
Value : "1"
TTL   : Access Token의 남은 유효시간
```

---

## Experiments

| Experiment | System State | Comparison | Main Metrics |
|---|---|---|---|
| E1 | Normal | Stateless vs Redis Denylist | Avg, P95, Throughput |
| E2 | Redis Failure | S1 vs S2 vs S3 | RTAR, VTSR |
| E3 | Redis-MySQL Inconsistency | S1 vs S2 vs S3 vs S4 | RTAR, DB Query Count |
| E4 | Normal / Load Increase | S3 vs S4 | Avg, P95, Throughput, DB Query Count |

### Performance Test Configuration

E1과 E4는 다음 조건으로 성능을 측정합니다.

```text
Load       : 20 / 50 / 100 VU
Duration   : 30 seconds per run
Repetition : 5 runs per condition
Warm-up    : 5 VU / 20 seconds
Rest       : 30 seconds
```

E2와 E3는 성능 부하 테스트가 아니라 장애 및 불일치 상황에서의 인증 결과를 측정합니다.

```text
E2 : Valid Token 100 requests
     Revoked Token 100 requests

E3 : Revoked Token 100 requests
```

---

## Metrics

### RTAR

Revoked Token Acceptance Rate

```text
RTAR =
Allowed Revoked Token Requests
────────────────────────────── × 100
Total Revoked Token Requests
```

낮을수록 폐기 토큰의 오허용이 적음을 의미합니다.

### VTSR

Valid Token Success Rate

```text
VTSR =
Successful Valid Token Requests
─────────────────────────────── × 100
Total Valid Token Requests
```

높을수록 정상 사용자의 요청을 안정적으로 처리함을 의미합니다.

성능 비교에는 다음 지표를 사용합니다.

- Average Latency
- P95 Latency
- Throughput
- Error Rate
- DB Query Count

---

## Tech Stack

- Java 21
- Spring Boot 4.1.1
- Spring Security
- JWT
- Spring Data JPA
- MySQL
- Redis
- Docker / Docker Compose
- k6

---

## Project Structure

```text
src/main/java/com/daeyeong/jwtrevocation/
├── auth/
├── security/
├── revocation/
│   └── strategy/
├── user/
└── api/

experiments/k6/
├── warm-up.js
├── e1-performance.js
├── run-e1-stateless.sh
├── run-e1-redis.sh
├── e2-availability.js
├── run-e2.sh
├── e3-consistency.js
├── run-e3.sh
├── e4-performance.js
├── run-e4-s3.sh
├── run-e4-s4.sh
└── results/

docs/
├── research-design.md
├── experiment-design.md
├── experiment-checklist.md
└── architecture.md
```

---

## Documentation

연구 및 실험에 대한 자세한 내용은 `docs/`에서 확인할 수 있습니다.

- `research-design.md` — 연구 목적, 연구 질문, 가설 및 평가 지표
- `architecture.md` — 시스템 구조 및 폐기 검증 전략
- `experiment-design.md` — E1~E4 실험 설계
- `experiment-checklist.md` — 본 실험 실행 절차 및 체크리스트

---

## Research Scope

본 연구는 JWT **Access Token의 폐기 검증**에 집중합니다.

다음 항목은 연구 범위에서 제외합니다.

- 새로운 JWT 또는 암호 알고리즘 제안
- Refresh Token 아키텍처 비교
- Redis Cluster
- Kubernetes
- Kafka 기반 복구
- Transactional Outbox
- 분산 트랜잭션
- 대규모 실제 운영 환경 재현