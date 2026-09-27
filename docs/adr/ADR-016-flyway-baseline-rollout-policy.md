# ADR-016: Flyway baseline 및 배포 호환 정책

## 상태

Accepted

## 배경

현재 애플리케이션은 Hibernate `ddl-auto=update`를 기본값으로 사용한다. 개발 초기에는 엔티티 변경을 빠르게 반영할 수 있지만, 운영 데이터가 있는 환경에서는 어떤 DDL이 언제 적용됐는지 추적하기 어렵고 배포마다 스키마 변경 결과가 달라질 수 있다.

Phase 4에서는 Flyway를 도입해 스키마 변경을 명시적인 마이그레이션으로 관리한다. 다만 이미 Hibernate가 생성한 기존 DB가 있을 수 있으므로, Flyway 도입 첫 배포는 기존 스키마를 깨뜨리지 않는 baseline 정책과 호환 배포 순서를 먼저 정해야 한다.

## 결정

Flyway 도입은 기존 DB를 `V1` baseline으로 인정하고, 이후 변경은 `V2`부터 증분 마이그레이션으로 관리한다.

| 항목 | 결정 |
| --- | --- |
| baseline 버전 | `1` |
| baseline 설명 | `baseline existing schema` |
| 신규 DB 초기화 | `V1__baseline_existing_schema.sql`로 현재 엔티티 기준 전체 스키마 생성 |
| 기존 DB 편입 | `baseline-on-migrate=true`를 사용해 기존 운영 DB를 버전 `1`로 등록 |
| 이후 변경 | `V2__...`부터 작은 단위의 forward-only 마이그레이션으로 추가 |
| 운영 Hibernate DDL | Flyway 적용 후 운영 프로필은 `ddl-auto=validate` 사용 |
| 롤백 방식 | 적용된 마이그레이션 파일 수정 금지, 보정용 새 마이그레이션으로 복구 |

기존 DB와 신규 DB의 기준선을 같게 유지하기 위해 `V1__baseline_existing_schema.sql`에는 현재 엔티티가 요구하는 테이블, 제약, 인덱스를 모두 담는다. 기존 운영 DB는 이미 같은 스키마를 가지고 있다고 보고 Flyway schema history에 baseline만 기록한다. 신규 환경은 같은 `V1` 파일을 실행해 동일한 기준 스키마를 만든다.

## 배포 순서

Flyway 전환은 두 단계로 나눈다.

1. Flyway 의존성과 설정을 추가하고, 기존 DB에서는 baseline만 기록되도록 배포한다.
2. 운영 프로필의 Hibernate `ddl-auto`를 `validate`로 바꿔 엔티티와 DB 스키마가 어긋나면 기동 시 실패하게 한다.

두 단계를 분리하는 이유는 Flyway schema history 생성, baseline 등록, 신규 DB 초기화가 정상 동작하는지 먼저 확인하기 위해서다. `ddl-auto=validate` 전환은 baseline 적용이 검증된 뒤 진행한다.

## 호환 정책

운영 배포 중 구버전 애플리케이션과 신버전 애플리케이션이 잠시 함께 떠 있을 수 있으므로, 마이그레이션은 가능한 한 expand-and-contract 방식으로 작성한다.

- 컬럼 추가는 먼저 nullable 또는 기본값이 있는 형태로 배포한다.
- 애플리케이션이 새 컬럼을 읽고 쓰기 시작한 뒤 not null, unique, check 제약을 별도 마이그레이션으로 강화한다.
- 컬럼 삭제와 이름 변경은 즉시 수행하지 않고, 코드에서 사용을 제거한 뒤 별도 배포에서 정리한다.
- 인덱스 추가는 기존 쿼리와 쓰기 부하를 고려해 별도 마이그레이션으로 둔다.
- 대량 백필은 한 트랜잭션에 오래 묶지 않고 배치 작업 또는 별도 운영 절차로 분리한다.

DB 스키마를 바꾸는 기능은 Flyway 마이그레이션과 엔티티 변경을 같은 작업에 포함한다. 스키마 변경이 없는 순수 코드 변경은 마이그레이션을 추가하지 않는다.

## 마이그레이션 작성 규칙

마이그레이션 파일은 `src/main/resources/db/migration` 아래에 둔다. 파일 이름은 Flyway 기본 규칙인 `V{version}__{description}.sql`을 따른다.

초기 규칙은 다음과 같다.

- 한 파일은 하나의 작은 목적만 가진다.
- 이미 배포된 마이그레이션은 수정하지 않는다.
- DDL은 PostgreSQL 기준으로 작성한다.
- 제약 이름과 인덱스 이름은 명시적으로 부여한다.
- `CHECK`, `UNIQUE`, `FOREIGN KEY` 같은 불변식은 애플리케이션 검증과 별개로 DB 최종 방어선으로 둔다.
- 테스트는 Flyway가 신규 DB에 전체 마이그레이션을 적용한 상태에서 실행되도록 유지한다.

## 운영 검증

Flyway 도입 후 배포 전 검증은 다음을 포함한다.

- 깨끗한 PostgreSQL DB에서 `./gradlew test`가 통과한다.
- 기존 스키마가 있는 DB에서 `baseline-on-migrate`가 schema history를 생성하고 애플리케이션이 기동한다.
- 운영 프로필에서 `ddl-auto=validate`를 사용할 때 엔티티와 DB 스키마 불일치가 없음을 확인한다.
- 신규 마이그레이션 추가 시 같은 마이그레이션을 두 번 적용해도 Flyway가 중복 실행하지 않는지 확인한다.

## 적용 범위

- 이 ADR은 Flyway 도입의 baseline 버전, 기존 DB 편입 방식, 배포 호환 정책을 정한다.
- Flyway 의존성, 설정, `V1` baseline SQL 추가는 후속 작업에서 구현한다.
- 운영 프로필의 `ddl-auto=validate` 전환은 baseline 마이그레이션 추가 뒤 별도 작업으로 진행한다.
- 재고, 주문, 결제 금액의 `CHECK` 제약 추가는 baseline 이후 별도 마이그레이션으로 진행한다.

## 결과

기존 운영 DB는 Flyway 버전 `1`의 baseline으로 편입하고, 신규 DB는 같은 `V1` 마이그레이션으로 현재 스키마를 생성한다. 이후 스키마 변경은 `V2`부터 forward-only 마이그레이션으로 관리한다.

이 결정은 Hibernate 자동 DDL에 의존하던 흐름을 명시적인 변경 이력으로 전환하면서도, 기존 데이터가 있는 환경의 첫 Flyway 도입 위험을 낮춘다.
