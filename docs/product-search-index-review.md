# 상품 검색 쿼리와 인덱스 점검

## 점검일

- 2026-09-12

## 현재 조회 경로

`ProductRepository` 기준으로 사용자 상품 조회는 대부분 `status = ACTIVE` 조건을 포함한다.

- `GET /api/products`: `status` 필터와 pageable 정렬
- `GET /api/products/category/{category}`: `category + status` 필터
- `GET /api/products/brand/{brand}`: `brand + status` 필터
- `GET /api/products/price-range`: `price BETWEEN minPrice AND maxPrice + status` 필터
- `GET /api/products/low-stock`: `stock_quantity < threshold + status` 필터
- `GET /api/products/search`: `name`, `description`, `category`, `brand`에 `LOWER(... LIKE '%keyword%')` 검색과 `status` 필터

## 적용한 인덱스

`products` 테이블에 JPA `@Table(indexes = ...)`로 아래 복합 인덱스 후보를 적용했다.

- `idx_products_status_created_at`: 기본 목록에서 활성 상품을 페이지 단위로 조회하는 경로
- `idx_products_status_category_created_at`: 카테고리별 활성 상품 목록
- `idx_products_status_brand_created_at`: 브랜드별 활성 상품 목록
- `idx_products_status_price_created_at`: 가격 범위별 활성 상품 목록
- `idx_products_status_stock_quantity`: 재고 부족 상품 조회

현재 프로젝트는 `ddl-auto=update`를 사용하므로 엔티티 메타데이터 기반 인덱스가 생성된다. 운영 마이그레이션 도입 시에는 동일 인덱스를 Flyway 또는 Liquibase 변경 이력으로 옮겨 관리한다.

## 남은 병목과 튜닝 후보

키워드 검색은 `LOWER(... LIKE '%keyword%')` 형태라 일반 B-tree 인덱스 효과가 제한적이다. 상품 수가 작을 때는 유지하되, 검색 트래픽 또는 상품 수가 늘면 아래 순서로 검토한다.

1. PostgreSQL `pg_trgm` 기반 GIN 인덱스와 `ILIKE`/similarity 검색 적용
2. 카테고리와 브랜드는 부분 문자열 검색 대상에서 제외하고 별도 필터로 분리
3. 검색 p95 300ms 초과 또는 상품 10만 건 이상일 때 검색 read model 도입 검토

## 실행 계획 확인 기준

운영 DB 또는 운영 유사 데이터에서 다음 쿼리를 `EXPLAIN (ANALYZE, BUFFERS)`로 확인한다.

- 활성 상품 목록의 정렬 조건별 index scan 여부
- 카테고리, 브랜드, 가격 범위 조회의 rows 제거 비율
- 키워드 검색에서 sequential scan이 허용 가능한 데이터 크기 안에 머무는지 여부

인덱스 추가 후 쓰기 비용이 증가할 수 있으므로 상품 등록/수정 빈도가 높아지는 경우 인덱스 사용률과 insert/update 지연을 함께 본다.
