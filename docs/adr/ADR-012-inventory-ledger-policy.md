# ADR-012: 재고 원장의 기록 범위와 보관 기간

## 상태

Accepted

## 배경

현재 재고 수량은 `products.stock_quantity`가 최종 상태를 가진다. 주문 생성 시에는 `ProductRepository.decreaseStockIfAvailable`의 조건부 `UPDATE`로 재고를 차감하고, 주문 취소나 결제 실패 복구, 만료 주문 복구 시에는 `increaseStock`으로 재고를 더한다. 이 방식은 단순하고 동시 주문에서 음수 재고를 막는 데 효과적이다.

하지만 재고 변경 이력이 별도 원장으로 남지 않기 때문에 어떤 주문, 취소, 만료 복구, 관리자 조정이 현재 재고를 만들었는지 추적하기 어렵다. CS나 운영자가 특정 상품의 재고 변동을 설명해야 하거나, 재고 불일치가 발생했을 때 원인을 찾으려면 최종 수량만으로는 부족하다.

재고 원장은 재고 변경 이벤트를 append-only로 기록해 감사와 보정의 근거를 제공한다. 다만 모든 재고 조회와 주문 차감을 원장 합산으로 바꾸면 구현 복잡도와 성능 부담이 커지므로, 초기 단계에서는 `products.stock_quantity`를 계속 주문 가능 재고의 source of truth로 유지하고 원장은 감사·정산·보정용 기록으로 도입한다.

## 결정

재고 원장은 상품별 재고 변경을 append-only로 기록한다. 주문 가능 여부와 재고 차감의 최종 판단은 계속 `products.stock_quantity`와 조건부 업데이트가 담당하고, 원장은 변경 사유와 참조 업무 ID를 남기는 감사 테이블로 사용한다.

| 항목 | 결정 |
| --- | --- |
| 원장 역할 | 재고 변경 감사, CS 조회, 운영 보정 근거 |
| 최종 재고 기준 | `products.stock_quantity` |
| 기록 방식 | 재고 변경 성공 후 같은 로컬 트랜잭션에서 append-only row 저장 |
| 변경 단위 | 상품 1개와 수량 변화 1건 |
| 수량 표현 | 입고·복구는 양수, 차감·조정 감소는 음수 |
| 초기 조회 | 상품별 최신 변경 이력과 참조 업무 추적 |
| 제외 범위 | 주문 가능 여부 실시간 판단, 검색 read model 재고 랭킹, 캐시 무효화의 단독 기준 |
| 보관 기간 | 운영 DB 1년, 이후 월 단위 아카이브 |

원장 row는 재고 변경이 실제로 성공한 경우에만 저장한다. 조건부 차감이 실패해 `INSUFFICIENT_STOCK`으로 끝난 요청은 원장에 기록하지 않고, 필요하면 별도 실패 로그나 메트릭으로 관측한다.

## 기록 대상

초기 원장은 실제 재고 수량을 바꾸는 업무만 기록한다.

| 변경 사유 | delta 방향 | 참조 ID | 정책 |
| --- | --- | --- | --- |
| `ORDER_PLACED` | 음수 | `orderId`, `orderItemId` | 주문 생성에서 재고 차감이 성공한 뒤 기록한다. |
| `ORDER_CANCELLED` | 양수 | `orderId`, `orderItemId` | 사용자 취소 복구가 성공한 뒤 기록한다. |
| `PAYMENT_FAILED_RESTORED` | 양수 | `orderId`, `paymentId` | 결제 실패로 주문을 취소하고 재고를 복구한 뒤 기록한다. |
| `PENDING_ORDER_EXPIRED` | 양수 | `orderId` | 만료 주문 복구가 성공한 뒤 기록한다. |
| `ADMIN_ADJUSTED` | 양수 또는 음수 | `adminUserId`, `adjustmentRequestId` | 관리자 수동 조정 기능을 도입할 때 기록한다. |
| `INITIAL_STOCK_SET` | 양수 | `productId` | 상품 생성 시 초기 재고를 원장 시작점으로 남긴다. |

상품명, 가격, 이미지, 리뷰 통계 변경은 재고 수량을 바꾸지 않으므로 원장 기록 대상이 아니다. 상품 비활성화도 수량 자체를 바꾸지 않으면 원장에 남기지 않는다.

## 테이블 스키마

`inventory_ledgers` 테이블을 추가한다.

| 컬럼 | 정책 |
| --- | --- |
| `inventory_ledger_id` | 내부 PK |
| `product_id` | 재고가 변경된 상품 ID, not null |
| `reason` | `ORDER_PLACED`, `ORDER_CANCELLED`, `PAYMENT_FAILED_RESTORED`, `PENDING_ORDER_EXPIRED`, `ADMIN_ADJUSTED`, `INITIAL_STOCK_SET` |
| `delta_quantity` | 변경 수량, 0 불가 |
| `stock_quantity_after` | 변경 성공 후 상품 재고 수량 |
| `reference_type` | `ORDER`, `ORDER_ITEM`, `PAYMENT`, `ADMIN_ADJUSTMENT`, `PRODUCT` 같은 업무 참조 타입 |
| `reference_id` | 참조 업무 ID |
| `idempotency_key` | 같은 업무 변경의 중복 기록 방지 키, not null |
| `memo` | 관리자 조정 사유나 운영 보정 메모, nullable |
| `created_by` | 시스템 또는 관리자 식별자, nullable |
| `created_at` | 원장 생성 시각 |

기본 인덱스는 `product_id, created_at desc`로 둔다. 운영자가 주문이나 보정 건에서 원장으로 역추적할 수 있도록 `reference_type, reference_id` 인덱스도 둔다. `idempotency_key`는 유니크 제약을 적용해 같은 업무 변경이 재시도되더라도 원장 row가 중복 생성되지 않게 한다.

## Idempotency 정책

원장은 append-only라서 같은 재고 변경이 두 번 기록되면 감사 값이 즉시 왜곡된다. 따라서 업무별로 안정적인 `idempotency_key`를 만든다.

| 변경 사유 | idempotency key |
| --- | --- |
| 주문 생성 차감 | `ORDER_PLACED:{orderId}:{orderItemId}` |
| 사용자 취소 복구 | `ORDER_CANCELLED:{orderId}:{orderItemId}` |
| 결제 실패 복구 | `PAYMENT_FAILED_RESTORED:{orderId}:{orderItemId}` |
| 만료 주문 복구 | `PENDING_ORDER_EXPIRED:{orderId}:{orderItemId}` |
| 관리자 조정 | `ADMIN_ADJUSTED:{adjustmentRequestId}` |
| 초기 재고 | `INITIAL_STOCK_SET:{productId}` |

재고 수량 변경과 원장 저장은 같은 트랜잭션 안에 둔다. 원장 유니크 제약 충돌이 발생하면 해당 업무가 이미 처리됐는지 확인하고, 재고 복구처럼 재시도가 가능한 흐름에서는 중복 복구가 일어나지 않도록 주문 상태나 보정 상태를 먼저 확인한다.

## 정합성 검증

원장은 `products.stock_quantity`를 대체하지 않지만, 운영 검증에는 사용할 수 있어야 한다.

정기 검증 작업은 아래 값을 비교한다.

- 상품 생성 이후 원장 `delta_quantity` 합계
- 원장 기준 계산 재고
- `products.stock_quantity`
- 최근 변경 시각과 마지막 참조 업무

불일치가 발견되면 자동으로 상품 재고를 수정하지 않는다. 우선 운영 보정 대상으로 등록하고, 관리자 조정 기능을 통해 `ADMIN_ADJUSTED` 원장과 함께 보정한다. 자동 수정은 원인 분석 없이 실제 판매 가능 재고를 바꿀 수 있으므로 별도 ADR에서 결정한다.

## 보관 정책

최근 1년 원장은 운영 DB에 보관한다. 1년이 지난 원장은 월 단위로 아카이브하되, 감사와 정산 조회를 위해 아래 필드는 반드시 유지한다.

- `inventory_ledger_id`
- `product_id`
- `reason`
- `delta_quantity`
- `stock_quantity_after`
- `reference_type`
- `reference_id`
- `idempotency_key`
- `created_by`
- `created_at`

아카이브된 원장은 일반 상품 상세나 주문 생성 흐름에서 조회하지 않는다. CS, 감사, 정산, 장애 분석 도구에서만 조회한다. 법적 또는 정산 요구가 더 긴 보관 기간을 요구하면 상품·주문 데이터 보관 정책과 함께 조정한다.

## 적용 범위

- 현재 단계에서는 원장 테이블과 쓰기 코드를 구현하지 않는다.
- 원장 구현 시 `ProductService.updateStock` 또는 별도 재고 애플리케이션 서비스에서 재고 변경과 원장 저장을 같은 트랜잭션으로 묶는다.
- 주문 생성, 사용자 취소, 결제 실패 복구, 만료 주문 복구는 원장 기록 대상이다.
- Outbox는 원장 자체를 대체하지 않는다. 외부 재고 read model이나 알림이 필요하면 원장 저장 후 별도 Outbox 이벤트를 검토한다.
- 검색 read model은 ADR-011과 검색 동기화 설계를 따른다. 검색 인덱스의 재고 값은 구매 가능 여부의 최종 기준이 아니다.

## 결과

재고 원장은 `products.stock_quantity`를 보조하는 append-only 감사 기록으로 설계한다. 주문 가능 여부와 차감 성공 여부는 계속 DB 조건부 업데이트가 판단하고, 원장은 변경 사유, 수량 변화, 변경 후 재고, 참조 업무 ID를 남겨 운영자가 재고 변동을 추적할 수 있게 한다.

이 결정은 현재 모놀리식의 단순한 재고 차감 구조를 유지하면서도, 주문·취소·복구·관리자 조정이 늘어날 때 재고 불일치 원인을 찾고 보정할 수 있는 기반을 제공한다.
