// MenuPick 부하 테스트. 설계 근거는 docs/LoadTestPlan.md 에 있다 — 이 파일은 그 구현이다.
//
// 실행:
//   SCENARIO=smoke  BASE_URL=... JWT_SECRET=... USER_IDS=101-150 k6 run scripts/k6/load-test.js
//   SCENARIO=load   ... RATE=100 k6 run scripts/k6/load-test.js
//   SCENARIO=stress ... k6 run scripts/k6/load-test.js
//   SCENARIO=soak   ... RATE=60 k6 run scripts/k6/load-test.js
//   SCENARIO=proxy  ... PROXY_MODE=upstream k6 run scripts/k6/load-test.js
//
// 사전 조건:
//   1. scripts/k6/seed.sql 을 대상 DB에 적용했을 것. 안 하면 픽이 전부 404로 떨어져
//      "빠르게 404를 내는 속도"를 재게 된다.
//   2. USER_IDS 에 시드된 사용자 id 범위를 줄 것 (seed.sql 이 마지막에 출력한다).
//   3. k6 를 대상 VM이 아닌 별도 머신에서 돌릴 것 — 같은 상자에서 돌리면 측정하려던
//      대상을 측정 도구가 압박한다 (LoadTestPlan 7절).
//
// 한 번에 한 시나리오만 돌린다. 섞으면 어느 쪽이 무릎을 만들었는지 알 수 없다.

import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';
import { authParams } from './jwt.js';

// ---- 환경 ----

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET;
const SCENARIO = __ENV.SCENARIO || 'smoke';
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || '5m';

if (!JWT_SECRET) {
  throw new Error('JWT_SECRET 환경변수가 필요하다. 사용법은 파일 상단 주석 참고.');
}

/**
 * 시드된 사용자 id 목록. "101-150" 범위형과 "101,102,103" 나열형을 모두 받는다.
 *
 * 사용자를 여럿 쓰는 이유: 한 계정만 두들기면 그 사용자의 행이 MySQL 버퍼풀과
 * 하이버네이트 배치 캐시에 완전히 얹혀, 실제보다 좋은 숫자가 나온다.
 */
const USER_IDS = parseUserIds(__ENV.USER_IDS);

function parseUserIds(raw) {
  if (!raw) {
    throw new Error('USER_IDS 환경변수가 필요하다. seed.sql 출력의 id 범위를 그대로 넣는다 (예: 101-150).');
  }
  if (raw.includes('-')) {
    const [from, to] = raw.split('-').map(Number);
    const ids = [];
    for (let id = from; id <= to; id++) ids.push(id);
    return ids;
  }
  return raw.split(',').map(Number);
}

function someUser() {
  return USER_IDS[Math.floor(Math.random() * USER_IDS.length)];
}

// ---- 커스텀 지표 ----

/**
 * 픽이 후보 없음(404)으로 끝난 횟수.
 *
 * 시드가 제대로 안 들어갔거나 필터가 너무 좁으면 여기가 오른다. 그 상태의 응답 시간은
 * "DB를 한 번 훑고 즉시 404"라 실제 픽보다 훨씬 빠르다 — 지표만 보면 성능이 좋아 보인다.
 * 그래서 실패로 세고 별도 카운터로도 드러낸다.
 */
const noCandidates = new Counter('pick_no_candidates');

// ---- 시나리오 정의 ----

const SCENARIOS = {
  // 배선 확인. 성능이 아니라 "시드가 들어갔고 토큰이 먹히는가"를 본다.
  smoke: {
    executor: 'shared-iterations',
    vus: 1,
    iterations: 1,
    exec: 'smoke',
    maxDuration: '1m',
  },

  // 목표 부하(동시 100명)에서의 상태.
  //
  // constant-vus가 아니라 도착률 기준인 이유: VU 고정은 응답이 느려지면 요청도 같이
  // 느려져(closed-loop) 부하가 저절로 줄어든다. 그러면 포화가 숨는다.
  load: {
    executor: 'constant-arrival-rate',
    rate: RATE,
    timeUnit: '1s',
    duration: DURATION,
    exec: 'browse',
    preAllocatedVUs: Math.max(50, RATE),
    maxVUs: RATE * 4,
  },

  // 무릎 찾기 — 이 설계의 핵심 시나리오. 임계값으로 실패시키지 않는다(목적이 깨뜨리는 것).
  stress: {
    executor: 'ramping-arrival-rate',
    startRate: 20,
    timeUnit: '1s',
    exec: 'browse',
    preAllocatedVUs: 100,
    maxVUs: 800,
    stages: [
      { target: 20, duration: '2m' },
      { target: 40, duration: '2m' },
      { target: 60, duration: '2m' },
      { target: 80, duration: '2m' },
      { target: 120, duration: '2m' },
      { target: 160, duration: '2m' },
    ],
  },

  // 시간이 지나야 드러나는 것들(힙 누수, 커넥션 대기 적체, 히스토리 증가에 따른 둔화).
  // RATE는 stress에서 찾은 무릎의 60% 수준으로 운영자가 직접 준다.
  soak: {
    executor: 'constant-arrival-rate',
    rate: RATE,
    timeUnit: '1s',
    duration: __ENV.DURATION || '1h',
    exec: 'browse',
    preAllocatedVUs: Math.max(50, RATE),
    maxVUs: RATE * 4,
  },

  // 외부 프록시. 기본 믹스에서 빼 둔 이유는 레이트 리밋(30/분)과 캐시 때문이다.
  // 무엇을 재는지 먼저 정하고 따로 돌린다 (LoadTestPlan 8절).
  proxy: {
    executor: 'constant-arrival-rate',
    rate: Number(__ENV.RATE || 5),
    timeUnit: '1s',
    duration: DURATION,
    exec: 'proxy',
    preAllocatedVUs: 20,
    maxVUs: 100,
  },
};

if (!SCENARIOS[SCENARIO]) {
  throw new Error(`알 수 없는 SCENARIO=${SCENARIO}. smoke|load|stress|soak|proxy 중 하나.`);
}

/**
 * 임계값.
 *
 * Planning.md 7.1 목표를 그대로 옮겼다. 단 stress에는 걸지 않는다 — 목적이 한계를
 * 넘기는 것이라, 임계값을 걸면 "실패"가 결과가 아니라 잡음이 된다.
 */
const THRESHOLDS = {
  smoke: {
    // 배선 확인이므로 단 한 건도 실패하면 안 된다. 여기서 404가 나면 그 뒤 회차는 전부 무의미하다.
    http_req_failed: ['rate==0'],
    checks: ['rate==1.0'],
    pick_no_candidates: ['count==0'],
  },
  load: {
    'http_req_duration{name:menuList}': ['p(95)<300'],
    'http_req_duration{name:history}': ['p(95)<300'],
    'http_req_duration{name:pick}': ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
    pick_no_candidates: ['count==0'],
  },
  stress: {},
  soak: {
    http_req_failed: ['rate<0.01'],
    pick_no_candidates: ['count==0'],
  },
  proxy: {
    'http_req_duration{name:proxy}': ['p(95)<1000'],
    http_req_failed: ['rate<0.01'],
  },
};

export const options = {
  scenarios: { [SCENARIO]: SCENARIOS[SCENARIO] },
  thresholds: THRESHOLDS[SCENARIO],
  // 시나리오 이름을 태그로 남겨 여러 회차 결과를 한 곳에 모아도 구분된다.
  tags: { scenario: SCENARIO },
};

// ---- 요청 ----

function menuList(userId) {
  const res = http.get(
    `${BASE_URL}/api/v1/menus?size=20`,
    authParams(userId, JWT_SECRET, { name: 'menuList' }),
  );
  check(res, { 'menuList 200': (r) => r.status === 200 });
  return res;
}

function history(userId) {
  const res = http.get(
    `${BASE_URL}/api/v1/history?size=20`,
    authParams(userId, JWT_SECRET, { name: 'history' }),
  );
  check(res, { 'history 200': (r) => r.status === 200 });
  return res;
}

/**
 * 픽. 이건 조회가 아니라 쓰기다 — 성공할 때마다 histories 행이 남는다(PickService.java:41).
 * P95 500ms 목표에 INSERT가 포함돼 있고, soak 중에는 DB가 단조 증가한다.
 *
 * @param variant 'plain' | 'category' | 'distance' — 필터가 인메모리 필터링 비용을 얼마나
 *                끌어올리는지 보려고 나눈다 (PickService는 SQL로 거르지 않는다).
 */
function pick(userId, variant) {
  const body = {};
  if (variant === 'category') {
    body.categories = ['한식'];
  } else if (variant === 'distance') {
    // 시드된 식당이 서울시청 반경 2km에 흩어져 있으므로 1km면 일부만 걸린다.
    body.latitude = 37.5665;
    body.longitude = 126.978;
    body.maxDistance = 1000;
  }

  const res = http.post(
    `${BASE_URL}/api/v1/pick`,
    JSON.stringify(body),
    authParams(userId, JWT_SECRET, { name: 'pick', variant: variant }),
  );

  if (res.status === 404) {
    // 예전 스크립트는 404를 정상으로 넘겼지만, 시드가 있는 지금은 나오면 안 되는 응답이다.
    noCandidates.add(1);
  }
  check(res, { 'pick 200': (r) => r.status === 200 });
  return res;
}

// ---- exec 함수 ----

/**
 * 목표 부하의 요청 믹스.
 *
 * 한 요청 = 한 반복이다. sleep을 넣지 않는 이유는 도착률 executor가 이미 속도를
 * 통제하기 때문이다 — 여기서 또 재우면 실제 도착률이 설정값보다 낮아진다.
 */
export function browse() {
  const userId = someUser();
  const roll = Math.random();

  if (roll < 0.4) {
    menuList(userId);
  } else if (roll < 0.6) {
    history(userId);
  } else if (roll < 0.8) {
    pick(userId, 'plain');
  } else if (roll < 0.9) {
    pick(userId, 'category');
  } else {
    pick(userId, 'distance');
  }
}

/** 모든 대상 경로를 한 번씩. 하나라도 어긋나면 즉시 실패시킨다. */
export function smoke() {
  const userId = USER_IDS[0];

  const menus = menuList(userId);
  if (menus.status !== 200) {
    fail(`menuList가 ${menus.status}. 토큰(JWT_SECRET)이나 BASE_URL을 확인할 것.`);
  }

  const menuCount = (menus.json('data.menus') || []).length;
  if (menuCount === 0) {
    fail('시드된 메뉴가 없다. seed.sql을 적용했는지, USER_IDS가 맞는지 확인할 것.');
  }

  history(userId);

  for (const variant of ['plain', 'category', 'distance']) {
    const res = pick(userId, variant);
    if (res.status !== 200) {
      fail(`pick(${variant})이 ${res.status}. 404면 시드 데이터가 필터 조건을 만족하지 못한다는 뜻이다.`);
    }
  }
}

/**
 * 외부 API 프록시.
 *
 * PROXY_MODE로 무엇을 잴지 명시적으로 고른다 — 캐시 TTL이 1시간(카카오)이고 좌표는
 * 소수점 3자리로 반올림되므로, 고정 질의로 쏘면 두 번째 요청부터 Redis만 재게 된다.
 * 어느 쪽으로 쟀는지 반드시 결과에 적을 것 (docs/LoadTestResults.md).
 *
 * 주의: 이 경로는 IP 기준 30/분으로 막힌다. 그대로 돌리면 대부분 429가 된다 —
 * 테스트 환경에서 RATE_LIMIT_PROXY_PER_MINUTE를 올리거나 X-Forwarded-For를 나눌 것.
 */
export function proxy() {
  const userId = someUser();
  const mode = __ENV.PROXY_MODE || 'cached';

  // upstream: 매번 다른 질의 → 캐시 미스 → 실제 업스트림 왕복을 잰다.
  // cached:   고정 질의 → 첫 요청 이후 Redis 히트 경로를 잰다.
  const query = mode === 'upstream' ? `강남 맛집 ${Math.random().toString(36).slice(2, 8)}` : '진주회관';

  const res = http.get(
    `${BASE_URL}/api/v1/kakao/search/keyword?query=${encodeURIComponent(query)}`,
    authParams(userId, JWT_SECRET, { name: 'proxy', mode: mode }),
  );
  check(res, {
    'proxy 200': (r) => r.status === 200,
    'proxy 429 아님(레이트 리밋에 막히면 측정이 아니다)': (r) => r.status !== 429,
  });
}
