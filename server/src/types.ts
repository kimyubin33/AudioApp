/** 서버가 쓰는 설정과 바인딩. 값은 wrangler.toml / .dev.vars / wrangler secret 으로 들어온다. */
export interface AppEnv {
  DB: D1Database;
  /** 접근 토큰 서명용 비밀값 (32자 이상). 배포: wrangler secret put TOKEN_SECRET */
  TOKEN_SECRET: string;
  /** Google 웹 클라이언트 ID. ID 토큰의 "받는 사람"이어야 한다 */
  GOOGLE_CLIENT_ID: string;
  /** R2 공개 주소. 업로드된 파일의 재생 주소를 만들 때 쓴다 */
  MEDIA_BASE_URL?: string;
  /** '1'이면 로컬 개발용 가짜 로그인 허용. 배포 환경에서는 설정하지 않는다 */
  DEV_MODE?: string;
}

export interface UserRow {
  id: string;
  google_sub: string;
  role: 'user' | 'creator' | 'admin';
  creator_id: string | null;
  created_at: number;
}
