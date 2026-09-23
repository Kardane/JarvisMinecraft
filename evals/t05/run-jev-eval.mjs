import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

import {
  JEV_MODEL,
  TypeSafeJevClassifier,
} from "../../brain/dist/ai/index.js";

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

const args = parseArgs(process.argv.slice(2));
const apiKey = process.env.TYPESAFE_API_KEY;
if (!apiKey) {
  console.error("TYPESAFE_API_KEY is required for the live Jev evaluation.");
  process.exit(1);
}

const sourcePath = resolve(
  args.input ?? new URL("./jev-korean-cases.jsonl", import.meta.url).pathname,
);
const cases = (await readFile(sourcePath, "utf8"))
  .trim()
  .split("\n")
  .filter(Boolean)
  .map((line) => JSON.parse(line));

const selected =
  args.limit === undefined ? cases : cases.slice(0, args.limit);
const classifier = new TypeSafeJevClassifier(apiKey);

const results = [];
for (const item of selected) {
  const classification = await classifier.classify({
    latestMessage: item.text,
    shortTopic: item.text,
    capabilities: [
      "server.status",
      "player.list",
      "player.lookup",
      "player.location",
      "player.nearby",
      "world.info",
      "staff.self_teleport",
      "history.lookup",
      "region.lookup",
      "region.protection",
    ],
  });
  results.push({
    id: item.id,
    split: item.split,
    expected: item.label,
    predicted: classification.category,
    confidence: classification.confidence,
    model: classification.model,
    requestId: classification.requestId,
    tags: item.tags,
  });
  console.error(
    item.id +
      " expected=" +
      item.label +
      " predicted=" +
      classification.category +
      " confidence=" +
      classification.confidence.toFixed(4),
  );
}

if (results.some((item) => item.model !== JEV_MODEL)) {
  throw new Error("Evaluation received a model other than " + JEV_MODEL);
}

const dev = results.filter((item) => item.split === "dev");
const holdout = results.filter((item) => item.split === "holdout");
const threshold =
  args.threshold ?? chooseDevThreshold(dev);

const report = {
  generatedAt: new Date().toISOString(),
  model: JEV_MODEL,
  datasetCases: selected.length,
  thresholdSource:
    args.threshold === undefined ? "dev-calibrated" : "cli-override",
  confidenceThreshold: threshold,
  dev: summarize(dev, threshold),
  holdout: summarize(holdout, threshold),
  holdoutTarget: {
    routeAccuracy: 0.95,
    passed:
      holdout.length > 0 &&
      summarize(holdout, threshold).routeAccuracy >= 0.95,
  },
  evidence: results,
};

const json = JSON.stringify(report, null, 2);
console.log(json);
if (args.output) {
  await writeFile(resolve(args.output), json + "\n", "utf8");
}

if (
  selected.length === 200 &&
  report.holdout.routeAccuracy < report.holdoutTarget.routeAccuracy
) {
  process.exitCode = 2;
}

function chooseDevThreshold(rows) {
  if (rows.length === 0) {
    return 0;
  }

  let best = {
    threshold: 0,
    selectiveAccuracy: -1,
    coverage: -1,
  };
  for (let step = 0; step <= 19; step += 1) {
    const threshold = step * 0.05;
    const summary = summarize(rows, threshold);
    if (summary.coverage < 0.5) {
      continue;
    }
    if (
      summary.selectiveAccuracy > best.selectiveAccuracy ||
      (summary.selectiveAccuracy === best.selectiveAccuracy &&
        summary.coverage > best.coverage)
    ) {
      best = {
        threshold,
        selectiveAccuracy: summary.selectiveAccuracy,
        coverage: summary.coverage,
      };
    }
  }
  return Number(best.threshold.toFixed(2));
}

function summarize(rows, threshold) {
  const confusion = Object.fromEntries(
    LABELS.map((expected) => [
      expected,
      Object.fromEntries(LABELS.map((predicted) => [predicted, 0])),
    ]),
  );
  const perClass = Object.fromEntries(
    LABELS.map((label) => [
      label,
      { total: 0, correct: 0, accuracy: 0 },
    ]),
  );

  let correct = 0;
  let abstained = 0;
  let covered = 0;
  let selectiveCorrect = 0;

  for (const row of rows) {
    confusion[row.expected][row.predicted] += 1;
    perClass[row.expected].total += 1;
    if (row.expected === row.predicted) {
      correct += 1;
      perClass[row.expected].correct += 1;
    }

    const isAbstained =
      row.predicted === "UNCERTAIN" || row.confidence < threshold;
    if (isAbstained) {
      abstained += 1;
    } else {
      covered += 1;
      if (row.expected === row.predicted) {
        selectiveCorrect += 1;
      }
    }
  }

  for (const label of LABELS) {
    const item = perClass[label];
    item.accuracy = item.total === 0 ? 0 : item.correct / item.total;
  }

  return {
    total: rows.length,
    correct,
    routeAccuracy: rows.length === 0 ? 0 : correct / rows.length,
    confidenceThreshold: threshold,
    abstained,
    abstentionRate: rows.length === 0 ? 0 : abstained / rows.length,
    coverage: rows.length === 0 ? 0 : covered / rows.length,
    selectiveAccuracy:
      covered === 0 ? 0 : selectiveCorrect / covered,
    perClass,
    confusionMatrix: confusion,
  };
}

function parseArgs(values) {
  const output = {};
  for (let i = 0; i < values.length; i += 1) {
    const value = values[i];
    if (value === "--input") {
      output.input = values[++i];
    } else if (value === "--output") {
      output.output = values[++i];
    } else if (value === "--limit") {
      output.limit = Number(values[++i]);
    } else if (value === "--threshold") {
      output.threshold = Number(values[++i]);
    } else {
      throw new Error("Unknown argument: " + value);
    }
  }
  if (
    output.threshold !== undefined &&
    (!Number.isFinite(output.threshold) ||
      output.threshold < 0 ||
      output.threshold > 1)
  ) {
    throw new Error("--threshold must be between 0 and 1.");
  }
  if (
    output.limit !== undefined &&
    (!Number.isInteger(output.limit) || output.limit < 1)
  ) {
    throw new Error("--limit must be a positive integer.");
  }
  return output;
}
