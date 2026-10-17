# 시스템 아키텍처 (Architecture)

## 1. 설계 목표

본 시스템은 상용 서비스를 구현하기 위한 프로젝트가 아니라 JWT 폐기 검증
전략을 통제된 조건에서 비교하기 위한 **최소 실험 시스템**이다.

따라서 Refresh Token, Kafka, 복잡한 권한 관리, 비즈니스 도메인 기능 등
연구 질문과 직접 관련 없는 기능은 제외한다.

## 2. 기술 스택

-   Java 21
-   Spring Boot 4.1.1
-   Spring Security
-   JWT
-   Spring Data JPA
-   MySQL
-   Redis
-   Docker / Docker Compose
-   k6

## 3. 전체 구조

``` text
Client / k6
    |
    v
Spring Boot
    |
    +--> Spring Security / JWT Filter
    |          |
    |          +--> RevocationStrategy
    |                    |
    |                    +--> Redis
    |                    |
    |                    +--> MySQL
    |
    +--> Protected API
    |
    +--> Experiment Metrics API
```

MySQL은 폐기 상태의 Source of Truth로, Redis는 빠른 폐기 검증을 위한
보조 저장소로 사용한다.

## 4. 데이터 모델

### User

``` text
User
- id
- email
- password
```

최소한의 로그인 기능만 제공한다.

### RevokedToken

``` text
RevokedToken
- id
- jti
- expiresAt
- revokedAt
```

-   `jti`에는 Unique 제약을 적용한다.
-   별도의 `revoked` Boolean 컬럼을 두지 않는다.
-   `RevokedToken` 테이블에 해당 `jti`가 존재한다는 것 자체가 폐기
    상태를 의미한다.

## 5. JWT 구조

Access Token에는 최소한 다음 Claim을 포함한다.

``` json
{
  "sub": "1",
  "jti": "550e8400-e29b-41d4-a716-446655440000",
  "iat": 1791784800,
  "exp": 1791786600
}
```

-   `sub`: 사용자 식별자
-   `jti`: Access Token별 고유 식별자
-   `iat`: 발급 시각
-   `exp`: 만료 시각

로그인마다 새로운 `jti`를 생성한다.

``` java
String jti = UUID.randomUUID().toString();
```

## 6. Redis 구조

### Key

`revoked:{jti}`

### Value

폐기 여부는 키 존재 여부로 판단하므로 값은 `"1"`을 사용한다.

### TTL

`TTL = JWT expiresAt - 현재 시각`

JWT가 만료된 이후에는 자체 만료 검증에서 거부되므로 Redis에 폐기 정보를
계속 보관하지 않는다.

## 7. API

### 로그인

`POST /auth/login`

### 로그아웃

`POST /auth/logout`

현재 Access Token의 `jti`, `exp`를 추출하여 MySQL과 Redis에 폐기 정보를
저장한다.

### 보호 API

`GET /api/protected`

`/api/protected`는 JWT 인증 및 폐기 전략을 동일한 조건에서 테스트하기
위한 최소 보호 Endpoint이다.

### 실험 Metrics API

`POST /api/experiment/metrics/reset`

DB Query Count를 0으로 초기화한다.

`GET /api/experiment/metrics`

현재 DB Query Count를 반환한다.

Metrics Endpoint는 JWT 인증 Filter의 실험 대상 요청에 포함되지 않도록
`shouldNotFilter()`에서 제외하며, Security 설정에서도 실험용 Endpoint
접근을 허용한다.

## 8. 로그아웃 저장 흐름

저장 순서는 **MySQL → Redis**로 고정한다.

``` text
POST /auth/logout
      |
      v
JWT 검증
      |
      v
jti / exp 추출
      |
      v
MySQL
RevokedToken INSERT
      |
      | 성공
      v
Redis
SET revoked:{jti}
TTL = JWT 남은 수명
      |
      v
Logout 완료
```

MySQL 저장 성공 후 Redis 저장이 실패하면 다음과 같은 부분 실패 상태가
발생할 수 있다.

``` text
MySQL              Redis
ABC = revoked      ABC = MISS
```

이 상태가 E3에서 다루는 주요 불일치 시나리오다.

## 9. 공통 인증 흐름

``` text
Request
  |
  v
Bearer Token 추출
  |
  v
JWT 서명 검증
  |
  v
만료시간 검증
  |
  v
jti 추출
  |
  v
RevocationStrategy
  |
  +--> 폐기됨    -> 요청 거부
  |
  +--> 폐기 아님 -> SecurityContext 설정
                        |
                        v
                 Protected API
```

전략 간 차이는 폐기 여부를 판단하는 부분에만 두어 다른 인증 로직을
동일하게 유지한다.

## 10. RevocationStrategy

``` java
public interface RevocationStrategy {
    boolean isRevoked(String jti);
}
```

구현체:

``` text
StatelessRevocationStrategy
FailOpenRevocationStrategy
FailClosedRevocationStrategy
DbFallbackRevocationStrategy
DbVerificationRevocationStrategy
```

각 구현체는 `@ConditionalOnProperty`를 이용해 설정값에 따라 하나의 전략
Bean이 활성화되도록 구성한다.

예:

``` java
@ConditionalOnProperty(
    prefix = "jwt.revocation",
    name = "strategy",
    havingValue = "stateless"
)
```

Redis 접근은 `RedisRevocationStore`로 분리한다.

``` java
@Component
@RequiredArgsConstructor
public class RedisRevocationStore {

    private final StringRedisTemplate redisTemplate;

    public boolean isRevoked(String jti) {
        return Boolean.TRUE.equals(
            redisTemplate.hasKey("revoked:" + jti)
        );
    }
}
```

## 11. S1 --- Fail-Open

``` text
Redis 정상
 |
 +--> HIT  -> revoked
 |
 +--> MISS -> not revoked

Redis 접근 실패
 |
 v
not revoked로 판단
```

Redis 접근 과정에서 발생한 `DataAccessException`을 장애 경로로 처리하여
요청을 허용한다.

## 12. S2 --- Fail-Closed

``` text
Redis 정상
 |
 +--> HIT  -> revoked
 |
 +--> MISS -> not revoked

Redis 접근 실패
 |
 v
revoked로 판단하여 요청 거부
```

## 13. S3 --- DB Fallback

``` text
Redis 정상
 |
 +--> HIT  -> 거부
 |
 +--> MISS -> 허용

Redis 접근 실패
 |
 v
MySQL existsByJti(jti)
 |
 +--> 존재 -> 거부
 |
 +--> 없음 -> 허용
```

Redis가 정상적으로 `MISS`를 반환하는 경우에는 DB를 조회하지 않는다.
Redis 접근 실패 시에만 DB Query Count를 증가시키고 MySQL을 조회한다.

## 14. S4 --- DB Verification

``` text
Redis HIT
 |
 v
거부

Redis MISS
 |
 v
MySQL 조회
 |
 +--> 존재 -> 거부
 |
 +--> 없음 -> 허용

Redis 접근 실패
 |
 v
MySQL 조회
```

따라서 `MySQL=revoked`, `Redis=MISS`인 상태에서도 폐기 토큰을 탐지할 수
있다. Redis MISS 또는 Redis 접근 실패 후 MySQL을 조회할 때 DB Query
Count를 증가시킨다.

## 15. Stateless Baseline

``` text
Request
  |
  v
JWT 서명 검증
  |
  v
만료시간 검증
  |
  v
StatelessRevocationStrategy
  |
  v
항상 not revoked
  |
  v
Authentication
```

`StatelessRevocationStrategy.isRevoked()`는 항상 `false`를 반환하며
Redis 및 MySQL 폐기 조회를 수행하지 않는다.

## 16. 전략 전환

동일한 코드베이스에서 설정값으로 전략을 전환한다.

``` yaml
jwt:
  revocation:
    strategy: fail-open
```

지원 값:

``` text
stateless
fail-open
fail-closed
db-fallback
db-verification
```

전략 변경 후에는 Spring Boot를 재시작하여 해당 조건부 Bean이
활성화되도록 한다.

## 17. 실험 Metrics

DB Verification과 DB Fallback의 DB 접근 경로를 확인하기 위해
`RevocationMetrics`를 둔다.

``` java
@Component
public class RevocationMetrics {

    private final AtomicLong dbQueryCount = new AtomicLong(0);

    public void incrementDbQueryCount() {
        dbQueryCount.incrementAndGet();
    }

    public long getDbQueryCount() {
        return dbQueryCount.get();
    }

    public void reset() {
        dbQueryCount.set(0);
    }
}
```

Metrics는 연구용 보조 계측이며 상용 모니터링 시스템을 구현하려는 목적이
아니다.

## 18. 장애 및 불일치 재현

### Redis 장애

``` bash
docker compose stop redis
```

실험 종료 후:

``` bash
docker compose start redis
```

MySQL과 Spring Boot는 정상 상태를 유지한다.

### Redis-MySQL 불일치

1.  토큰을 정상 로그아웃하여 MySQL과 Redis에 폐기 정보를 저장한다.
2.  MySQL 기록은 유지한다.
3.  Redis의 폐기 정보를 제거한다.
4.  Redis 서버 자체는 정상 상태로 유지한다.

개념적으로:

``` text
MySQL              Redis
jti = revoked      MISS
```

특정 키를 삭제하거나 연구 전용 Redis DB에서 `FLUSHDB`를 사용하는
방식으로 통제된 불일치 상태를 구성할 수 있으며, 최종 실험에서는 실제
사용한 방식을 기록한다.

## 19. 패키지 구조

``` text
src/main/java/com/daeyeong/jwtrevocation/

auth/
├── AuthController
├── AuthService
├── LoginRequest
└── TokenResponse

security/
├── JwtProvider
├── JwtAuthenticationFilter
└── SecurityConfig

revocation/
├── RevokedToken
├── RevokedTokenRepository
├── RedisRevocationStore
├── RevocationMetrics
└── strategy/
    ├── RevocationStrategy
    ├── StatelessRevocationStrategy
    ├── FailOpenRevocationStrategy
    ├── FailClosedRevocationStrategy
    ├── DbFallbackRevocationStrategy
    └── DbVerificationRevocationStrategy

user/
├── User
└── UserRepository

api/
├── ProtectedController
└── ExperimentMetricsController
```

실제 클래스 배치가 위 예시와 다른 경우 최종 논문 작성 전 실제 코드
구조와 일치하도록 갱신한다.

## 20. 실험 스크립트 구조

``` text
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
    ├── e1/
    ├── e2/
    ├── e3/
    └── e4/
```

E1/E4는 성능 실험이므로 Warm-up과 반복 측정을 사용한다. E2/E3는 인증
판단 결과를 측정하므로 1 VU / 100 iterations로 수행하고 별도의 Warm-up을
적용하지 않는다.

## 21. 구현 상태 및 주의사항

현재 최소 실험 시스템에는 다음 기능이 구현되어 있다.

-   Stateless JWT 인증 Baseline
-   Redis 기반 폐기 저장/조회
-   MySQL → Redis 로그아웃 저장
-   S1 Fail-Open
-   S2 Fail-Closed
-   S3 DB Fallback
-   S4 DB Verification
-   설정 기반 전략 전환
-   DB Query Count 계측
-   k6 E1\~E4 실험 스크립트

구현 및 실험 시 다음 사항을 유지한다.

-   `/api/protected`는 연구용 보호 Endpoint로 통일한다.
-   `/auth/login`은 인증 없이 접근 가능해야 한다.
-   `/auth/logout`은 Access Token을 필요로 한다.
-   `/api/experiment/**`는 실험 계측용 Endpoint이며 인증 성능 측정
    경로에서 제외한다.
-   로그아웃 멱등성 자체는 본 연구의 핵심 대상이 아니므로 과도하게
    범위를 확장하지 않는다.
-   전략 외의 인증 처리 로직은 최대한 동일하게 유지하여 실험의 통제성을
    확보한다.
