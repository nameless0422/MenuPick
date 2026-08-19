import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from "axios";
import { apiErrorMessage, http, setAccessToken, setSessionExpiredHandler } from "./http";

// 실제 네트워크 대신 adapter를 갈아 끼운다. 여기서 확인하려는 건 서버의 동작이 아니라
// "인터셉터가 어떤 요청을 몇 번 보내는가"라서, 나간 요청 목록만 정확히 보이면 충분하다.
const calls: string[] = [];
const realAdapter = http.defaults.adapter;

type Handler = (config: InternalAxiosRequestConfig) => Promise<AxiosResponse>;

function installAdapter(handler: Handler) {
  http.defaults.adapter = (config) => {
    calls.push(`${(config.method ?? "get").toUpperCase()} ${config.url}`);
    return handler(config);
  };
}

function ok<T>(config: InternalAxiosRequestConfig, data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: "OK", headers: {}, config };
}

function fail(config: InternalAxiosRequestConfig, status: number, body?: unknown) {
  const response = { data: body, status, statusText: "", headers: {}, config } as AxiosResponse;
  return new AxiosError(`Request failed with status code ${status}`, String(status), config, {}, response);
}

/** 자격 증명 실패의 실제 응답 형태 — 백엔드 ErrorCode.INVALID_CREDENTIALS는 401이다. */
const INVALID_CREDENTIALS = {
  success: false,
  message: "이메일 또는 비밀번호가 올바르지 않습니다.",
  errorCode: "INVALID_CREDENTIALS",
};

beforeEach(() => {
  calls.length = 0;
  setAccessToken(null);
  // 등록해두면 세션 만료 통지가 다른 테스트로 새어 나간다.
  setSessionExpiredHandler(null);
});

afterEach(() => {
  http.defaults.adapter = realAdapter;
});

describe("401 재시도 가드", () => {
  // 이 경로들에서의 401은 "세션 만료"가 아니라 "자격 증명이 틀렸다"는 뜻이라
  // refresh로 고쳐지지 않는다. 그런데도 부르면 IP당 분당 10회짜리 인증 버킷을
  // 요청 1회당 2회씩 태워, 비밀번호를 몇 번 헷갈린 사용자가 429에 갇힌다.
  const preAuthPaths = [
    "/api/v1/auth/login",
    "/api/v1/auth/signup",
    "/api/v1/auth/kakao",
    "/api/v1/auth/google",
    "/api/v1/auth/verify-email",
    "/api/v1/auth/resend-verification",
    "/api/v1/auth/password-reset",
    "/api/v1/auth/password-reset/confirm",
    "/api/v1/auth/refresh",
  ];

  it.each(preAuthPaths)("POST %s의 401에는 refresh를 덧붙이지 않는다", async (url) => {
    installAdapter(async (config) => {
      throw fail(config, 401, INVALID_CREDENTIALS);
    });

    await expect(http.post(url)).rejects.toBeInstanceOf(AxiosError);

    expect(calls).toEqual([`POST ${url}`]);
  });

  it("쿼리스트링이 붙어도 인증 전 경로로 알아본다", async () => {
    installAdapter(async (config) => {
      throw fail(config, 401, INVALID_CREDENTIALS);
    });

    await expect(http.post("/api/v1/auth/login?next=%2Fpick")).rejects.toBeInstanceOf(AxiosError);

    expect(calls).toEqual(["POST /api/v1/auth/login?next=%2Fpick"]);
  });

  it("인증이 필요한 경로의 401은 refresh로 되살린다", async () => {
    // 이쪽까지 막아버리면 Access Token이 만료될 때마다 사용자가 로그인 화면으로 튕긴다.
    installAdapter(async (config) => {
      if (config.url === "/api/v1/auth/refresh") {
        return ok(config, { success: true, data: { accessToken: "새토큰" } });
      }
      if (calls.filter((call) => call.endsWith("/api/v1/menus")).length === 1) {
        throw fail(config, 401, { success: false, message: "인증이 필요합니다." });
      }
      return ok(config, { success: true, data: [] });
    });

    await http.get("/api/v1/menus");

    expect(calls).toEqual([
      "GET /api/v1/menus",
      "POST /api/v1/auth/refresh",
      "GET /api/v1/menus",
    ]);
  });

  it("비밀번호 변경(PATCH /auth/password)은 인증 경로라 refresh 대상이다", async () => {
    // 이름이 password-reset과 닮았을 뿐 로그인 상태에서만 부르는 경로다.
    installAdapter(async (config) => {
      if (config.url === "/api/v1/auth/refresh") {
        return ok(config, { success: true, data: { accessToken: "새토큰" } });
      }
      if (calls.filter((call) => call.endsWith("/api/v1/auth/password")).length === 1) {
        throw fail(config, 401, { success: false, message: "인증이 필요합니다." });
      }
      return ok(config, { success: true, data: null });
    });

    await http.patch("/api/v1/auth/password", {});

    expect(calls).toContain("POST /api/v1/auth/refresh");
  });
});

describe("요청 타임아웃", () => {
  it("무한 대기하지 않도록 timeout이 설정돼 있다", () => {
    // axios 기본값 0(무한)이면 응답을 주지 않는 서버에서 AuthProvider의 부팅 refresh가
    // 끝나지 않고, ProtectedRoute가 "로딩 중..."에 영구 고정된다.
    expect(http.defaults.timeout).toBeGreaterThan(0);
    expect(http.defaults.timeout).toBeLessThanOrEqual(30_000);
  });
});

describe("apiErrorMessage", () => {
  const config = { headers: {} } as InternalAxiosRequestConfig;

  it("백엔드가 준 message가 있으면 그대로 쓴다", () => {
    const error = fail(config, 400, { success: false, message: "메뉴 이름은 비울 수 없습니다." });

    expect(apiErrorMessage(error)).toBe("메뉴 이름은 비울 수 없습니다.");
  });

  it("서버에 닿지 못하면 axios의 영어 문구 대신 한국어로 안내한다", () => {
    const error = new AxiosError("Network Error", AxiosError.ERR_NETWORK, config, {});

    const message = apiErrorMessage(error);

    expect(message).not.toContain("Network Error");
    expect(message).toContain("연결");
  });

  it("타임아웃도 axios의 영어 문구를 그대로 노출하지 않는다", () => {
    const error = new AxiosError("timeout of 15000ms exceeded", AxiosError.ECONNABORTED, config, {});

    const message = apiErrorMessage(error);

    expect(message).not.toContain("timeout");
    expect(message).toContain("응답");
  });

  it("공통 응답 형식이 아닌 5xx(프록시 HTML 등)도 한국어로 바꾼다", () => {
    const error = fail(config, 502, "<html><body>502 Bad Gateway</body></html>");

    const message = apiErrorMessage(error);

    expect(message).not.toContain("502");
    expect(message).toContain("서버");
  });
});
