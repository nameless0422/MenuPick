import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const dockerfile = readFileSync(join(process.cwd(), "Dockerfile"), "utf8");
const workflow = readFileSync(join(process.cwd(), "..", ".github", "workflows", "ci.yml"), "utf8");
const nginxConf = readFileSync(join(process.cwd(), "nginx.conf"), "utf8");

describe("프론트 운영 이미지 계약", () => {
  it("nginx 런타임을 패치 버전까지 고정한다", () => {
    expect(dockerfile).toContain("FROM nginx:1.30.6-alpine");
  });

  it("접근 로그에 인증 링크의 쿼리와 Referer를 남기지 않는다", () => {
    expect(nginxConf).toContain("map $request_uri $menupick_request_path");
    expect(nginxConf).toContain("access_log /var/log/nginx/access.log menupick_safe;");

    const format = nginxConf.slice(
      nginxConf.indexOf("log_format menupick_safe"),
      nginxConf.indexOf("server {"),
    );
    expect(format).toContain("$menupick_request_path");
    expect(format).not.toContain("$request_uri");
    expect(format).not.toContain("$args");
    expect(format).not.toMatch(/\$request\b/);
    expect(format).not.toContain("$http_referer");
  });

  it("같은 오리진 호출에도 토큰이 든 전체 URL을 Referer로 보내지 않는다", () => {
    // add_header는 상속이 아니라 덮어쓰기라 자기 헤더가 있는 location에도 있어야 한다.
    expect(nginxConf.match(/add_header Referrer-Policy "strict-origin" always;/g)).toHaveLength(3);
  });

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

  it("조건 조정 UI 플래그를 기본 off로 번들에 기록한다", () => {
    expect(dockerfile).toContain('ARG VITE_PICK_ALTERNATIVES_ENABLED="false"');
    expect(dockerfile).toContain(
      'echo "VITE_PICK_ALTERNATIVES_ENABLED=${VITE_PICK_ALTERNATIVES_ENABLED}" >> .env.production',
    );
  });

  it("CI 변수가 없을 때 조건 조정 UI를 켜지 않는다", () => {
    expect(workflow).toContain(
      "VITE_PICK_ALTERNATIVES_ENABLED=${{ vars.VITE_PICK_ALTERNATIVES_ENABLED || 'false' }}",
    );
  });
});
