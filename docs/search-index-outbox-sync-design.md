# 검색 인덱스 Outbox 동기화 이벤트 설계

## 작성일

- 2026-09-19

## 목적

ADR-011은 검색 read model을 도입할 조건과 지연 허용치를 정의했다. 이 문서는 검색 read model 도입 시 상품 변경을 검색 인덱스에 전달하기 위한 Outbox 이벤트 설계를 정한다.

현재 단계에서는 검색 엔진과 색인 워커를 구현하지 않는다. 대신 이벤트 타입, payload, 소비자 idempotency, 실패 복구 기준을 먼저 고정해 이후 OpenSearch 같은 검색 엔진을 붙일 때 도메인 저장과 색인 요청 사이의 이중 쓰기 위험을 줄인다.

## 적용 범위

검색 인덱스 동기화 이벤트는 사용자 상품 검색 결과를 갱신하기 위한 projection 이벤트다.

포함 대상은 다음과 같다.

- 상품 생성
- 상품명, 설명, 카테고리, 브랜드, 이미지, 가격 변경
- 판매 상태 변경
- 상품 비활성화 또는 삭제
- 리뷰 통계 변경 중 검색 결과 표시나 랭킹에 쓰는 값

제외 대상은 다음과 같다.

- 주문 생성 가능 여부 판단
- 재고 차감 성공 여부 판단
- 결제 가능 여부 판단
- 관리자 재고 조회
- 실시간 재고 랭킹

검색 인덱스는 탐색 경험을 위한 읽기 모델이다. 가격, 판매 상태, 재고, 주문 가능 여부의 최종 검증은 운영 DB를 기준으로 한다.

## 이벤트 타입

초기 이벤트 타입은 상품 검색 문서의 upsert와 delete를 명확히 분리한다.

| 이벤트 타입 | aggregate type | 발생 시점 | 처리 기준 |
| --- | --- | --- | --- |
| `PRODUCT_SEARCH_INDEX_UPSERT_REQUESTED` | `PRODUCT` | 상품 생성 또는 검색 문서에 포함되는 필드 변경 커밋 시 | 검색 문서를 생성하거나 최신 상태로 갱신한다. |
| `PRODUCT_SEARCH_INDEX_DELETE_REQUESTED` | `PRODUCT` | 상품이 비활성화되거나 검색 노출 대상에서 제외될 때 | 검색 문서를 삭제하거나 `status=INACTIVE`로 갱신한다. |

두 이벤트 모두 outbox row를 상품 변경 트랜잭션 안에서 저장한다. 발행자는 ADR-007의 `PENDING`, `FAILED`, `PUBLISHED`, `DEAD_LETTER` 상태와 재시도 정책을 그대로 사용한다.

## Payload

검색 동기화 payload는 소비자가 원본 DB를 다시 읽지 않아도 기본 색인이 가능한 최소 스냅샷을 담는다. 다만 재처리나 보정이 필요할 때는 `productId` 기준으로 DB의 현재 상품 상태를 다시 조회할 수 있다.

공통 필드는 다음과 같다.

| 필드 | 설명 |
| --- | --- |
| `eventId` | 소비자 idempotency 기준 UUID |
| `eventType` | 검색 색인 이벤트 타입 |
| `occurredAt` | outbox 이벤트 생성 시각 |
| `aggregateType` | `PRODUCT` |
| `aggregateId` | `productId`와 동일한 상품 aggregate ID |
| `productId` | 검색 문서 ID와 DB 상품 연결 키 |
| `indexVersion` | 검색 문서 스키마 버전 |
| `changeReason` | `CREATED`, `UPDATED`, `STATUS_CHANGED`, `DELETED`, `REVIEW_STATS_CHANGED` 중 하나 |

`PRODUCT_SEARCH_INDEX_UPSERT_REQUESTED`는 다음 필드를 추가로 담는다.

| 필드 | 정책 |
| --- | --- |
| `name` | 검색 대상 |
| `description` | 검색 대상 |
| `category` | 필터와 랭킹 보조 정보 |
| `brand` | 필터와 랭킹 보조 정보 |
| `price` | 표시와 정렬용, 주문 최종 검증은 DB 기준 |
| `status` | 사용자 검색은 `ACTIVE`만 노출 |
| `imageUrl` | 검색 결과 표시용 |
| `averageRating` | 표시 또는 랭킹용 |
| `reviewCount` | 표시 또는 랭킹용 |
| `updatedAt` | 검색 문서 최신성 비교용 |

`PRODUCT_SEARCH_INDEX_DELETE_REQUESTED`는 `productId`, `status`, `updatedAt`, `changeReason`만으로 처리할 수 있다. 실제 검색 엔진에서는 물리 삭제를 기본으로 하되, 운영 감사나 롤백 요구가 있으면 tombstone 문서로 확장한다.

개인정보와 주문 데이터는 payload에 포함하지 않는다.

## 소비자 처리 정책

검색 read model 소비자는 ADR-007의 소비자 idempotency 정책을 따른다.

| 항목 | 정책 |
| --- | --- |
| 소비자 이름 | `search-indexer` |
| 중복 판단 키 | `consumer_name + event_id` |
| 처리 보장 | at-least-once 수신, idempotent 색인 |
| 처리 이력 보관 | 최소 90일 |
| 문서 ID | `productId` |
| 버전 비교 | payload의 `updatedAt` 또는 DB 재조회 결과의 `updatedAt` 기준 |

같은 이벤트가 다시 전달되면 색인 작업을 반복하지 않고 성공으로 응답한다. 서로 다른 이벤트가 역순으로 도착할 수 있으므로, 소비자는 현재 검색 문서의 `updatedAt`보다 오래된 payload로 최신 문서를 덮어쓰지 않는다.

payload만으로 최신성 판단이 애매하면 소비자는 DB에서 `productId`의 현재 상태를 재조회해 검색 문서를 재생성한다. 이 방식은 이벤트 순서 보장에 강하게 의존하지 않고 최종 상태를 복구하기 위한 기준이다.

## 장애와 보정

색인 실패는 outbox retry 정책을 따른다. 5회 재시도 후에도 실패하면 `DEAD_LETTER`로 남기고 운영자가 재발행 또는 전체 재색인을 선택한다.

운영 보정 경로는 다음과 같이 둔다.

1. 단건 보정: `productId` 기준으로 DB 현재 상태를 읽어 검색 문서를 upsert 또는 delete한다.
2. 기간 보정: 특정 시간 이후 변경된 상품을 조회해 검색 문서를 재생성한다.
3. 전체 재색인: 검색 문서 스키마 변경이나 대량 누락이 확인되면 `indexVersion`을 올리고 전체 활성 상품을 재색인한다.

검색 인덱스와 DB 사이의 불일치는 사용자 탐색 품질 문제로 다룬다. 주문 생성, 재고 차감, 가격 검증은 DB를 다시 조회하므로 검색 인덱스 불일치가 과금이나 재고 변경의 최종 판단이 되지 않는다.

## 코드 반영 기준

검색 read model 도입이 결정되면 다음 순서로 구현한다.

1. `OutboxEvent.EventType`에 `PRODUCT_SEARCH_INDEX_UPSERT_REQUESTED`, `PRODUCT_SEARCH_INDEX_DELETE_REQUESTED`를 추가한다.
2. `OutboxEvent.AggregateType`에 `PRODUCT`를 추가한다.
3. `ProductService`의 생성, 수정, 삭제, 리뷰 통계 변경 트랜잭션에서 검색 색인 outbox 이벤트를 저장한다.
4. `search-indexer` 소비자 처리 이력 저장소를 추가해 `consumer_name + event_id` 유니크 제약을 둔다.
5. 색인 실패, 중복 전달, 역순 이벤트, 단건 재색인 테스트를 추가한다.

현재 문서 작업에서는 위 코드 변경을 수행하지 않는다. ADR-011의 도입 조건이 충족되어 검색 read model을 실제로 붙일 때 실행한다.

## 결과

검색 인덱스 동기화는 상품 변경 트랜잭션 안에서 outbox 이벤트를 저장하고, 별도 `search-indexer` 소비자가 at-least-once로 수신해 idempotent하게 색인하는 방식으로 설계한다.

이 설계는 검색 결과의 짧은 지연을 허용하면서도 상품 저장과 색인 요청 사이의 유실 위험을 낮춘다. 검색 인덱스가 stale해도 구매와 재고 변경의 최종 판단은 DB가 담당한다.
