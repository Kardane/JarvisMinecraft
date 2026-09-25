declare module "node:crypto" {
  export function randomUUID(): string;
  export function timingSafeEqual(left: Uint8Array, right: Uint8Array): boolean;
}

declare const process: {
  readonly env: Readonly<Record<string, string | undefined>>;
  exitCode: number | undefined;
  on(event: "SIGINT" | "SIGTERM", listener: () => void): void;
};
