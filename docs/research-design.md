# 연구 설계 (Research Design)

## 1. 연구 제목

**Redis 기반 JWT 폐기 검증 시스템에서 데이터 일관성 및 장애 대응 전략의 비교 분석**

## 2. 배경과 목적

JWT Access Token은 서명과 만료시간만으로 검증할 수 있지만, 만료 전에 로그아웃한 토큰을 즉시 차단하려면 별도의 폐기 상태 관리가 필요하다. 본 연구는 MySQL을 기준 저장소, Redis를 Denylist로 사용하는 구조에서 다음 두 문제를 **구분**한다.

1. **Redis 연결 장애:** 폐기 정보를 조회할 수 없음.
2. **Redis–MySQL 상태 불일치:** Redis는 정상 동작하지만 MySQL의 폐기 기록에 대응하는 Redis 키가 누락됨.

기존 기술을 통제된 환경에서 비교하는 실증 연구이며 새로운 JWT 암호 알고리즘이나 자동 복구 프로토콜을 제안하지 않는다.

## 3. 연구 질문과 대응 실험

| 연구 질문 | 내용 | 실험 |
|---|---|---|
| RQ1 | Redis Denylist가 Stateless 인증 성능에 미치는 영향 | E1 |
| RQ2 | Redis 장애 시 Fail-Open, Fail-Closed, DB Fallback의 보안성·가용성 | E2 |
| RQ3 | Redis–MySQL 불일치에서 DB Verification의 폐기 토큰 탐지 | E3 |
| RQ4 | DB Verification의 추가 DB 조회에 따른 성능 비용 | E4 |

## 4. 비교 전략

- **Stateless 기준선:** JWT 서명·만료만 검증.
- **S1 Fail-Open:** Redis `HIT` 거부, `MISS` 허용, 접근 실패 시 허용.
- **S2 Fail-Closed:** Redis `HIT` 거부, `MISS` 허용, 접근 실패 시 거부.
- **S3 DB Fallback:** Redis 접근 실패 시에만 MySQL 조회.
- **S4 DB Verification:** Redis `MISS` 또는 접근 실패 시 MySQL 조회.

Redis 접근 실패의 코드상 처리 대상은 `DataAccessException`이다. MySQL은 폐기 기록의 기준 저장소다.

## 5. 연구 가설

- **H1:** Redis Denylist는 Stateless 인증보다 평균·P95 지연이 증가하고 처리량이 감소할 것이다.
- **H2-1:** Redis 장애 시 Fail-Open은 정상 요청을 허용하지만 폐기 토큰도 오허용할 것이다.
- **H2-2:** Redis 장애 시 Fail-Closed는 폐기 토큰을 차단하지만 정상 요청도 거부할 것이다.
- **H2-3:** Redis 장애 시 DB Fallback은 MySQL 조회를 통해 정상·폐기 토큰을 구분할 것이다.
- **H3:** Redis 정상·키 누락 상태에서 S4는 S1~S3보다 폐기 토큰 오허용률이 낮을 것이다.
- **H4:** 정상 Redis 상태에서 S4는 S3보다 DB 조회가 많아 지연 증가와 처리량 감소가 나타날 것이다.

## 6. 평가 지표

- **RTAR** = HTTP 200으로 허용된 폐기 토큰 요청 / 전체 폐기 토큰 요청 × 100. 낮을수록 좋음.
- **VTSR** = HTTP 200으로 처리된 정상 토큰 요청 / 전체 정상 토큰 요청 × 100. 높을수록 좋음.
- **성능:** 평균 응답시간(ms), P95(ms), 처리량(requests/s), 오류율.
- **보조 계측:** DB Query Count (전략 구현의 DB 조회 경로 진입 횟수).

## 7. 실험 조건

- E1/E4: 20/50/100 VU, 각 30초 × 5회, 5 VU/20초 Warm-up, 대기 30초.
- E2: 1 VU/100 iterations, 반복마다 정상·폐기 토큰 각각 1회.
- E3: 1 VU/100 iterations, 폐기 토큰만 요청.
- 통제: 동일 호스트·API·JWT 조건·Redis/MySQL 구성; 전략 전환 후 애플리케이션 재시작.
- 결과: [experiment-results.md](experiment-results.md).

## 8. 해석의 범위

단일 로컬 호스트에서 수행한 실험이며 Redis 장애는 컨테이너 중단, 데이터 불일치는 특정 폐기 키 누락으로 통제했다. 네트워크 분할, 클러스터, 자동 복구, 복합 장애, 운영 트래픽은 평가하지 않았다. 결과는 해당 실험 조건의 상대 비교로 해석한다.
