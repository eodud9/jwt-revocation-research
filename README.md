# JWT Revocation Research

**Redis 기반 JWT 폐기 검증에서 장애 대응과 데이터 일관성의 트레이드오프를 비교한 실험 연구**

JWT Access Token을 만료 전에 폐기하기 위해 MySQL을 폐기 정보의 기준 저장소(Source of Truth), Redis를 조회용 Denylist로 사용하는 최소 Spring Boot 시스템을 구현했습니다. **Redis 연결 장애(E2)**와 **Redis가 정상 동작하지만 폐기 키가 누락된 불일치(E3)**를 구분하고, 네 가지 전략의 인증 판단 및 성능 비용을 비교했습니다.

> 연구 범위: JWT Access Token 폐기 검증. 새로운 암호 알고리즘, 분산 트랜잭션, 자동 복구 프로토콜을 제안하는 프로젝트는 아닙니다.

## 핵심 결과

| 실험 | 조건 | 주요 관찰 |
|---|---|---|
| E1 | Stateless vs Redis Denylist, 20/50/100 VU | Redis 조회 조건의 처리량이 각각 **56.3% / 48.6% / 45.0% 감소** |
| E2 | Redis 연결 중단, 정상·폐기 토큰 각 100회 | Fail-Open: VTSR 100%, RTAR 100%; Fail-Closed: 0%, 0%; DB Fallback: 100%, 0% |
| E3 | MySQL 폐기 기록 유지, Redis 키 누락, 폐기 토큰 100회 | S1~S3 RTAR 100%, S4 DB Verification RTAR 0%; S4 DB 조회 100회 |
| E4 | DB Fallback vs DB Verification, 20/50/100 VU | S4 처리량이 각각 **59.5% / 63.4% / 62.2% 감소** |

**해석:** Redis가 응답하지 않는 장애와 Redis가 정상적으로 `MISS`를 반환하는 데이터 불일치는 서로 다른 문제입니다. DB Fallback은 전자에 대응하지만 후자를 검출하지 못합니다. DB Verification은 후자를 검출하는 대신 정상 요청에서도 DB 조회 비용을 발생시킵니다. 수치는 단일 로컬 실험환경에서의 관찰값이며 운영환경 성능을 일반화하지 않습니다.

자세한 수치와 실험 조건: [실험 결과](docs/experiment-results.md) · [실험 설계](docs/experiment-design.md)

## 비교 전략

| 전략 | Redis HIT | Redis MISS | Redis 접근 중 `DataAccessException` |
|---|---|---|---|
| Stateless 기준선 | 조회하지 않음 | 조회하지 않음 | 해당 없음 |
| S1 Fail-Open | 거부 | 허용 | 허용 |
| S2 Fail-Closed | 거부 | 허용 | 거부 |
| S3 DB Fallback | 거부 | 허용 | MySQL에서 `jti` 조회 |
| S4 DB Verification | 거부 | MySQL에서 `jti` 조회 | MySQL에서 `jti` 조회 |

표의 '허용'은 JWT 서명·만료 검증을 통과했다는 전제에서 폐기 검증 전략이 요청을 통과시킨다는 뜻입니다. 코드가 명시적으로 처리하는 Redis 예외는 `DataAccessException`이며, 모든 종류의 장애를 포괄하는 것은 아닙니다.

## 아키텍처

```text
Client / k6
    |
Spring Security / JWT Filter
    |
RevocationStrategy
    |-- Redis: revoked:{jti} (TTL = 토큰 잔여 유효기간)
    `-- MySQL: revoked_tokens (기준 저장소)
```

- Access Token: HS256, 30분 만료, UUID `jti`
- 로그아웃 저장 순서: **MySQL → Redis** (원자적 저장 아님)
- MySQL 저장 후 Redis 반영 실패 시 불일치가 남을 수 있음
- 기존 `jti`가 MySQL에 존재하면 현재 로그아웃 구현은 조기 반환하므로, 재호출만으로 누락된 Redis 키를 복원하지 못할 수 있음

상세 구현: [아키텍처 문서](docs/architecture.md)

## 기술 스택과 실험환경

- Java 21, Spring Boot 4.1.1, Spring Security, Spring Data JPA, JWT
- MySQL 8.4, Redis 7.4, Docker Compose, k6 2.3.0
- MacBook Air / Apple M1(8코어) / RAM 8GB / macOS 15.6.1
- Docker Desktop 할당: CPU 8개, 메모리 4GB, Swap 1GB
- JVM 관측값: 초기 Heap 128MiB, 최대 Heap 2GiB, G1GC (별도 `-Xms`/`-Xmx` 지정 없음)
- MySQL: `max_connections=151`, InnoDB Buffer Pool 128MiB
- Redis: `maxclients=10000`, `maxmemory=0` (Redis 자체 메모리 상한 미설정)

Spring Boot 애플리케이션과 k6 부하 생성기가 같은 호스트에서 실행되어 결과에 호스트 자원 경쟁의 영향이 포함될 수 있습니다. 환경 세부값은 실험 종료 후 변경 없이 확인한 값입니다.

## 로컬 실행

사전 준비: **JDK 21, Docker Desktop, k6**. 명령어는 프로젝트 루트 기준입니다.

1. 예제 환경변수 파일을 복사하고 **직접 생성한 비밀값**을 입력합니다.

   ```bash
   cp .env.example .env
   # .env의 MYSQL_ROOT_PASSWORD, JWT_SECRET 수정
   ```

2. Compose 및 Spring Boot 실행. Compose는 `.env`를 읽지만, **Spring Boot를 IDE/터미널에서 별도로 실행할 때는 환경변수를 별도 전달**해야 합니다.

   ```bash
   docker compose up -d
   set -a; source .env; set +a
   ./gradlew bootRun
   ```

   `application.yaml`은 MySQL `localhost:3307`, Redis `localhost:6380`, HTTP `localhost:8080`을 사용합니다. DB 이름은 `jwt_research`로 맞춰야 합니다. 기존 MySQL 볼륨을 재사용할 때 `MYSQL_DATABASE` 변경만으로 DB가 새로 생성되지는 않습니다.

3. `src/main/resources/application.yaml`의 `jwt.revocation.strategy`를 선택하고 **애플리케이션을 재시작**합니다.

   ```yaml
   jwt:
     revocation:
       strategy: db-fallback
   ```

   지원 값: `stateless`, `fail-open`, `fail-closed`, `db-fallback`, `db-verification`.

4. `/auth/login`으로 발급한 토큰을 `ACCESS_TOKEN` 등에 지정해 실험을 실행합니다. 로그인에는 DB에 등록된 사용자 계정이 필요합니다. 이 저장소에는 공개 데모 계정이나 초기 사용자 생성 스크립트가 제공되지 않으므로 테스트 계정은 별도로 준비해야 합니다.

   ```bash
   curl -X POST http://localhost:8080/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"email":"YOUR_TEST_EMAIL","password":"YOUR_TEST_PASSWORD"}'
   ```

   보호 API는 `GET /api/protected`, 폐기는 `POST /auth/logout` (`Authorization: Bearer <token>`)입니다.

## 실험 실행 및 원본 데이터

- E1: `./experiments/k6/run-e1-stateless.sh 20` 또는 `./experiments/k6/run-e1-redis.sh 20` (50/100도 각각 실행)
- E2: `./experiments/k6/run-e2.sh fail-open` (전략 변경·Redis 중단 등 사전 조건 별도 준비)
- E3: `./experiments/k6/run-e3.sh db-verification` (MySQL 폐기 기록 유지, Redis 키 누락 상태 별도 준비)
- E4: `./experiments/k6/run-e4-s3.sh 20` 또는 `./experiments/k6/run-e4-s4.sh 20` (50/100도 각각 실행)

E1/E4는 조건별 30초 × 5회, 20/50/100 VU, 5 VU·20초 Warm-up, 대기 30초입니다. E2/E3는 1 VU·100 iterations이며 E2는 반복마다 정상 토큰과 폐기 토큰을 각 1회 요청합니다.

필요한 환경변수: E1/E4는 `ACCESS_TOKEN`, E2는 `VALID_TOKEN`·`REVOKED_TOKEN`, E3는 `REVOKED_TOKEN`. **실제 JWT를 커밋하거나 문서에 붙여 넣지 마세요.**

원본 결과: [`experiments/k6/results/`](experiments/k6/results/) · [실행 체크리스트](docs/experiment-checklist.md)

> **k6 체크 해석 주의:** E2/E3의 `revoked token allowed`는 HTTP 200을 성공으로 검사합니다. 따라서 폐기 토큰이 정상 차단되면 해당 체크는 실패로 기록됩니다. 체크 실패율을 실험 실패율이나 보안 실패율로 그대로 해석하면 안 됩니다.

## 연구 범위와 한계

- 단일 로컬 머신·단일 Redis/MySQL 인스턴스에서의 통제된 실험입니다.
- E2는 Redis 컨테이너 중단을, E3는 Redis의 폐기 키 누락을 모사했습니다. 네트워크 분할·복합 장애·실서비스 트래픽을 검증하지 않았습니다.
- E2/E3는 각 전략당 100회 요청의 **인증 결과 관찰**이지 장애 발생 확률의 추정이 아닙니다.
- DB Verification은 Redis 키가 누락된 상태에서 MySQL에 폐기 기록이 존재하는 경우를 검출하며, 모든 데이터 일관성 문제를 해결하는 것은 아닙니다.
- 로그아웃의 MySQL/Redis 이중 기록은 원자적이지 않고 자동 복구·재동기화가 구현되지 않았습니다.

## 문서

- [연구 설계](docs/research-design.md) — 연구 질문·가설·평가 지표
- [시스템 아키텍처](docs/architecture.md) — 실제 구현 흐름과 제한사항
- [실험 설계](docs/experiment-design.md) — E1~E4 조건과 분석 방법
- [실험 체크리스트](docs/experiment-checklist.md) — 실행 절차 및 상태 확인
- [실험 결과](docs/experiment-results.md) — 최종 집계표·해석·원본 위치
