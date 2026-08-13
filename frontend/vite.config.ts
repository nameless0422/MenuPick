// vitest/config의 defineConfig를 쓴다 — vite 쪽 defineConfig에는 test 키 타입이 없어
// tsc -b가 이 설정을 통째로 거부한다.
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    // 컴포넌트 테스트라 DOM이 필요하다. 실제 브라우저(Playwright 등)를 쓰지 않는 이유는
    // 지금 확인하려는 것들이 브라우저 엔진 차이가 아니라 컴포넌트의 상태 전이라서다 —
    // jsdom이면 브라우저 바이너리 없이 CI에서 그대로 돌아간다.
    environment: 'jsdom',
    // globals를 켜지 않는다. describe/it/expect를 명시적으로 import하면 tsconfig의
    // types 목록을 건드릴 필요가 없고, 어디서 온 심볼인지도 파일만 보고 알 수 있다.
    globals: false,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
})
