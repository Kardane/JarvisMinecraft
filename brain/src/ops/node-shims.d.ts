declare module "node:fs/promises" {
  export interface Dirent {
    readonly name: string;
    isFile(): boolean;
  }

  export interface Stats {
    readonly size: number;
    readonly mtimeMs: number;
    isFile(): boolean;
  }

  export function mkdir(
    path: string,
    options?: { readonly recursive?: boolean },
  ): Promise<string | undefined>;

  export function readdir(
    path: string,
    options: { readonly withFileTypes: true },
  ): Promise<Dirent[]>;

  export function stat(path: string): Promise<Stats>;
  export function appendFile(path: string, data: string, encoding: "utf8"): Promise<void>;
  export function unlink(path: string): Promise<void>;
}

declare module "node:path" {
  export function join(...parts: string[]): string;
}
