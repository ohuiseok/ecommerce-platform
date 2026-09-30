# E-Commerce Monolith

Spring Boot 기반 단일 이커머스 애플리케이션입니다. 회원, 상품, 주문 도메인을 하나의 배포 단위에서 제공합니다.

## 현재 구조

```text
src/main/java/com/ecommerce/monolith/
├── EcommerceApplication.java
├── common/      # JPA auditing, 도메인 예외(ErrorCode/BusinessException), OpenAPI 설정
├── security/    # JWT 인증 필터
├── user/        # 회원가입, 로그인, 사용자 관리
├── product/     # 상품 조회, 검색, 재고 관리
├── cart/        # 장바구니 조회 및 상품 담기/수정/삭제
├── order/       # 주문 생성, 체크아웃, 조회, 상태 변경, 취소
├── payment/     # 모의 PG 연동 결제 요청/조회/취소
├── coupon/      # 쿠폰 발급, 보유 쿠폰 조회, 체크아웃 시 할인 적용
└── review/      # 구매 검증 기반 리뷰 작성/조회/수정/삭제, 상품 평점 집계
```

## 기술 스택

- Java 17
- Spring Boot 3.2
- Spring Web
- Spring Security
- Spring Data JPA
- PostgreSQL
- JWT
- springdoc-openapi (Swagger UI)
- Testcontainers (통합 테스트)
- Docker Compose

## 실행 방법

### Docker Compose

```bash
ADMIN_EMAIL=admin@example.com ADMIN_PASSWORD=admin123 docker-compose up --build
```

애플리케이션:

- API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health
- PostgreSQL: localhost:5432

### 개발용 DB만 실행

```bash
docker-compose -f docker-compose.dev.yml up -d
```

그 다음 로컬에서 애플리케이션을 실행합니다.

```bash
./gradlew bootRun
```

Gradle wrapper가 포함되어 있어 로컬 Gradle 설치 없이 실행할 수 있습니다.

## 환경 변수

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | 애플리케이션 포트 |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/ecommerce` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | DB 사용자 |
| `SPRING_DATASOURCE_PASSWORD` | `postgres` | DB 비밀번호 |
| `JWT_SECRET` | 개발용 기본값 | JWT 서명 키 |
| `JWT_EXPIRATION` | `86400000` | Access token 만료 시간(ms) |
| `SPRING_PROFILES_ACTIVE` | 없음 | 운영 실행 시 `prod` 지정 |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `update`, `prod` 프로필은 `validate` | Hibernate DDL 전략 |
| `ADMIN_EMAIL` | 없음 | 시작 시 생성/갱신할 관리자 이메일 |
| `ADMIN_PASSWORD` | 없음 | 시작 시 생성/갱신할 관리자 비밀번호 |
| `ADMIN_NAME` | `Administrator` | 관리자 이름 |

운영 환경에서는 `SPRING_PROFILES_ACTIVE=prod`, `JWT_SECRET`, DB 계정을 반드시 별도로 설정해야 합니다.
관리자 계정은 `ADMIN_EMAIL`과 `ADMIN_PASSWORD`가 모두 설정된 경우에만 생성되거나 갱신됩니다.

## API 경로

모든 API는 단일 앱의 `/api` 하위로 제공됩니다.

### User

- `POST /api/users/register`
- `POST /api/users/login`
- `GET /api/users/{userId}`
- `GET /api/users/email/{email}`
- `PUT /api/users/{userId}`

### Product

- `POST /api/products`
- `GET /api/products`
- `GET /api/products/{productId}`
- `GET /api/products/search?keyword=...`
- `GET /api/products/category/{category}`
- `GET /api/products/brand/{brand}`
- `GET /api/products/categories`
- `GET /api/products/brands`
- `GET /api/products/price-range?minPrice=...&maxPrice=...`
- `GET /api/products/{productId}/stock`
- `PUT /api/products/{productId}`
- `PUT /api/products/{productId}/stock`
- `DELETE /api/products/{productId}`

상품 조회 `GET /api/products/**`는 공개 API이고, 생성/수정/삭제/재고 변경은 `ADMIN` 권한 JWT가 필요합니다.

### Cart

- `GET /api/cart`
- `POST /api/cart/items`
- `PUT /api/cart/items/{cartItemId}`
- `DELETE /api/cart/items/{cartItemId}`
- `DELETE /api/cart`

장바구니 API는 JWT 인증이 필요하며 항상 토큰의 사용자 본인 장바구니만 조회/수정합니다.

### Order

- `POST /api/orders` (상품을 직접 지정해 주문 생성)
- `POST /api/orders/checkout` (장바구니 기반 체크아웃, 성공 시 장바구니 비움)
- `GET /api/orders/{orderId}`
- `GET /api/orders/user/{userId}`
- `GET /api/orders/status/{status}`
- `PUT /api/orders/{orderId}/status`
- `DELETE /api/orders/{orderId}`

주문 API는 JWT 인증이 필요합니다. 사용자별 조회, 단건 조회, 취소는 본인 주문 또는 관리자만 접근할 수 있습니다. 상태별 주문 조회와 주문 상태 변경은 관리자만 접근할 수 있습니다. 주문은 생성 시 `PENDING` 상태이며, 결제가 완료되면 `CONFIRMED`로 전환됩니다. `POST /api/orders`와 `/checkout` 요청에 `userCouponId`를 함께 보내면 보유 쿠폰이 적용되어 `originalAmount`에서 `discountAmount`만큼 할인된 `totalAmount`로 주문이 생성되고, 주문을 취소하면 쿠폰은 다시 사용 가능한 상태로 복원됩니다.

### Payment

- `POST /api/payments` (결제 요청 — 실제 PG 대신 모의 클라이언트로 승인/거절을 재현)
- `GET /api/payments/{paymentId}`
- `GET /api/payments/order/{orderId}`
- `DELETE /api/payments/{paymentId}` (결제 취소/환불, 관리자 전용)

결제 API는 JWT 인증이 필요하며 본인 주문 또는 관리자만 접근할 수 있습니다. `CARD` 결제는 모의 카드번호 마지막 자리가 짝수이면 승인, 홀수면 거절되도록 시뮬레이션합니다. 결제가 승인되면 연결된 주문 상태가 자동으로 `CONFIRMED`로 바뀝니다.

### Coupon

- `POST /api/coupons` (쿠폰 생성, 관리자 전용)
- `GET /api/coupons` (현재 발급 가능한 쿠폰 목록)
- `GET /api/coupons/{couponId}`
- `POST /api/coupons/{couponId}/issue` (본인에게 쿠폰 발급)
- `GET /api/coupons/my` (내가 보유한 쿠폰 목록)

쿠폰 조회는 공개 API이고, 발급/보유 쿠폰 조회는 JWT 인증이 필요합니다. 쿠폰은 정액(`FIXED_AMOUNT`) 또는 정률(`PERCENTAGE`, `maxDiscountAmount`로 한도 설정 가능) 할인을 지원하며 `minOrderAmount`, `validFrom`~`validUntil`, `issueLimit`(전체 발급 수량)을 검증합니다. 발급 수량 제한은 재고 차감과 동일하게 조건부 UPDATE 쿼리로 동시 발급 시 초과 발급을 방지합니다. 한 사용자는 동일 쿠폰을 한 번만 발급받을 수 있고, 쿠폰 1개는 주문 1건에만 사용할 수 있습니다.

### Review

- `POST /api/reviews` (리뷰 작성)
- `GET /api/reviews/{reviewId}`
- `GET /api/reviews/product/{productId}` (상품별 리뷰 목록)
- `GET /api/reviews/my` (내가 쓴 리뷰 목록)
- `PUT /api/reviews/{reviewId}`
- `DELETE /api/reviews/{reviewId}`

리뷰 조회는 공개 API이고, 작성/내 리뷰 조회는 JWT 인증이 필요합니다. 리뷰 작성 시 `orderId`로 넘긴 주문이 본인 소유이고 취소되지 않았으며 해당 상품을 포함하는지 검증하는 방식으로 실제 구매자만 리뷰를 남길 수 있게 합니다. 수정/삭제는 작성자 본인 또는 관리자만 가능합니다. 상품별 평균 평점(`averageRating`)과 리뷰 수(`reviewCount`)는 리뷰 작성/수정/삭제 시마다 재계산되어 `Product` 응답에 함께 노출됩니다. 한 사용자는 같은 상품에 리뷰를 하나만 남길 수 있습니다.

### 오류 응답

모든 도메인 예외는 `ErrorCode`(상태 코드 + 메시지)를 통해 일관된 형식으로 반환됩니다. 예: 리소스 없음 `404`, 중복 이메일/재고 부족/이미 처리된 결제 `409`, 잘못된 요청 `400`.

## 인증 흐름

회원가입:

```bash
curl -X POST http://localhost:8080/api/users/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "password123",
    "name": "홍길동",
    "phoneNumber": "010-1234-5678"
  }'
```

로그인:

```bash
curl -X POST http://localhost:8080/api/users/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "password123"
  }'
```

인증 API 호출:

```bash
curl http://localhost:8080/api/orders/1 \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

## 남은 개선 과제

- 운영용 DB 마이그레이션 도입: Flyway 또는 Liquibase
- CI 파이프라인 구성 (빌드/테스트 자동화)

## 테스트

```bash
./gradlew test
```

- 서비스 단위 테스트(Mockito): User/Product/Order/Cart/Payment/Coupon/Review
- 통합 테스트(Testcontainers PostgreSQL, Docker 필요):
  - `ProductConcurrencyIntegrationTest`: 재고보다 많은 동시 요청에도 오버셀이 발생하지 않는지 검증 (조건부 UPDATE 쿼리 동시성 테스트)
  - `OrderCheckoutIntegrationTest`: 회원가입 → 로그인 → 관리자 상품 등록 → 장바구니 담기 → 체크아웃 → 결제까지 HTTP 계층 전체 흐름 검증 (결제 성공/실패, 재결제 방지, 빈 장바구니 체크아웃 방지 포함)
  - `CouponCheckoutIntegrationTest`: 쿠폰 발급 → 체크아웃 할인 적용 → 주문 취소 시 쿠폰 복원, 최소 주문 금액 미충족 시 체크아웃 거부 검증
  - `ReviewIntegrationTest`: 체크아웃 → 리뷰 작성 → 상품 평점 반영 → 중복/미구매 리뷰 거부 → 리뷰 삭제 시 평점 재계산 검증

## 빌드

```bash
./gradlew clean bootJar
```

헬스체크:

```bash
./scripts/health-check.sh
```
