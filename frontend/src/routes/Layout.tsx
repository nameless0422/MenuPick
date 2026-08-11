import { Link, Outlet } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

export default function Layout() {
  const { logout } = useAuth();

  return (
    <div>
      <nav>
        <Link to="/pick">오늘 뭐 먹지</Link>
        <Link to="/menus">메뉴</Link>
        <Link to="/restaurants">식당</Link>
        <Link to="/history">히스토리</Link>
        <Link to="/settings">설정</Link>
        <button onClick={() => logout()}>로그아웃</button>
      </nav>
      <main>
        <Outlet />
      </main>
    </div>
  );
}
