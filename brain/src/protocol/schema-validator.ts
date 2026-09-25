import { Ajv2020 } from "ajv/dist/2020.js";
import * as addFormatsModule from "ajv-formats";

import type { JsonObject } from "../core/types.js";
import { PROTOCOL_SCHEMA } from "../generated/protocol-schema.js";

const ajv = new Ajv2020({
  allErrors: true,
  strict: true,
  allowUnionTypes: true,
  strictTypes: false,
});
const addFormats = resolveAddFormats(addFormatsModule);
addFormats(ajv);

const validateProtocol = ajv.compile(PROTOCOL_SCHEMA);

export function assertProtocolMessage(
  value: unknown,
): asserts value is JsonObject {
  if (validateProtocol(value)) {
    return;
  }
  throw new Error(
    "Protocol schema validation failed: " +
      ajv.errorsText(validateProtocol.errors, { separator: "; " }),
  );
}


function resolveAddFormats(
  moduleValue: unknown,
): (ajv: InstanceType<typeof Ajv2020>) => unknown {
  let candidate: unknown = moduleValue;
  for (let depth = 0; depth < 2; depth += 1) {
    if (
      candidate !== null &&
      typeof candidate === "object" &&
      "default" in candidate
    ) {
      candidate = (candidate as { readonly default: unknown }).default;
      continue;
    }
    break;
  }
  if (typeof candidate !== "function") {
    throw new Error("ajv-formats did not expose a callable plugin.");
  }
  return candidate as (ajv: InstanceType<typeof Ajv2020>) => unknown;
}
