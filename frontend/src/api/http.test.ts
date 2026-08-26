import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from "axios";
import {
  apiErrorMessage,
  http,
  setAccessToken,
  setSessionExpiredHandler,
  unwrap,
  type ApiResponse,
} from "./http";

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

describe("401 재시도 — 토큰이 이미 갈린 경우", () => {
  /**
   * 화면 하나가 병렬로 부르는 쿼리 서너 개가 동시에 만료를 만나는 것은 흔한 일이다.
   * 그 401들은 몇십~몇백 ms씩 어긋나 도착하는데, refreshPromise는 .finally에서 즉시
   * 비워지므로 "완전히 겹친" 것만 하나로 합쳐진다. 나머지는 각자 refresh를 부르고,
   * /auth/refresh는 인증 버킷(IP당 분당 10회)에 있어 공유 NAT에서는 화면 몇 번 여는
   * 것만으로 429에 닿는다 — 그때부터 로그인·가입·비밀번호 재설정까지 함께 막힌다.
   */
  it("401이 시차를 두고 도착해도 refresh는 한 번만 나간다", async () => {
    let releaseSecond: () => void = () => {};
    const secondBlocked = new Promise<void>((resolve) => {
      releaseSecond = resolve;
    });

    setAccessToken("옛토큰");
    installAdapter(async (config) => {
      if (config.url === "/api/v1/auth/refresh") {
        return ok(config, { success: true, data: { accessToken: "새토큰" } });
      }
      if (config.headers.Authorization === "Bearer 옛토큰") {
        // 두 번째 요청의 401만 늦게 도착시킨다 — 첫 번째의 재발급이 이미 끝난 뒤다.
        if (config.url === "/api/v1/menus") await secondBlocked;
        throw fail(config, 401, { success: false, message: "인증이 필요합니다." });
      }
      return ok(config, { success: true, data: [] });
    });

    // 둘 다 옛 토큰을 달고 나간다(어댑터가 같은 틱에 호출된다).
    const first = http.get("/api/v1/tags");
    const second = http.get("/api/v1/menus");

    await first;
    releaseSecond();
    await second;

    expect(calls.filter((call) => call.includes("/auth/refresh"))).toHaveLength(1);
    // 두 번째 요청은 버려지지 않고 새 토큰으로 다시 나가야 한다.
    expect(calls.filter((call) => call === "GET /api/v1/menus")).toHaveLength(2);
  });

  it("토큰이 그대로면(진짜 만료) refresh를 부른다", async () => {
    // 위 최적화가 "토큰이 다르면 재발급을 건너뛴다"로 새어 나가면 안 된다.
    setAccessToken("옛토큰");
    installAdapter(async (config) => {
      if (config.url === "/api/v1/auth/refresh") {
        return ok(config, { success: true, data: { accessToken: "새토큰" } });
      }
      if (config.headers.Authorization === "Bearer 옛토큰") {
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
});

describe("unwrap", () => {
  const config = { headers: {} } as InternalAxiosRequestConfig;

  function response<T>(body: unknown): AxiosResponse<ApiResponse<T>> {
    return { data: body, status: 200, statusText: "OK", headers: {}, config } as AxiosResponse<
      ApiResponse<T>
    >;
  }

  it("data가 있으면 그대로 꺼낸다", () => {
    expect(unwrap(response<{ id: number }>({ success: true, data: { id: 7 } }))).toEqual({ id: 7 });
  });

  it("data 없이 온 200은 한국어 문구를 달고 API 경계에서 끊긴다", () => {
    // 예전 `res.data.data!`는 undefined를 그대로 통과시켜, 실패가 화면 렌더 중에
    // "Cannot read properties of undefined"로 드러났다 — 사용자가 보는 건 전면 오류 화면이다.
    expect(() => unwrap(response({ success: true }))).toThrowError(/서버 응답이 비어 있습니다/);
  });

  it("data: null도 같은 실패로 본다", () => {
    expect(() => unwrap(response({ success: true, data: null }))).toThrowError(
      /서버 응답이 비어 있습니다/,
    );
  });

  it("unwrap이 던진 문구는 apiErrorMessage가 그대로 화면에 올린다", () => {
    // 화면들은 catch에서 apiErrorMessage로 문구를 만든다. 여기서 영어가 새면
    // 한국어 화면 한복판에 axios 문구가 뜨는 원래 문제로 되돌아간다.
    let thrown: unknown;
    try {
      unwrap(response({ success: true }));
    } catch (e) {
      thrown = e;
    }

    expect(apiErrorMessage(thrown)).toMatch(/서버 응답이 비어 있습니다/);
  });
});
