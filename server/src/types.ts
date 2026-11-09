/** 서버가 쓰는 설정과 바인딩. 값은 wrangler.toml / .dev.vars / wrangler secret 으로 들어온다. */
export interface AppEnv {
  DB: D1Database;
  /** 같은 원점의 크리에이터/검수 웹 정적 자산. */
  ASSETS?: Fetcher;
  /** 접근 토큰 서명용 비밀값 (32자 이상). 배포: wrangler secret put TOKEN_SECRET */
  TOKEN_SECRET: string;
  /** Google 웹 클라이언트 ID. ID 토큰의 "받는 사람"이어야 한다 */
  GOOGLE_CLIENT_ID: string;
  /** 비공개 R2. 파일은 승인 상태를 확인하는 Worker를 통해서만 제공한다. */
  MEDIA: R2Bucket;
  /** API의 공개 HTTPS 원점. TLS 프록시를 쓰는 로컬 개발에서도 지정할 수 있다. */
  API_BASE_URL?: string;
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
