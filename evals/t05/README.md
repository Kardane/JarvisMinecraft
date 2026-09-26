# T05 model evaluation assets

E14에서 TypeScript/Node production Brain이 제거되면서 Node 기반 T05 runner도 제거했다. 평가 데이터와 기존 evidence는 보존한다.

## Jev Korean routing set

`jev-korean-cases.jsonl`에는 기존 200-case Korean Jev routing dataset이 남아 있다. 다음을 포함한다.

- 8 routing label
- development / holdout split
- 존댓말/반말
- typo
- negation
- pronoun
- compound request
- general conversation
- hostile/prompt-injection strings

이 데이터는 향후 Java Jev evaluation runner를 추가할 때 그대로 재사용한다.

## 현재 live model 검증

Embedded Java 경로의 live provider smoke는 다음 Gradle task가 담당한다.

```bash
OPENAI_API_KEY=... TYPESAFE_API_KEY=... \
  ./gradlew :minecraft:common:embeddedBrainLiveVerification
```

E11 verification은 실제 Jev와 Luna provider 호출을 검증한다. Minecraft platform E2E와 model live smoke는 서로 다른 evidence 층위다.

## E12 parity

`evals/embedded-policy-parity.json`은 E12에서 확정한 shared policy fixture다. Node reference 삭제 후에도 Java parity regression input으로 계속 유지한다.
