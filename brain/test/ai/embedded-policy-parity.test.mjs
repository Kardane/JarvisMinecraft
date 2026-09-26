import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

import { getToolDescriptor } from "../../dist/core/catalog.js";
import {
  isLowConfidence,
  readOnlyFallbackTools,
  toolsForHealthyRoute,
} from "../../dist/ai/routing.js";
import { translateFunctionCall } from "../../dist/ai/index.js";

const fixture = JSON.parse(
  await readFile(
    new URL("../../../evals/embedded-policy-parity.json", import.meta.url),
    "utf8",
  ),
);

test("E12 shared route fixtures match the Remote Brain policy", () => {
  for (const item of fixture.routeCases) {
    const active = item.activeTools.map((name) => getToolDescriptor(name));
    let tools;
    let fallback = null;

    if (item.mode === "ERROR") {
      tools = readOnlyFallbackTools(active);
      fallback = "JEV_ERROR";
    } else {
      const classification = {
        category: item.category,
        confidence: item.confidence,
        probabilities: {},
        model: "jev-1.13.0",
        requestId: "e12-fixture",
      };

      if (item.category === "UNCERTAIN") {
        tools = readOnlyFallbackTools(active);
        fallback = "JEV_UNCERTAIN";
      } else if (
        item.abstainBelow !== null
          && isLowConfidence(classification, {
            abstainBelow: item.abstainBelow,
          })
      ) {
        tools = readOnlyFallbackTools(active);
        fallback = "JEV_LOW_CONFIDENCE";
      } else {
        tools = toolsForHealthyRoute(item.category, active);
      }
    }

    assert.deepEqual(
      tools.map((tool) => tool.name).sort(),
      [...item.expectedTools].sort(),
      item.name,
    );
    assert.equal(fallback, item.expectedFallback, item.name);
  }
});

test("E12 shared argument fixtures match the Remote Brain validation policy", () => {
  for (const item of fixture.argumentCases) {
    let accepted = true;
    try {
      translateFunctionCall(
        item.tool,
        JSON.stringify(item.arguments),
        new Set([item.tool]),
      );
    } catch {
      accepted = false;
    }
    assert.equal(accepted, item.valid, item.name);
  }
});
