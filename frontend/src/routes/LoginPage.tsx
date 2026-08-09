import { kakaoAuthorizeUrl, googleAuthorizeUrl } from "../auth/oauthUrls";

export default function LoginPage() {
  return (
    <div>
      <h1>메뉴픽</h1>
      <p>오늘 뭐 먹지 고민을 대신 해드립니다.</p>
      <button onClick={() => (window.location.href = kakaoAuthorizeUrl())}>
        카카오로 로그인
      </button>
      <button onClick={() => (window.location.href = googleAuthorizeUrl())}>
        구글로 로그인
      </button>
    </div>
  );
}
