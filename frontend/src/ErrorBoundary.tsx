import { Component, createRef, type ErrorInfo, type ReactNode } from "react";
import "./ErrorBoundary.css";

/**
 * 최상위 에러 바운더리.
 *
 * 없으면 렌더 중 예외 하나가 트리 전체의 언마운트로 이어진다 — 화면에는 아무것도 남지 않고
 * (완전한 백색 화면), 사용자는 새로고침 말고 할 수 있는 게 없으며, 무엇이 터졌는지 기록도
 * 남지 않는다.
 *
 * 가장 유력했던 트리거였던 api/*.ts의 `res.data.data!` 단언은 `unwrap`(api/http.ts)으로
 * 걷어냈다 — 이제 빈 응답은 API 경계에서 한국어 문구를 달고 끊긴다. 그래도 이 바운더리는
 * 남는다: 렌더 중 예외는 API만 만드는 것이 아니고, 마지막 그물이 없으면 남는 화면이
 * 백색 화면뿐이라는 사실은 그대로다.
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

  /**
   * 오류 화면의 <h1>. 예외가 나면 기존 트리가 통째로 언마운트되어 초점이 <body>로 떨어진다.
   *
   * <p>초점이 <body>에 있으면 스크린리더의 읽기 커서도 문서 맨 위로 돌아가고, 키보드
   * 사용자는 Tab을 눌러 지금 화면에 무엇이 남았는지 더듬어야 한다. 여기는 라우터 밖이라
   * <main> 랜드마크도 없어서 랜드마크 점프로 되돌아올 자리조차 없다. role="alert"가
   * 문구를 한 번 읽어 주기는 하지만 그건 알림일 뿐 — 알림이 끝나면 커서는 여전히 맨 위다.
   * 제목으로 초점을 옮기면 읽기 커서가 이 화면 안에 서고, 그 다음 Tab이 곧 두 버튼이다.
   */
  private heading = createRef<HTMLHeadingElement>();

  componentDidCatch(error: Error, info: ErrorInfo) {
    // 에러 리포팅 서비스가 없으니 최소한 콘솔에는 남긴다. 이게 없으면 "화면이 하얘요"라는
    // 제보를 받아도 어느 컴포넌트에서 시작됐는지 되짚을 단서가 하나도 없다.
    console.error("[ErrorBoundary] 렌더 중 처리되지 않은 예외", error, info.componentStack);
    // 이 훅은 오류 화면이 이미 커밋된 뒤에 돈다 — ref가 채워져 있다.
    this.heading.current?.focus();
  }

  render() {
    if (!this.state.hasError) return this.props.children;

    return (
      // role="alert"로 스크린리더에도 알린다 — 시각적으로는 화면이 통째로 바뀌지만
      // 보조기술 사용자에게는 아무 일도 일어나지 않은 것처럼 조용히 지나간다.
      <div className="error-boundary" role="alert">
        {/* tabIndex=-1이 없으면 focus()가 조용히 무시된다 — 제목은 원래 초점을 받지 않는다.
            -1이라 Tab 순회에는 끼어들지 않고, 프로그램으로 옮길 때만 초점을 받는다. */}
        <h1 ref={this.heading} tabIndex={-1}>화면을 표시하지 못했습니다</h1>
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
