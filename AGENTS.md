# 저장소 작업 지침

## 프로젝트와 문서 기준

- 이 저장소는 Minecraft 서버용 JARVIS를 구현한다. 서버 플랫폼 어댑터(Paper, Fabric, NeoForge), 공통 Java 모듈, TypeScript Brain, 언어 중립 protocol contract를 함께 관리한다.
- 제품 범위와 완료 조건은 [`docs/Minecraft_JARVIS_WORK_SPEC.md`](docs/Minecraft_JARVIS_WORK_SPEC.md)를 기준으로 한다. 초기 아이디어인 [`docs/Minecraft_JARVIS_PLAN.md`](docs/Minecraft_JARVIS_PLAN.md)와 충돌하면 작업 명세서를 따른다.
- 메시지 envelope와 연결 규칙은 [`docs/protocol.md`](docs/protocol.md), Tool 입력·결과·범위는 [`docs/tools.md`](docs/tools.md), 버전 및 빌드 기준은 [`docs/compatibility.md`](docs/compatibility.md)와 [`docs/build.md`](docs/build.md)를 따른다. 동일 계약을 이 문서에 복제하지 말고 해당 문서를 갱신한다.
- 구현 전 현재 branch, worktree, 변경 파일을 확인한다. 이미 있는 staged, modified, untracked 파일은 보존하고 요청된 경로만 수정한다.
- 2026-09-25 동기화 기준: GitHub `main`, 로컬 `main`, `origin/main`이 모두 `8df2b04` (T10 acceptance merge)를 가리키며 commit divergence는 없다. 이 checkout은 T03~T10 구현을 포함한다. 현재 로컬 검증 결과는 [`docs/architecture.md`](docs/architecture.md)와 `tests/acceptance/out/`에 기록했다. 전체 release gate는 `PARTIAL`이다. A09 holdout은 95% 목표 미달, A10 provider loop는 통과했고, A11 세 플랫폼 로컬 측정은 통과했지만 전용 fixed-load host 확인이 남았다. 원격 merge 당시 기준은 [T10 acceptance report](https://github.com/Kardane/JarvisMinecraft/blob/8df2b04/docs/verification/T10_V01_REPORT.md)을 참조한다.
- 로컬 snapshot과 원격 진행 상태가 다르면 오래된 tracking ref를 현재 원격 상태로 간주하지 않는다. 사용자가 의도한 최신 worktree/증거를 찾고, 기존 구현이나 테스트를 다시 만들거나 checkout을 임의 reset하지 않는다. 이 로컬 checkout에 없는 remote-only source를 수정할 때에는 현재 task의 checkout 범위부터 확인한다.

## 기술 및 모듈 경계

- 현재 고정 기준은 Minecraft 1.21.8, Java 21, Gradle Wrapper 8.14.5, Node.js 24다. 버전 변경은 호환성 근거와 함께 관련 결정 문서 및 lock 파일을 검토한다.
- 공통 Java 모듈은 Bukkit/Paper, Fabric, NeoForge, NMS API를 import하지 않는다. Minecraft 플랫폼 객체를 Brain이나 transport 경계 밖으로 넘기지 않고 제한된 DTO snapshot으로 변환한다.
- 플랫폼별 이벤트, 권한 확인, 스케줄러 연결, 공식 서버 API 호출은 각 Adapter가 담당한다. 다른 플랫폼 Adapter의 구현을 직접 참조하지 않는다.
- Brain은 세션·요청 예산·분류·응답 생성·Tool 제안을 조립한다. Adapter는 현재 서버 상태와 권한을 근거로 capability 및 Tool을 다시 확인하고 최종 실행을 결정한다.
- protocol 구조를 바꾸면 JSON Schema, valid/invalid fixtures, manifest, protocol 문서, 소비자(Java/TypeScript)를 함께 갱신한다. 구현 편의를 이유로 wire contract나 fixture 의미를 임의로 느슨하게 하지 않는다.

## 보안과 런타임 규칙

- v0.1 상호작용자는 현재 접속 중이고 서버가 OP로 인정하는 플레이어다. Brain 또는 모델 payload는 권한 증거가 아니다. 접수, Tool 실행, 결과 전달 경계에서 서버의 권한을 확인한다.
- 모델 출력은 신뢰할 수 없는 제안으로 처리한다. 등록된 Tool allowlist, capability, strict argument validation, 범위 제한을 모두 통과한 요청만 처리한다.
- 임의 콘솔 명령, SQL, 코드 실행, 임의 파일 접근, 다른 플레이어 강제 이동, ban/warn 및 rollback을 추가하지 않는다. 범위 확장은 해당 작업 명세서와 계약에서 먼저 승인된 뒤 진행한다.
- AI/network/disk/DB I/O를 Minecraft tick 또는 server thread에서 기다리지 않는다. Minecraft API는 플랫폼이 요구하는 execution context에서만 호출한다. 비동기 결과를 보낼 때도 플레이어의 OP·접속 상태를 다시 확인한다.
- Brain의 API 키는 Brain 환경에서만 읽는다. 공유 비밀은 loopback WebSocket handshake의 `X-Jarvis-Secret` 헤더로만 전달한다. 비밀, 전체 프롬프트, 불필요한 개인 정보는 로그나 모델 입력에 넣지 않는다.
- Jev가 실패하거나 불확실하면 변경 Tool을 제공하지 않는다. Luna가 실패하면 사실을 만들지 않고 정해진 오류 경로를 사용한다. 변경 요청의 outcome이 불명확하면 `OUTCOME_UNKNOWN`으로 남기고 자동 재실행하지 않는다.
- Paper 외부 Provider는 공개 API 및 실제 runtime capability 확인을 거친 optional 기능으로 취급한다. 플러그인이 설치되어 있다는 이유만으로 Tool을 활성화하지 않는다.

## 변경 및 확인

- 문서와 코드에서 한국어를 기본으로 사용하고, Java/Kotlin/TypeScript 식별자, 프로토콜 필드, Tool 이름, 모델·SDK 버전은 원문 표기를 보존한다.
- 기존 CI와 검증 스크립트를 변경된 계약에 맞춰 갱신한다. 사용자가 테스트나 검증 실행을 요청하지 않았다면 테스트, 빌드, 서버 또는 유료 모델 호출을 실행하지 않는다. 수행하지 않은 검증을 통과했다고 보고하지 않는다.
- 상태 보고에서는 문서/정적 분석, 빌드, 서버 기동, 플랫폼 UI, 외부 모델 호출, 실제 E2E 결과를 구분한다. 각 결론을 현재 checkout의 실제 증거에 연결한다.
- 외부 배포, publish, push, merge, 유료 API 호출, 서버 재시작처럼 저장소 밖에 영향을 주는 작업은 사용자의 명시 요청 범위에서만 진행한다.
