import {
  appendFile,
  mkdir,
  readdir,
  stat,
  unlink,
} from "node:fs/promises";
import { join } from "node:path";

import type { AuditFileInfo, AuditFileSystem } from "./types.js";

export const nodeAuditFileSystem: AuditFileSystem = {
  async ensureDirectory(path: string): Promise<void> {
    await mkdir(path, { recursive: true });
  },

  async list(path: string): Promise<readonly AuditFileInfo[]> {
    const entries = await readdir(path, { withFileTypes: true });
    const files: AuditFileInfo[] = [];
    for (const entry of entries) {
      if (!entry.isFile()) {
        continue;
      }
      const metadata = await stat(join(path, entry.name));
      if (!metadata.isFile()) {
        continue;
      }
      files.push({
        name: entry.name,
        size: metadata.size,
        mtimeMs: metadata.mtimeMs,
      });
    }
    return files;
  },

  async append(path: string, data: string): Promise<void> {
    await appendFile(path, data, "utf8");
  },

  async remove(path: string): Promise<void> {
    await unlink(path);
  },
};
