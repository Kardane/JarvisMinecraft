import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const DATASET = new URL(
  "../../../evals/t05/jev-korean-cases.jsonl",
  import.meta.url,
);

const LABELS = [
  "SERVER_QUERY",
  "PLAYER_QUERY",
  "WORLD_QUERY",
  "HISTORY_QUERY",
  "REGION_QUERY",
  "ACTION_REQUEST",
  "GENERAL",
  "UNCERTAIN",
];

test("A09 Korean dataset has 200 balanced cases with frozen holdout", async () => {
  const cases = (await readFile(DATASET, "utf8"))
    .trim()
    .split("\n")
    .map((line) => JSON.parse(line));

  assert.equal(cases.length, 200);
  assert.equal(cases.filter((item) => item.split === "dev").length, 144);
  assert.equal(cases.filter((item) => item.split === "holdout").length, 56);

  for (const label of LABELS) {
    assert.equal(
      cases.filter((item) => item.label === label).length,
      25,
      label + " must contain 25 cases",
    );
  }

  const tags = new Set(cases.flatMap((item) => item.tags));
  for (const tag of [
    "존댓말",
    "반말",
    "오타",
    "부정문",
    "지시대명사",
    "복합요청",
    "공격문자열",
  ]) {
    assert.ok(tags.has(tag), "missing required A09 tag: " + tag);
  }

  const ids = new Set(cases.map((item) => item.id));
  assert.equal(ids.size, 200);
});
