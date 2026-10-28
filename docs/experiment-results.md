# E1~E4 최종 실험 결과

> 연구 결과 기록. 수치는 `experiments/k6/results/`의 k6 summary 및 DB Query Count 계측값을 기반으로 한 논문 집계값이다. E1/E4는 각 조건 5회 반복 측정의 평균이며, 평균 응답시간과 P95는 ms, 처리량은 requests/s 단위다. 표준편차는 반복 측정 5개의 **표본 표준편차**로 계산한다.

## E1 — Stateless JWT와 Redis Denylist

| VU | Stateless 처리량 | Redis 처리량 | Stateless 평균(ms) | Redis 평균(ms) | Stateless P95(ms) | Redis P95(ms) | Redis 처리량 감소 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 20 | 49,206.85 | 21,499.07 | 0.332 | 0.857 | 0.741 | 1.333 | 56.3% |
| 50 | 47,356.86 | 24,319.47 | 0.939 | 1.871 | 2.636 | 3.652 | 48.6% |
| 100 | 44,549.96 | 24,499.38 | 1.948 | 3.699 | 5.546 | 8.467 | 45.0% |

Redis 조회가 추가된 조건에서 처리량이 감소하고 평균·P95 응답시간이 증가했다. 이 차이는 본 로컬 환경에서 관찰된 결과이며 Redis 사용 자체의 보편적 성능 비용으로 일반화하지 않는다.

원본: [`experiments/k6/results/e1/`](../experiments/k6/results/e1/)

## E2 — Redis 연결 장애

Redis 컨테이너 중단 조건. 전략별 정상 토큰 100회, 폐기 토큰 100회. `VTSR`은 정상 토큰 요청 중 HTTP 200 비율, `RTAR`은 폐기 토큰 요청 중 HTTP 200 비율이다.

| 전략 | VTSR | RTAR | 해석 |
|---|---:|---:|---|
| S1 Fail-Open | 100% | 100% | 정상 요청 허용, 폐기 토큰도 오허용 |
| S2 Fail-Closed | 0% | 0% | 폐기 토큰 차단, 정상 요청도 거부 |
| S3 DB Fallback | 100% | 0% | DB를 통해 정상·폐기 토큰 구분 |

**중요:** k6 `revoked token allowed` 체크는 HTTP 200을 기대한다. 차단에 성공한 요청은 이 체크의 `fails`로 기록되므로, `checks.value`를 그대로 보안 성공률로 사용하지 않는다.

초기 S3 실행에서는 예상과 다른 결과가 관찰되어 재실험했으며, 원인은 확인되지 않았다. 본 표는 재실험 결과를 반영한다. 이 재실험 결과만으로 초기 이상 결과의 원인을 특정할 수 없다.

원본: [`experiments/k6/results/e2/`](../experiments/k6/results/e2/)

## E3 — Redis 정상, MySQL–Redis 폐기 정보 불일치

MySQL에는 해당 `jti` 폐기 기록이 존재하지만 Redis에서는 키가 누락된 조건. 전략별 폐기 토큰 100회 요청.

| 전략 | RTAR | DB 조회 횟수 |
|---|---:|---:|
| S1 Fail-Open | 100% | 0 |
| S2 Fail-Closed | 100% | 0 |
| S3 DB Fallback | 100% | 0 |
| S4 DB Verification | 0% | 100 |

S1~S3은 정상적인 Redis `MISS`를 폐기되지 않은 상태로 해석했다. S4는 `MISS`일 때 MySQL을 조회해 폐기 상태를 탐지했다. 이 결과는 **의도적으로 구성한 키 누락 시나리오**에 한정된다.

원본: [`experiments/k6/results/e3/`](../experiments/k6/results/e3/)

## E4 — DB Fallback과 DB Verification의 성능

| VU | S3 처리량 | S4 처리량 | S3 평균(ms) | S4 평균(ms) | S3 P95(ms) | S4 P95(ms) | S4 처리량 감소 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 20 | 20,846.26 | 8,441.12 | 0.883 | 2.286 | 1.417 | 3.443 | 59.5% |
| 50 | 24,853.32 | 9,090.42 | 1.834 | 5.232 | 3.591 | 9.613 | 63.4% |
| 100 | 24,354.36 | 9,208.75 | 3.712 | 10.315 | 8.509 | 22.513 | 62.2% |

정상 Redis에서 정상 토큰은 대부분 `MISS`를 반환한다. S3은 DB를 조회하지 않지만 S4는 매 요청마다 추가 MySQL 조회를 수행하므로 본 실험에서 처리량 감소 및 지연 증가가 관찰됐다.

원본: [`experiments/k6/results/e4/`](../experiments/k6/results/e4/)

## 공통 조건과 해석상의 제한

- Java 21 / Spring Boot 4.1.1 / Redis 7.4 / MySQL 8.4 / k6 2.3.0
- Apple M1(8코어), RAM 8GB, Docker Desktop CPU 8개·메모리 4GB·Swap 1GB
- E1/E4: 20·50·100 VU, 조건별 30초 × 5회, Warm-up 5 VU·20초, 측정 사이 대기 30초
- E2/E3: 1 VU·100 iterations, Warm-up 없음
- Spring Boot 서버와 k6 부하 생성기는 동일한 호스트에서 실행
- E2의 Redis 중단 상태와 E3의 Redis 키 누락 상태를 독립적으로 증명하는 상태 로그는 결과 JSON에 포함되지 않음
- 실제 운영환경의 장애 복구 시간, 고가용성 구성, 데이터 재동기화, 복합 장애를 측정하지 않음

**연구 결론:** Redis 연결 실패에 대한 대응만으로는 Redis가 정상적으로 반환한 잘못된 `MISS`를 검출할 수 없다. DB Verification은 이러한 특정 불일치를 검출하지만 정상 요청의 DB 조회 비용이 증가한다. 전략 선택은 보안 요구와 성능·가용성 요구를 함께 고려해야 한다.
