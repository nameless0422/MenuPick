import type { ReactNode } from "react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";

/**
 * 화면 컴포넌트를 앱과 같은 컨텍스트 안에서 렌더한다.
 *
 * <p>재시도는 꺼 둔다. main.tsx의 기본값(최대 3회)을 그대로 쓰면 실패를 검증하는 테스트가
 * 재시도를 기다리느라 느려지고, 타이머를 조작하는 테스트에서는 아예 멈춘 것처럼 보인다.
 */
export function renderWithProviders(ui: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
}
