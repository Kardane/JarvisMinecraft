# Minecraft JARVIS 기획 검토

검토일: 2026-09-23. 대상: [원안](Minecraft_JARVIS_PLAN.md). 구현 기준: [작업명세서](Minecraft_JARVIS_WORK_SPEC.md).

## 검토 결론

외부 Brain, 플랫폼 Adapter, 제한된 Tool, Capability 탐지라는 방향은 유지한다. 다만 원안은 v0.1의 범위가 넓고, 권한·승인·스레드·실패 처리 계약이 부족하다. 공통 조회 기능을 먼저 세 플랫폼에서 완성하고, Paper 플러그인 조회를 다음 단계, 변경 작업 확대를 그 이후로 나눈다.

저장소 확인 시 구현 코드·빌드 설정은 없고 기획서와 `.codegraph` 보조 파일만 존재했다. 따라서 아래 내용은 기존 구현의 결함 진단이나 실행 검증 결과가 아닌 설계 검토다. 인덱스를 새로 만들거나 구현 코드를 추가하지 않았다.

## 유지·추가·변경·제외

| 원안 위치 | 판단 | 수정 방향과 이유 |
|---|---|---|
| §3 플랫폼 | 유지·구체화 | Paper / Fabric / NeoForge 유지. 최소 Minecraft 1.21.8, 첫 검증 대상은 각 플랫폼의 1.21.8. 이후 버전은 검증된 조합만 지원 목록에 추가한다. |
| §5 분리 구조 | 유지·정정 | AI·네트워크는 비동기, 월드·엔티티 접근은 플랫폼이 요구하는 서버 스레드. 프로세스 분리만으로 tick 영향이 사라지는 것은 아니다. |
| §6, §24, §29 | 단계 분리 | 공통 기능 v0.1 → CoreProtect/WorldGuard 조회 v0.1.1 → CMI 선택 확장·승인형 롤백 v0.2. 원안의 v1/v0.1 혼용을 없앤다. |
| §9, §27 AI Routing | 추가·앞당김 | 생성 모델은 `gpt-6-luna`, 분류 모델은 TypeSafe AI Jev를 초기부터 사용. Jev를 나중의 미지정 소형 모델로 남겨두지 않는다. |
| §10 채팅 | 보완 | OP 대화 진입·유지·종료 규칙과 비공개 전달을 명시. 활성 대화 중 일반 채팅이 잘못 전송되지 않도록 명시적 종료와 공개 메시지 탈출 규칙을 둔다. |
| §13~15 권한·승인 | 축소·강화 | 초기에는 OP 여부가 필수 조건. 권한 노드·그룹만으로 비OP 접근을 열지 않는다. 실행 직전 OP 재검사와 서버 측 승인 토큰 검증을 둔다. |
| §16 지표 | 수정 | 불가능한 TPS/MSPT를 0으로 반환하지 않는다. null, 단위, 측정 시각·구간, 출처를 함께 반환한다. |
| §18 프로토콜 | 추가 | 버전 협상, 인증, 세션 격리, deadline, toolCallId/actionId, 중복 방지, 재접속, 오류 분류. 자체 WebSocket 구현은 금지한다. |
| §19~20 보안·감사 | 추가 | 비OP 입력 외부 전송 금지, 외부 전송 최소화, 로그 마스킹·보존·용량 제한, 프롬프트 주입을 통한 권한 우회 방지. |
| §21 설정 | 수정 | 롤백 기본 false, `CHANGE_ME`로 시작 금지, API 키는 Brain 환경 변수만 사용. 승인 검사를 끄는 운영 설정은 제공하지 않는다. |
| §23 저장소 | 축소 | v0.1 대화는 메모리 TTL, 감사는 회전 JSONL. SQLite는 영속 승인/실행 저널이 필요한 v0.2에 도입한다. |
| §25 운영진 전용 응답 | 앞당김 | v0.1부터 요청 OP에게만 답한다. 다른 OP에게도 자동 공유하지 않는다. |
| §27 자체 History/Regions | 제외 | 기존 보호·기록 시스템을 재구현하지 않는다. 검증된 Provider가 없으면 미지원으로 보고한다. |
| §28 하루 요약·원인 추론 | 후속 | 이력·시계열 수집 전에는 오늘의 사건이나 과거 렉 원인을 설명할 근거가 없다. 현재 상태와 과거 분석을 구분한다. |

## 재사용 경계

| 기능 | 재사용 대상 | JARVIS가 구현할 부분 |
|---|---|---|
| 생성·Tool calling | 공식 OpenAI SDK / Responses API | 도메인 Tool 스키마, 제한된 실행 루프, 결과 근거 연결 |
| 의도 분류 | 공식 `@typesafe-ai/sdk` / Jev | 작은 분류 집합, 한국어 평가, 불확실성 처리 |
| 채팅·텔레포트·권한 | 각 플랫폼 API 및 기존 스케줄러 | 공통 계약과 플랫폼별 얇은 변환 |
| 이력 | CoreProtect 공개 API | 범위 제한, 페이징·출처 변환 |
| 보호구역 | WorldGuard 공개 query API | 요청 주체·위치 연결, 결과 설명 |
| CMI | 검증한 공개 API만 | 기본 플랫폼 기능에 없는 정보만 추가 |
| JSON·통신·검증 | 기존 serializer, WebSocket, JSON Schema validator | 버전 있는 메시지 계약 및 인증 정책 |

플러그인 내부 DB 조회, 커맨드 출력 파싱, 리플렉션 기반 내부 접근, 자체 ORM·에이전트 프레임워크·분산 작업 큐는 초기 범위에 넣지 않는다. CMI 없이도 공통 조회와 자기 텔레포트가 동작해야 한다.

## Jev 반영 시 주의점

Jev는 상태와 제한된 질문을 받아 구조화된 판단을 반환하는 모델이다. 자유 문장 작성은 Luna가 담당한다. 이 구분은 [TypeSafe 소개](https://docs.typesafe.ai/introduction)에 근거한다.

Node/TypeScript Brain은 공식 [`@typesafe-ai/sdk`](https://docs.typesafe.ai/sdk/javascript)를 사용한다. 초기 평가 버전은 `jev-1.13.0`으로 고정하고 실제 응답 모델 ID를 기록한다. 한국어는 별도 평가가 필요하며, 별칭 변경에 따른 결과 변화도 관리해야 한다. [모델 문서](https://docs.typesafe.ai/models)

confidence는 정답 보증이나 승인 증거가 아니다. 분류 신뢰도가 낮으면 Luna의 조회 경로 또는 재질문으로 넘어간다. 수치 계산·기간 비교·OP 확인·승인 판정은 코드로 수행한다. [Confidence](https://docs.typesafe.ai/confidence), [알려진 한계](https://docs.typesafe.ai/model-jaggedness/jev-1.13)

## 검증한 공식 근거와 남은 확인

| 근거 | 설계에 반영한 내용 | 구현 전 남은 확인 |
|---|---|---|
| [GPT-6 Luna](https://developers.openai.com/api/docs/models/gpt-6-luna), [Function calling](https://developers.openai.com/api/docs/guides/function-calling) | 모델명을 고정하고 Responses의 Tool 호출 흐름 재사용 | 실제 계정 접근, SDK 버전, reasoning 옵션·오류 응답 smoke |
| [Paper Java 요구사항](https://docs.papermc.io/paper/getting-started/), [NeoForge 1.21.8](https://docs.neoforged.net/docs/1.21.8/gettingstarted/) | 1.21.8 기준 Java 21 | Paper/Fabric/NeoForge 정확한 배포·로더·API 버전 매트릭스 |
| [Paper chat events](https://docs.papermc.io/paper/dev/chat-events/) | 비동기 채팅 핸들러에서 Bukkit 월드 API를 바로 호출하지 않음 | 각 플랫폼 취소·비공개 전달·서명된 채팅 동작 |
| [CoreProtect API](https://docs.coreprotect.net/api/) | 공개 API를 통한 이력 조회·후속 롤백 | 1.21.8 호환 플러그인/API 버전, 조회 및 preview 의미·스레드 계약 |
| [WorldGuard protection query](https://worldguard.enginehub.org/en/latest/developer/regions/protection-query/) | 보호 판정을 직접 계산하지 않고 query 사용 | bypass, owner/member, priority, parent, WorldEdit 의존성 조합 |

문서 확인은 제품 호환성이나 라이브 API 성공의 증거가 아니다. 특히 CoreProtect Fabric 지원과 CMI warning API는 확인되지 않았으므로 명세에 지원 기능으로 확정하지 않는다. 비용·성능은 광고 수치를 제품 보장으로 옮기지 않고 구현 단계에서 측정한다.
