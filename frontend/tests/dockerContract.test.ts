import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const dockerfile = readFileSync(join(process.cwd(), "Dockerfile"), "utf8");

describe("프론트 운영 이미지 계약", () => {
  it("nginx 연결 상한을 기본값 1024보다 높인다", () => {
    expect(dockerfile).toContain("worker_connections  4096;");
    expect(dockerfile).toContain("grep -q 'worker_connections  4096;'");
  });

  it("jsdom 30이 지원하는 Node 22 버전으로 빌드한다", () => {
    expect(dockerfile).toContain(
      "FROM --platform=$BUILDPLATFORM node:22.22.2-alpine AS build",
    );
  });

  it("멀티아키텍처 빌드에서 정적 번들을 QEMU로 만들지 않는다", () => {
    expect(dockerfile).toContain("FROM --platform=$BUILDPLATFORM node:");
  });
});
