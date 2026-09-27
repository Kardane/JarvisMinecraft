# Documentation

현재 유지하는 문서는 역할별로 분리한다.

- [architecture.md](architecture.md) — runtime 구조와 안전 경계
- [operations.md](operations.md) — 설정, 운영, 장애 대응, `/jm status`
- [testing.md](testing.md) — 현재 테스트/릴리스 검증 기준
- [build.md](build.md) — 빌드 환경, artifact, dependency locking
- [compatibility.md](compatibility.md) — 지원 플랫폼/버전
- [tools.md](tools.md) — Tool catalog와 경계
- [protocol.md](protocol.md) — 보존된 protocol/fixture 계약
- [later-todo.md](later-todo.md) — 아직 구현되지 않은 작업
- [decisions/](decisions/) — 유지되는 ADR

과거 날짜별 verification report는 repository에서 제거했다. 현재 상태를 판단할 때는 코드, CI, 위 문서와 committed regression assets를 기준으로 한다.
