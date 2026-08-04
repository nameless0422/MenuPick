// MenuPick 핵심 API 부하 테스트 (docs/ImprovementBacklog.md 12번)
//
// 실행:
//   BASE_URL=http://localhost:8080 ACCESS_TOKEN=<유효한 JWT> k6 run scripts/k6/load-test.js
//
// ACCESS_TOKEN 발급 방법: 카카오/구글 OAuth는 스크립트로 자동화할 수 없으므로,
// 로컬에서 실제 소셜 로그인으로 한 번 로그인한 뒤 응답 바디의 accessToken을 복사해 사용한다.
// (부하 테스트 전용 시드 계정/토큰 발급 API는 별도 과제 — 이번 범위에서는 제외)
//
// Planning.md 7.1 성능 목표를 threshold로 그대로 반영했다:
//   조회 API P95 < 300ms, 랜덤 픽 P95 < 500ms

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ACCESS_TOKEN = __ENV.ACCESS_TOKEN;

if (!ACCESS_TOKEN) {
  throw new Error('ACCESS_TOKEN 환경변수가 필요합니다. 사용법은 파일 상단 주석 참고');
}

const authHeaders = {
  headers: {
    Authorization: `Bearer ${ACCESS_TOKEN}`,
    'Content-Type': 'application/json',
  },
};

export const options = {
  scenarios: {
    menu_list: {
      executor: 'constant-vus',
      exec: 'menuList',
      vus: 20,
      duration: '1m',
    },
    pick: {
      executor: 'constant-vus',
      exec: 'pick',
      vus: 20,
      duration: '1m',
    },
  },
  thresholds: {
    'http_req_duration{name:menuList}': ['p(95)<300'],
    'http_req_duration{name:pick}': ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

export function menuList() {
  const res = http.get(`${BASE_URL}/api/v1/menus?size=20`, {
    ...authHeaders,
    tags: { name: 'menuList' },
  });
  check(res, { 'menu list 200': (r) => r.status === 200 });
  sleep(1);
}

export function pick() {
  const payload = JSON.stringify({
    latitude: 37.5665,
    longitude: 126.978,
    categories: [],
    tagIds: [],
    excludeTagIds: [],
  });
  const res = http.post(`${BASE_URL}/api/v1/menus/pick`, payload, {
    ...authHeaders,
    tags: { name: 'pick' },
  });
  check(res, { 'pick 200 or 404(후보없음)': (r) => r.status === 200 || r.status === 404 });
  sleep(1);
}
