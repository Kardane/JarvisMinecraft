import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, "..", "..");
const schemaPath = resolve(repoRoot, "protocol", "schema", "protocol.schema.json");
const fixtureRoot = resolve(repoRoot, "protocol", "fixtures");
const manifestPath = resolve(fixtureRoot, "manifest.json");

const readJson = async (path) => JSON.parse(await readFile(path, "utf8"));

const schema = await readJson(schemaPath);
const manifest = await readJson(manifestPath);

const ajv = new Ajv2020({
  allErrors: true,
  strict: true,
  allowUnionTypes: true,
});
addFormats(ajv);

const validate = ajv.compile(schema);
let failures = 0;

for (const file of manifest.valid) {
  const value = await readJson(resolve(fixtureRoot, "valid", file));
  if (!validate(value)) {
    failures += 1;
    console.error(`Expected valid fixture to pass: ${file}`);
    console.error(ajv.errorsText(validate.errors, { separator: "\n" }));
  }
}

for (const entry of manifest.invalid) {
  const value = await readJson(resolve(fixtureRoot, "invalid", entry.file));
  if (validate(value)) {
    failures += 1;
    console.error(`Expected invalid fixture to fail: ${entry.file}`);
  }
}

if (failures > 0) {
  process.exitCode = 1;
} else {
  console.log(
    `Protocol fixtures OK: ${manifest.valid.length} valid, ${manifest.invalid.length} invalid.`,
  );
}
