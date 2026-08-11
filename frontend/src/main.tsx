import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import axios from "axios";
import "./index.css";
import App from "./App.tsx";
import { AuthProvider } from "./auth/AuthContext";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 인증·레이트리밋 실패는 재시도해봐야 결과가 같다. 기본값(3회)을 그대로 두면
      // 만료된 세션에서 쿼리 하나가 401 재시도를 반복하며 그때마다 /auth/refresh를
      // 새로 호출한다(재시도마다 새 요청이라 인터셉터의 1회 가드가 걸리지 않는다).
      // 백엔드 인증 경로 제한이 IP당 분당 10회라, 화면 하나 여는 것만으로 429에 닿아
      // 이후 정상 로그인까지 막힌다.
      retry: (failureCount, error) => {
        const status = axios.isAxiosError(error) ? error.response?.status : undefined;
        if (status === 401 || status === 403 || status === 429) return false;
        return failureCount < 3;
      },
    },
  },
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>,
);
