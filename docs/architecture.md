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

예시:

    id jti       revoked_at         expires_at
  ---- --------- ------------------ ------------------
     1 abc-123   2026-10-12 15:30   2026-10-12 16:00

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

따라서 동일 사용자가 여러 번 로그인하더라도 각각의 Access Token을
독립적으로 폐기할 수 있다.

## 6. Redis 구조

### Key

`revoked:{jti}`

예:

`revoked:550e8400-e29b-41d4-a716-446655440000`

### Value

단순 존재 여부만 필요하므로 `"1"`과 같은 값을 사용할 수 있다.

### TTL

Redis 폐기 키의 TTL은 JWT의 남은 유효시간과 동일하게 설정한다.

`TTL = JWT expiresAt - 현재 시각`

JWT가 만료된 이후에는 자체 만료 검증에서 거부되므로 Redis에 폐기 정보를
계속 보관할 필요가 없다.

## 7. API

### 로그인

`POST /auth/login`

요청 예시:

``` json
{
  "email": "test@test.com",
  "password": "1234"
}
```

응답 예시:

``` json
{
  "accessToken": "eyJ..."
}
```

### 로그아웃

`POST /auth/logout`

Header:

`Authorization: Bearer {accessToken}`

현재 Access Token의 `jti`, `exp`를 추출하여 MySQL과 Redis에 폐기 정보를
저장한다.

### 보호 API

`GET /api/protected`

Header:

`Authorization: Bearer {accessToken}`

응답 예시:

``` json
{
  "message": "success"
}
```

`/api/protected`는 별도의 비즈니스 의미를 가지는 API가 아니라 JWT 인증
및 폐기 전략을 동일한 조건에서 테스트하기 위한 최소 보호 Endpoint이다.

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

모든 전략은 다음 공통 처리 과정을 사용한다.

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

공통 인터페이스 예시:

``` java
public interface RevocationStrategy {
    boolean isRevoked(String jti);
}
```

구현체:

``` text
FailOpenRevocationStrategy
FailClosedRevocationStrategy
DbFallbackRevocationStrategy
DbVerificationRevocationStrategy
```

Redis 접근 세부 구현은 `RedisRevocationStore`와 같은 별도 컴포넌트로
분리할 수 있다.

예:

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

`redisHasKey()`와 같은 표현은 설계 설명을 위한 의사 메서드이며
Spring/Redis의 실제 기본 메서드명이 아니다. 실제 구현에서는
`StringRedisTemplate.hasKey()` 등을 사용한다.

## 11. S1 --- Fail-Open

``` text
Redis 정상
  |
  +--> HIT  -> revoked
  |
  +--> MISS -> not revoked

Redis 연결 실패
  |
  v
not revoked로 판단
```

개념적 의사코드:

``` java
try {
    return redisRevocationStore.isRevoked(jti);
} catch (RedisConnectionFailureException e) {
    return false;
}
```

## 12. S2 --- Fail-Closed

``` text
Redis 정상
  |
  +--> HIT  -> revoked
  |
  +--> MISS -> not revoked

Redis 연결 실패
  |
  v
요청 거부
```

개념적으로는 연결 실패 시 폐기 검증에 실패한 요청을 허용하지 않는다.
실제 구현에서는 실제 폐기 상태와 정책상 거부를 별도 예외로 구분할 수
있다.

## 13. S3 --- DB Fallback

``` text
Redis 정상
  |
  +--> HIT  -> 거부
  |
  +--> MISS -> 허용

Redis 연결 실패
  |
  v
MySQL existsByJti(jti)
  |
  +--> 존재 -> 거부
  |
  +--> 없음 -> 허용
```

Redis가 정상적으로 `MISS`를 반환하는 경우에는 DB를 조회하지 않는다.

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

Redis 연결 실패
  |
  v
MySQL 조회
```

따라서 `MySQL=revoked`, `Redis=MISS`인 상태에서도 폐기 토큰을 탐지할 수
있다.

## 15. Stateless Baseline

E1의 기준선으로 다음 흐름을 사용한다.

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
Authentication
```

Redis 및 MySQL 폐기 조회를 수행하지 않는다.

## 16. 전략 전환

동일한 코드베이스에서 설정값으로 전략을 전환하는 구조를 사용한다.

예:

``` yaml
jwt:
  revocation:
    strategy: fail-open
```

후보 값:

``` text
stateless
fail-open
fail-closed
db-fallback
db-verification
```

Spring의 조건부 Bean 등록 또는 Factory 패턴 등을 이용할 수 있다.
구체적인 구현 방식은 실제 코드 구조에 맞추어 결정한다.

## 17. 장애 및 불일치 재현

### Redis 장애

Docker Compose 환경에서 Redis 컨테이너만 중단한다.

``` bash
docker compose stop redis
```

실험 종료 후:

``` bash
docker compose start redis
```

### Redis-MySQL 불일치

1.  토큰을 정상 로그아웃하여 MySQL과 Redis에 폐기 정보를 저장한다.
2.  MySQL 기록은 유지한다.
3.  Redis의 해당 폐기 키만 삭제한다.

개념적으로:

``` text
DEL revoked:{jti}
```

결과:

``` text
MySQL              Redis
jti = revoked      MISS
```

이는 실제 모든 불일치를 재현하는 것이 아니라 DB 저장 성공 후 Redis 반영
실패로 발생할 수 있는 상태를 통제된 방식으로 모사한다.

## 18. 권장 패키지 구조

``` text
src/main/java/...

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
 ├── RevocationStrategy
 ├── FailOpenRevocationStrategy
 ├── FailClosedRevocationStrategy
 ├── DbFallbackRevocationStrategy
 └── DbVerificationRevocationStrategy

user/
 ├── User
 └── UserRepository

api/
 └── ProtectedController
```

## 19. 구현 순서

1.  `User`, `RevokedToken` 엔티티 및 Repository
2.  JWT 발급 및 `jti` 생성
3.  로그인 API
4.  기본 JWT 인증 Filter
5.  `/api/protected`
6.  Redis 폐기 저장/조회
7.  로그아웃의 MySQL → Redis 저장
8.  `RevocationStrategy` 인터페이스
9.  S1\~S4 구현
10. 설정 기반 전략 전환
11. Docker Compose로 MySQL/Redis 환경 통일
12. k6 실험 스크립트 작성

## 20. 구현 시 주의사항

-   `/api/protected`는 연구용 보호 Endpoint로 통일한다.
-   `/auth/login`은 인증 없이 접근 가능해야 한다.
-   `/auth/logout`은 Access Token을 필요로 한다.
-   로그아웃 멱등성 자체는 본 연구의 핵심 대상이 아니므로 과도하게
    범위를 확장하지 않는다.
-   전략 외의 인증 처리 로직은 최대한 동일하게 유지하여 실험의 통제성을
    확보한다.
