# Historical T10 acceptance evidence

E14 이후 이 디렉터리는 실행 가능한 Node acceptance harness가 아니라 **과거 T10 evidence archive** 역할을 한다.

보존 항목:

- `out/paper.json`
- `out/fabric.json`
- `out/neoforge.json`
- `out/a09-jev-report.json`
- `out/a10-live-models-report.json`

기존 core/live Node runner와 WebSocket acceptance gateway는 production Remote Brain과 함께 제거했다. 이 결과 파일을 현재 Embedded runtime의 신규 E2E 통과 증거로 재해석하지 않는다.

현재 deterministic 검증은:

```bash
./gradlew build
```

현재 live Jev/Luna smoke는:

```bash
OPENAI_API_KEY=... TYPESAFE_API_KEY=... \
  ./gradlew :minecraft:common:embeddedBrainLiveVerification
```

새 platform live acceptance harness가 필요하면 Embedded Brain을 직접 부팅하는 형태로 별도 추가해야 한다.
