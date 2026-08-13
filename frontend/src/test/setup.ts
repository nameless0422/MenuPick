import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// 테스트마다 DOM을 비운다. 남겨두면 다음 테스트의 쿼리가 이전 렌더의 노드를 집어
// 통과해야 할 테스트가 통과하고 실패해야 할 테스트도 통과한다.
afterEach(() => {
  cleanup();
});
