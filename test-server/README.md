# JARVIS Minecraft E17 테스트 서버 가이드

Embedded Brain 전환 계획의 **Phase E17 (최종 실제 E2E)** 검증을 위한 로컬 독립 테스트 서버 환경입니다.

별도의 Node.js, npm, WebSocket 데몬 프로세스 없이 **단일 Paper 1.21.8 서버 JAR와 `jarvisminecraft-paper.jar` 플러그인**만으로 완전하게 동작합니다.

---

## 1. 디렉토리 구성

```text
test-server/
└── paper/
    ├── paper.jar                  # Paper 1.21.8 서버 코어
    ├── plugins/
    │   └── jarvisminecraft-paper.jar  # 빌드된 JARVIS Embedded 플러그인
    ├── eula.txt                   # eula=true
    ├── server.properties          # 온라인 모드, 포트 25565, RCON 비활성화
    ├── ops.json                   # 기본 관리자 계정 (Operator, Steve)
    ├── start-server.ps1           # API 키 자동 주입 및 서버 실행 스크립트 (PowerShell)
    └── start-server.bat           # 윈도우 배치 실행 스크립트
```

---

## 2. 서버 실행 방법

### PowerShell에서 실행
```powershell
.\test-server\paper\start-server.ps1
```

- `test-server/paper/config/.env.local` 파일에 저장된 `OPENAI_API_KEY`와 `TYPESAFE_API_KEY`를 자동으로 읽어와 프로세스 환경 변수로 주입합니다.
- 콘솔이나 로그에는 키 값이 절대 노출되지 않습니다.
- Java 21 런타임(`C:\Program Files\Java\jdk-21` 또는 `$env:JAVA_HOME`)을 자동으로 탐색하여 사용합니다.

---

## 3. 인게임 접속 및 기본 설정

1. **클라이언트 버전**: Minecraft **1.21.8**
2. **서버 주소**: `localhost` 또는 `127.0.0.1:25565`
3. **OP 관리자 권한**:
   - `ops.json`에 이미 `Operator`와 `Steve` 계정이 레벨 4 OP로 사전 등록되어 있습니다.
   - 본인의 마인크래프트 계정 닉네임으로 접속 후, 서버 콘솔 창에서 아래 명령어를 입력하여 OP를 부여할 수 있습니다:
     ```text
     op <본인닉네임>
     ```
   - **주의**: JARVIS는 보안 원칙에 따라 **현재 접속 중인 OP 플레이어의 대화만 접수**합니다.

---

## 4. Phase E17 필수 E2E 검증 시나리오 11가지

서버에 접속하여 인게임 채팅창에서 `자비스 <요청>` 형태로 직접 테스트할 수 있습니다.

### 시나리오 1: 일반 서버 조회 (Read-only)
- **입력**: `"자비스 서버 상태 알려줘"`
- **기대 동작**: Jev가 `SERVER_QUERY`로 분류 ➔ Luna가 `get_server_status` 호출 ➔ 현재 TPS(20.0), 청크 수, 메모리 현황을 한국어로 안내.

### 시나리오 2: 플레이어 조회 (Read-only)
- **입력**: `"자비스 Steve 지금 어디 있어?"`
- **기대 동작**: Jev가 `PLAYER_QUERY`로 분류 ➔ Luna가 `get_player_location` 호출 ➔ Steve의 좌표(X, Y, Z, 월드) 안내.

### 시나리오 3: 관리자 텔레포트 (State-changing)
- **입력**: `"자비스 나를 Steve한테 보내줘"`
- **기대 동작**: Jev가 `ACTION_REQUEST` 분류 ➔ Luna가 `teleport_staff` 제안 ➔ Pre-execution audit 기록 ➔ OP 권한 재검사 통과 ➔ Steve 위치로 즉시 이동 완료.

### 시나리오 4: deop race (보안 경계 검증)
- **테스트 절차**:
  1. OP 플레이어가 자비스에게 요청 전송
  2. 모델 처리 도중 서버 콘솔에서 `deop <플레이어>` 실행
  3. 도구 실행 직전 CommonRuntime/Platform 권한 재확인 단계에서 즉시 차단
  4. 상태 변경 거부 및 stale 응답 전달 차단 확인.

### 시나리오 5: logout race
- **테스트 절차**:
  1. 요청 후 모델 처리 중 클라이언트 접속 종료
  2. Tool 실행 및 응답 전달 전 대상 플레이어 오프라인 감지 ➔ 안전 취소.

### 시나리오 6: Provider 미설치 시 격리
- CoreProtect, WorldGuard, CMI 등의 플러그인이 미설치된 기본 서버에서는 관련 Tool(`rollback`, `region` 등)이 모델 프롬프트 및 라우팅에 일절 노출되지 않음.

### 시나리오 7: Jev 장애 fallback
- TypeSafe Jev 장애 또는 저신뢰도 시 읽기 전용 fallback 모드로 자동 전환 ➔ 상태 변경 도구(`teleport_staff` 등) 원천 차단.

### 시나리오 8: Luna 장애 안전 실패
- OpenAI 통신 장애 시 서버 상태를 자의적으로 추측하지 않고 안전 오류 메시지 전송.

### 시나리오 9: Audit 장애 fail-closed
- 감사 로그 디렉토리 권한 박탈 등으로 audit 기록 실패 시, 상태 변경 도구 실행을 즉시 거부.

### 시나리오 10: Tool 타임아웃 처리
- 읽기 전용 도구 타임아웃 시 `TIMEOUT` 처리, 상태 변경 도구 타임아웃 시 `OUTCOME_UNKNOWN` 처리 및 자동 재시도 금지.

### 시나리오 11: AI 처리 중 서버 틱 지연(Tick block) 없음
- 모델 HTTP 호출 및 추론이 비동기 worker 스레드에서 처리되므로, Minecraft 메인 루프 TPS(20.0)에 영향을 주지 않음.

---

## 5. 감사 로그 확인

- 감사 로그 저장 위치:
  ```text
  test-server/paper/plugins/JarvisMinecraft/audit/*.jsonl
  ```
- 모든 실행 도구 내역, 사전 감사(`PRE_EXECUTION`), 사후 감사(`OK`)가 실시간 JSONL 형태로 기록되며 API 키는 마스킹 처리됩니다.
