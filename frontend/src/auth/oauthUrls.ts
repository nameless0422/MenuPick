// 인가 코드를 받아오는 첫 단계 — Kakao/Google의 authorize 엔드포인트로 리다이렉트한다.
// 여기서 쓰는 redirect_uri는 반드시 백엔드 OAUTH_*_REDIRECT_URI(Planning.md 6.2)와 동일해야
// 토큰 교환이 성공한다 (KakaoOAuthProvider.getUserProfile 참고).

export function kakaoAuthorizeUrl(): string {
  const params = new URLSearchParams({
    client_id: import.meta.env.VITE_KAKAO_CLIENT_ID,
    redirect_uri: import.meta.env.VITE_KAKAO_REDIRECT_URI,
    response_type: "code",
  });
  return `https://kauth.kakao.com/oauth/authorize?${params.toString()}`;
}

export function googleAuthorizeUrl(): string {
  const params = new URLSearchParams({
    client_id: import.meta.env.VITE_GOOGLE_CLIENT_ID,
    redirect_uri: import.meta.env.VITE_GOOGLE_REDIRECT_URI,
    response_type: "code",
    scope: "openid email profile",
  });
  return `https://accounts.google.com/o/oauth2/v2/auth?${params.toString()}`;
}
