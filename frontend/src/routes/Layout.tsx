import { useEffect, useRef, useState } from "react";
import { NavLink, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import AppIcon from "../components/AppIcon";
import MobileSheet from "../components/MobileSheet";
import { useCompactLayout } from "../a11y/useCompactLayout";

/**
 * 라우트별 문서 제목. SPA는 페이지를 갈아 끼워도 브라우저가 <title>을 건드리지
 * 않으므로 직접 넣어야 한다. 스크린리더 사용자가 화면을 옮긴 뒤 가장 먼저 듣는
 * 것이 문서 제목이고, 탭을 오갈 때 자신이 어디 있는지 확인하는 유일한 단서다.
 */
const TITLES: Record<string, string> = {
  "/pick": "오늘 뭐 먹지",
  "/menus": "내 메뉴",
  "/restaurants": "저장한 식당",
  "/history": "픽 히스토리",
  "/settings": "설정",
};

export default function Layout() {
  const { logout } = useAuth();
  const { pathname } = useLocation();
  const compact = useCompactLayout();
  const mainRef = useRef<HTMLElement>(null);
  // 첫 렌더에서는 초점을 옮기지 않는다. 사용자가 주소창에서 바로 들어온 경우이거나
  // 새로고침이라, 아직 아무 데도 손대지 않은 초점을 빼앗을 이유가 없다.
  const previousPath = useRef(pathname);

  useEffect(() => {
    const name = TITLES[pathname];
    document.title = name ? `${name} · 메뉴픽` : "메뉴픽";

    // 라우트가 바뀌면 <main>은 통째로 갈리는데 초점은 방금 누른 nav 링크에 남는다.
    // 그대로 두면 다음 Tab이 새 페이지 본문이 아니라 그다음 nav 링크로 가버려,
    // 스크린리더 사용자는 화면이 바뀐 것도 모른 채 본문을 통째로 지나친다.
    if (previousPath.current !== pathname) {
      mainRef.current?.focus({ preventScroll: true });
      document.documentElement.scrollTop = 0;
      document.body.scrollTop = 0;
    }
    previousPath.current = pathname;
  }, [pathname]);

  return (
    <div className="app-shell">
      {/* nav의 링크 다섯 개와 로그아웃 버튼을 매 페이지마다 Tab으로 지나야 한다.
          보조기술 사용자는 <main> 랜드마크로 건너뛸 수 있지만, 보조기술 없이
          키보드만 쓰는 사람에게는 우회로가 없다. */}
      <a className="skip-link" href="#main">
        본문으로 건너뛰기
      </a>
      <header className="app-bar">
        <div className="app-bar-inner">
          {/* 서비스 이름. 링크로 만들지 않는다 — 목적지가 "오늘 뭐 먹지"와 같아
              nav에 이름만 다른 중복 링크가 하나 더 생긴다. */}
          <div className="brand-lockup">
            <span className="brand-symbol" aria-hidden="true"><AppIcon name="leaf" /></span>
            <span className="wordmark">메뉴픽</span>
            <span className="brand-caption">나의 작은 식탁</span>
          </div>
          <nav aria-label="주요 메뉴">
            {/* NavLink는 현재 경로일 때 aria-current="page"를 자동으로 붙인다.
                <Link>로는 지금 어느 화면인지 알 방법이 시각적으로도 없었다. */}
            <NavLink to="/pick"><AppIcon name="pick" /><span>{compact ? "오늘" : "오늘 뭐 먹지"}</span></NavLink>
            <NavLink to="/menus"><AppIcon name="menu" /><span>메뉴</span></NavLink>
            <NavLink to="/restaurants"><AppIcon name="pin" /><span>식당</span></NavLink>
            <NavLink to="/history"><AppIcon name="history" /><span>{compact ? "기록" : "히스토리"}</span></NavLink>
            {!compact && <NavLink to="/settings"><AppIcon name="settings" /><span>설정</span></NavLink>}
          </nav>
          {/* 로그아웃은 화면을 옮기는 동작이 아니라 세션을 끊는 동작이다 — nav 안에
              두면 랜드마크를 훑는 사용자에게 여섯 번째 목적지처럼 들린다. */}
          {compact ? <MobileAccountMenu /> : <div className="sidebar-bottom">
            <p>잘 고르고,<br />맛있게 먹고,<br />차곡차곡 기록해요.</p>
            <button className="nav-logout" onClick={() => logout()}>
              <AppIcon name="logout" />로그아웃
            </button>
          </div>}
        </div>
      </header>
      {/* tabIndex={-1}이 있어야 focus()가 먹는다. 초점 순서에는 들어가지 않는다. */}
      <main id="main" ref={mainRef} tabIndex={-1}>
        <Outlet />
      </main>
    </div>
  );
}

function MobileAccountMenu() {
  const { logout } = useAuth();
  const [open, setOpen] = useState(false);
  return <>
    <button type="button" className="account-trigger" aria-label="계정 메뉴"
      aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen(true)}>
      <AppIcon name="user" />
    </button>
    <MobileSheet open={open} title="내 계정" onDismiss={() => setOpen(false)}>
      <div className="account-actions">
        <NavLink to="/settings" onClick={() => setOpen(false)}>설정 열기</NavLink>
        <button type="button" onClick={() => { setOpen(false); logout(); }}>로그아웃</button>
      </div>
    </MobileSheet>
  </>;
}
