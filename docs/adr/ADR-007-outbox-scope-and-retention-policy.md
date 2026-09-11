# ADR-007: Outbox 적용 범위, 보관 정책, 재시도 기준

## 상태

Accepted

## 배경

현재 주문, 결제, 쿠폰 처리는 같은 모놀리식 애플리케이션 안에서 서비스 메서드 직접 호출로 연결된다. 이 구조는 단순하고 로컬 트랜잭션 경계를 이해하기 쉽지만, 알림, 검색 인덱싱, 정산, 운영 보정 같은 후속 처리를 외부 시스템이나 비동기 워커로 넘기기 시작하면 저장과 발행 사이의 이중 쓰기 문제가 생긴다.

예를 들어 주문은 정상 저장됐지만 알림 이벤트 발행이 실패하거나, 결제 완료 row는 커밋됐지만 정산 이벤트 발행 전에 서버가 종료될 수 있다. 반대로 이벤트를 먼저 발행한 뒤 DB 트랜잭션이 롤백되면 후속 시스템은 존재하지 않는 주문이나 결제를 처리하게 된다.

Outbox는 핵심 도메인 상태 변경과 이벤트 기록을 같은 DB 트랜잭션에 저장한 뒤, 별도 발행자가 저장된 이벤트를 읽어 외부로 전달하는 패턴이다. 현재 단계에서는 내부 도메인 호출을 모두 이벤트로 대체하지 않고, 저장 이후 비동기 처리가 필요한 업무 이벤트의 안정적인 전달 기준을 정한다.

## 결정

Outbox는 주문과 결제의 주요 상태 변경 이벤트에 우선 적용한다. 이벤트 row는 해당 도메인 변경을 저장하는 같은 로컬 트랜잭션 안에서 함께 저장한다.

| 항목 | 결정 |
| --- | --- |
| 적용 방식 | DB outbox 테이블에 이벤트 저장 후 별도 발행자가 전송 |
| 트랜잭션 경계 | 도메인 상태 변경과 outbox 저장을 같은 로컬 트랜잭션으로 묶음 |
| 초기 적용 범위 | 주문 생성, 주문 취소, 결제 완료, 결제 실패, 운영 보정 등록 |
| 제외 범위 | 상품 조회, 장바구니 조회, 단순 관리자 조회, 동기 검증 실패 |
| 발행 보장 | at-least-once |
| 소비자 중복 처리 | 이벤트 ID 기준 idempotency를 소비자 책임으로 둠 |
| 소비자 처리 이력 보관 | 소비자별 처리 이력을 최소 90일 보관 |

Outbox는 현재 모놀리식 내부의 즉시 일관성이 필요한 호출을 대체하지 않는다. 주문 생성 중 재고 차감과 쿠폰 사용, 결제 성공 후 주문 확정처럼 같은 요청 안에서 결과가 확정되어야 하는 처리는 기존 로컬 트랜잭션 흐름을 유지한다. Outbox는 그 결과를 알림, 검색 read model, 정산, 운영 보정 워커로 안정적으로 전달하기 위한 기록으로 사용한다.

## 이벤트 범위

초기 이벤트 타입은 운영 리스크가 큰 주문과 결제 흐름으로 제한한다.

| 이벤트 타입 | 발생 시점 | 주요 용도 |
| --- | --- | --- |
| `ORDER_CREATED` | 주문이 `PENDING`으로 저장된 후 | 주문 알림, 검색 read model 후보, 주문 분석 |
| `ORDER_CANCELLED` | 주문이 `CANCELLED`로 전이되고 자원 복구가 끝난 후 | 취소 알림, 재고 read model 보정, CS 이력 |
| `PAYMENT_COMPLETED` | 결제가 `COMPLETED`로 저장되고 주문 확정 처리가 끝난 후 | 정산, 구매 완료 알림, 매출 분석 |
| `PAYMENT_FAILED` | 결제가 `FAILED`로 저장되고 주문 취소 복구가 끝난 후 | 실패 알림, 결제 장애 분석 |
| `PAYMENT_RECONCILIATION_REQUIRED` | 늦은 결제 성공 등 운영 보정 대상이 저장된 후 | 운영자 보정 큐, 환불 필요 검토 |

쿠폰 발급, 리뷰 작성, 상품 변경 이벤트는 현재 단계의 초기 범위에서 제외한다. 해당 이벤트는 분석 또는 마케팅 요구가 명확해질 때 별도 ADR이나 구현 작업으로 추가한다.

## 테이블 스키마

`outbox_events` 테이블을 추가한다.

| 컬럼 | 정책 |
| --- | --- |
| `outbox_event_id` | 내부 PK |
| `event_id` | 외부 전달과 소비자 idempotency에 사용하는 UUID, not null, unique |
| `event_type` | 업무 이벤트 타입, not null |
| `aggregate_type` | `ORDER`, `PAYMENT`, `RECONCILIATION` 같은 집합 타입, not null |
| `aggregate_id` | 집합 ID, not null |
| `payload` | 이벤트 본문 JSON, not null |
| `status` | `PENDING`, `PUBLISHED`, `FAILED`, `DEAD_LETTER` |
| `retry_count` | 발행 실패 횟수, 기본값 0 |
| `last_error` | 마지막 발행 실패 사유, nullable |
| `available_at` | 다음 발행 가능 시각, not null |
| `published_at` | 발행 성공 시각, nullable |
| `created_at`, `updated_at` | 감사 시각 |

기본 조회 인덱스는 `status, available_at, outbox_event_id` 순서로 둔다. 발행자는 `PENDING` 또는 재시도 가능한 `FAILED` 이벤트 중 `available_at <= now`인 row를 작은 배치로 조회한다.

`event_id`는 외부로 전달되는 안정적인 이벤트 식별자다. 소비자는 이 값을 기준으로 중복 처리를 막아야 한다. `outbox_event_id`는 내부 저장소 PK로만 사용한다.

## Payload 정책

Payload는 후속 처리에 필요한 최소 스냅샷을 담는다. 소비자가 이벤트 수신 시점에 원본 테이블을 다시 조회할 수는 있지만, 원본 상태가 이미 바뀌었을 수 있으므로 이벤트 발생 당시 판단에 필요한 핵심 값은 payload에 포함한다.

초기 공통 필드는 다음과 같다.

- `eventId`
- `eventType`
- `occurredAt`
- `aggregateType`
- `aggregateId`
- `orderId`
- `paymentId`
- `userId`
- `amount`
- `status`
- `reason`

개인정보는 payload에 넣지 않는다. 배송지, 전화번호, 카드번호 같은 값은 이벤트가 아니라 원본 도메인 테이블의 접근 권한을 통해 조회한다. 로그와 payload 모두 ADR-003의 마스킹 기준을 따른다.

## 발행과 재시도 정책

Outbox 발행은 at-least-once 모델로 둔다. 발행자는 같은 이벤트를 두 번 보낼 수 있으므로 소비자는 `event_id` 기준으로 idempotent하게 처리해야 한다.

발행 상태는 다음과 같이 사용한다.

- `PENDING`: 아직 발행되지 않은 신규 이벤트다.
- `PUBLISHED`: 발행 성공이 확인된 이벤트다.
- `FAILED`: 발행에 실패했지만 재시도 가능한 이벤트다.
- `DEAD_LETTER`: 최대 재시도를 초과해 자동 발행을 중단한 이벤트다.

최대 재시도 횟수는 5회로 둔다. 실패 시 `retry_count`를 증가시키고 `last_error`를 갱신한다. 재시도 간격은 초기 구현에서 고정 1분으로 시작하고, 외부 시스템 부하나 장애 패턴이 확인되면 지수 backoff로 확장한다. 5회를 초과한 이벤트는 `DEAD_LETTER`로 전환하고 운영자가 확인할 수 있게 조회 대상에 남긴다.

## 소비자 idempotency 정책

Outbox 발행자는 장애 복구, 네트워크 타임아웃, 발행 성공 확인 실패 상황에서 같은 이벤트를 다시 전달할 수 있다. 따라서 모든 소비자는 이벤트 처리 전에 `event_id`와 소비자 이름을 함께 기록하고, 이미 처리된 조합이면 업무 로직을 다시 실행하지 않는다.

초기 소비자 기준은 다음과 같다.

| 소비자 | 중복 판단 키 | idempotent 처리 기준 | 처리 이력 보관 |
| --- | --- | --- | --- |
| 알림 소비자 | `consumer_name + event_id` | 같은 이벤트의 알림은 한 번만 전송한다. 전송 성공 후 처리 이력을 `PROCESSED`로 남긴다. | 90일 |
| 검색 read model 소비자 | `consumer_name + event_id` | 같은 이벤트가 재전달되면 색인 갱신을 생략한다. 최신 상태 보정이 필요하면 별도 재색인 작업으로 처리한다. | 90일 |
| 정산 소비자 | `consumer_name + event_id` | 같은 결제 완료 이벤트의 정산 요청은 한 번만 생성한다. 이미 처리된 이벤트는 성공 응답으로 간주한다. | 정산 데이터 보관 기간과 동일 |
| 운영 보정 소비자 | `consumer_name + event_id` | 같은 보정 대상은 한 번만 큐에 등록한다. 이미 등록된 이벤트는 기존 보정 건을 재사용한다. | 보정 업무 데이터 보관 기간과 동일 |

소비자 처리 이력 저장소는 소비자별로 분리하거나 공통 테이블을 사용할 수 있지만, 유니크 제약은 반드시 `consumer_name, event_id` 조합으로 둔다. 이벤트 타입이나 aggregate ID는 조회와 감사용 보조 필드로만 사용하고, 중복 판단의 최종 기준으로 쓰지 않는다. 같은 업무 이벤트가 두 소비자에게 각각 전달될 수 있으므로 `event_id` 단독 유니크 제약은 사용하지 않는다.

처리 상태는 최소 `PROCESSING`, `PROCESSED`, `FAILED`를 가진다. 소비자는 처리 시작 시 이력을 선점하고, 업무 처리가 끝난 뒤 `PROCESSED`로 갱신한다. 처리 중 장애로 `PROCESSING` 상태가 오래 남는 경우에는 소비자별 타임아웃 기준에 따라 재처리 가능 대상으로 전환한다. 이 기준은 외부 시스템 연동 방식이 구체화될 때 구현 ADR 또는 운영 문서에서 세분화한다.

중복 이벤트를 받은 소비자는 오류로 처리하지 않고 성공으로 응답한다. 이는 outbox 발행자가 같은 이벤트를 계속 재시도해 큐나 외부 시스템을 압박하지 않도록 하기 위한 기준이다.

## 보관 정책

Outbox 이벤트는 도메인 감사와 장애 복구 근거이므로 단기 TTL로 삭제하지 않는다. 기본 보관 기간은 주문과 결제 운영 데이터 보관 기간과 맞춘다.

운영 DB 용량이 문제가 되면 `PUBLISHED` 상태 이벤트부터 월 단위로 아카이브한다. 아카이브 시에도 `event_id`, `event_type`, `aggregate_type`, `aggregate_id`, `status`, `retry_count`, 주요 시각, payload는 보존한다. `FAILED`와 `DEAD_LETTER` 이벤트는 운영자가 처리하거나 별도 보관 결정을 하기 전까지 운영 DB에 남긴다.

## 적용 범위

- Phase 2 Day 11은 주문과 결제 저장 트랜잭션 안에서 outbox row를 함께 저장한다.
- Phase 2 Day 12는 `PENDING` 또는 재시도 가능한 `FAILED` 이벤트 조회와 `PUBLISHED` 상태 업데이트를 구현한다.
- Phase 2 Day 13은 `retry_count`, `last_error`, `available_at`, `DEAD_LETTER` 전환을 구현한다.
- Phase 2 Day 14는 소비자별 `event_id` 기준 idempotency를 정의한다.
- Outbox는 PG 웹훅 수신 idempotency 저장소를 대체하지 않는다. 웹훅 중복 판단은 ADR-006의 `pg_event_id`를 계속 사용한다.
- Outbox는 결제 API idempotency key를 대체하지 않는다. 결제 생성 재시도 판단은 ADR-002의 `orderId + idempotencyKey`를 계속 사용한다.

## 결과

주문과 결제의 핵심 상태 변경은 같은 트랜잭션 안에서 outbox 이벤트로 기록된다. 후속 발행은 at-least-once로 처리하고, 중복 처리는 소비자가 `event_id` 기준으로 방어한다.

이 결정은 외부 발행 실패가 도메인 저장 결과를 유실시키는 위험을 줄인다. 소비자 처리 이력은 `consumer_name, event_id` 조합으로 관리해 같은 이벤트가 재전달되어도 알림, 검색 색인, 정산, 운영 보정이 중복 실행되지 않도록 한다.
