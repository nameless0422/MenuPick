import { Component, type ErrorInfo, type ReactNode } from "react";
import "./ErrorBoundary.css";

/**
 * 최상위 에러 바운더리.
 *
 * 없으면 렌더 중 예외 하나가 트리 전체의 언마운트로 이어진다 — 화면에는 아무것도 남지 않고
 * (완전한 백색 화면), 사용자는 새로고침 말고 할 수 있는 게 없으며, 무엇이 터졌는지 기록도
 * 남지 않는다. 실제 후보 트리거는 api/*.ts 전반의 `res.data.data!`다: 서버가 data 없이
 * 200을 주면 단언이 undefined를 통과시켜 화면 렌더 중에 터진다.
 *
 * 훅으로는 만들 수 없다 — getDerivedStateFromError/componentDidCatch에 대응하는 훅이 없어서
 * React 19에서도 에러 바운더리는 클래스 컴포넌트여야 한다.
 */
interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
}

export default class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false };

  static getDerivedStateFromError(): State {
    return { hasError: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // 에러 리포팅 서비스가 없으니 최소한 콘솔에는 남긴다. 이게 없으면 "화면이 하얘요"라는
    // 제보를 받아도 어느 컴포넌트에서 시작됐는지 되짚을 단서가 하나도 없다.
    console.error("[ErrorBoundary] 렌더 중 처리되지 않은 예외", error, info.componentStack);
  }

  render() {
    if (!this.state.hasError) return this.props.children;

    return (
      // role="alert"로 스크린리더에도 알린다 — 시각적으로는 화면이 통째로 바뀌지만
      // 보조기술 사용자에게는 아무 일도 일어나지 않은 것처럼 조용히 지나간다.
      <div className="error-boundary" role="alert">
        <h1>화면을 표시하지 못했습니다</h1>
        <p>
          일시적인 오류일 수 있습니다. 새로고침해도 같은 화면이 계속 나오면 잠시 후 다시 시도해
          주세요.
        </p>
        <div className="error-boundary-actions">
          <button type="button" onClick={() => window.location.reload()}>
            새로고침
          </button>
          {/* 같은 화면에서 결정적으로 터지는 예외라면 새로고침은 같은 결과를 반복한다.
              앱을 아예 못 쓰게 되는 걸 막으려면 다른 화면으로 나갈 길이 하나 더 필요하다.
              (라우터 밖이라 navigate를 쓸 수 없어 전체 이동으로 처리한다 — 어차피 상태를
              버리고 다시 시작하는 게 목적이다.) */}
          <button type="button" onClick={() => window.location.assign("/")}>
            처음 화면으로
          </button>
        </div>
      </div>
    );
  }
}
