# h2 전용 마이그레이션

`common/` 은 H2(MODE=PostgreSQL)·PostgreSQL 공용 문법이다. 이 폴더에는 h2 에서만 필요한 스크립트(인덱스 옵션, 확장 등)를 둔다.
버전 번호는 common 과 겹치지 않게 `V{n}_1__...` 처럼 소수 버전을 쓴다. 현재 없음.
