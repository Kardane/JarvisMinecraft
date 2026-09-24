import test from "node:test";
import assert from "node:assert/strict";

import {
  OpenAiLunaPort,
} from "../../dist/ai/index.js";

const REQUEST = "00000000-0000-4000-8000-000000000501";
const SESSION = "00000000-0000-4000-8000-000000000502";
const ADMIN = "00000000-0000-4000-8000-000000000503";

test("Luna wrapper preserves function call state and returns function output by call_id", async () => {
  const requests = [];
  const fakeClient = {
    responses: {
      async create(params, options) {
        requests.push({ params, options });
        if (requests.length === 1) {
          return {
            id: "resp_1",
            model: "gpt-6-luna",
            output_text: "",
            output: [
              {
                id: "fc_1",
                type: "function_call",
                call_id: "call_1",
                name: "get_server_status",
                arguments: "{}",
                status: "completed",
              },
            ],
          };
        }
        return {
          id: "resp_2",
          model: "gpt-6-luna",
          output_text: "현재 TPS는 19.95입니다.",
          output: [],
        };
      },
    },
  };

  const luna = new OpenAiLunaPort("test-key-not-a-real-secret", fakeClient);
  const routing = {
    category: "SERVER_QUERY",
    fallbackReason: null,
    originalAvailableTools: [serverTool()],
  };

  const first = await luna.next(turn([
    {
      role: "user",
      text: "자비스 서버 TPS 알려줘",
      requestId: REQUEST,
      at: new Date().toISOString(),
    },
  ]), routing);

  assert.equal(first.kind, "tools");
  assert.deepEqual(first.calls, [
    { tool: "get_server_status", arguments: {} },
  ]);
  assert.equal(requests[0].params.model, "gpt-6-luna");
  assert.equal(requests[0].params.reasoning.effort, "medium");
  assert.equal(requests[0].params.store, false);
  assert.equal(requests[0].params.tools[0].strict, true);
  assert.equal(requests[0].options.maxRetries, 0);

  const second = await luna.next(turn([
    {
      role: "user",
      text: "자비스 서버 TPS 알려줘",
      requestId: REQUEST,
      at: new Date().toISOString(),
    },
    {
      role: "tool",
      tool: "get_server_status",
      toolCallId: "brain-tool-call-1",
      result: {
        status: "OK",
        data: {
          tps: {
            value: 19.95,
            unit: "tps",
            windowMs: 60_000,
            observedAt: new Date().toISOString(),
            source: "Paper",
          },
        },
        error: null,
        observedAt: new Date().toISOString(),
        source: "Paper",
        truncated: false,
      },
      requestId: REQUEST,
      at: new Date().toISOString(),
    },
  ]), routing);

  assert.equal(second.kind, "final");
  assert.equal(second.text, "현재 TPS는 19.95입니다.");

  const continuationInput = requests[1].params.input;
  assert.ok(
    continuationInput.some(
      (item) =>
        item.type === "function_call_output" &&
        item.call_id === "call_1" &&
        item.output.includes("19.95"),
    ),
  );

  assert.deepEqual(luna.lastMetadata(REQUEST), {
    responseId: "resp_2",
    model: "gpt-6-luna",
    toolCalls: 0,
  });
});

test("Luna wrapper rejects a mismatched Tool result sequence before a second API call", async () => {
  let calls = 0;
  const fakeClient = {
    responses: {
      async create() {
        calls += 1;
        return {
          id: "resp_a",
          model: "gpt-6-luna",
          output_text: "",
          output: [
            {
              id: "fc_a",
              type: "function_call",
              call_id: "call_a",
              name: "get_server_status",
              arguments: "{}",
              status: "completed",
            },
          ],
        };
      },
    },
  };

  const luna = new OpenAiLunaPort("test-key-not-a-real-secret", fakeClient);
  const routing = {
    category: "SERVER_QUERY",
    fallbackReason: null,
    originalAvailableTools: [serverTool()],
  };
  const first = await luna.next(turn([
    {
      role: "user",
      text: "자비스 서버 상태",
      requestId: REQUEST,
      at: new Date().toISOString(),
    },
  ]), routing);
  assert.equal(first.kind, "tools");

  await assert.rejects(
    luna.next(turn([
      {
        role: "user",
        text: "자비스 서버 상태",
        requestId: REQUEST,
        at: new Date().toISOString(),
      },
      {
        role: "tool",
        tool: "get_player_location",
        toolCallId: "wrong",
        result: {
          status: "ERROR",
          data: null,
          error: {
            code: "NOT_FOUND",
            message: "wrong",
            retryable: false,
            details: {},
          },
          observedAt: new Date().toISOString(),
          source: "Fake",
          truncated: false,
        },
        requestId: REQUEST,
        at: new Date().toISOString(),
      },
    ]), routing),
    /sequence is inconsistent/,
  );
  assert.equal(calls, 1);
});

function turn(history) {
  return {
    binding: {
      serverId: "main",
      requesterUuid: ADMIN,
      sessionId: SESSION,
      requestId: REQUEST,
    },
    requesterName: "Admin",
    history,
    capabilities: [
      { name: "server.status", source: "Fake", version: "1.0" },
    ],
    availableTools: [serverTool()],
    remainingToolCalls: 8,
    remainingModelRounds: 4,
    deadlineAt: new Date(Date.now() + 30_000).toISOString(),
  };
}

function serverTool() {
  return {
    name: "get_server_status",
    capability: "server.status",
    stateChanging: false,
    risk: "READ_ONLY",
  };
}
