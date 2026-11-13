import type { AppEnv } from './types';
import { HttpError, json, nowSeconds } from './http';
import { MAX_OPEN_UPLOADS, UPLOAD_TTL_SECONDS } from './upload-validation';

export const QUOTA_WINDOW_SECONDS = 24 * 60 * 60;
export function uploadLimits(env: AppEnv) {
  const positive = (value: string | undefined, fallback: number, max: number) => {
    if (value === undefined) return fallback;
    if (!/^[1-9][0-9]{0,12}$/.test(value) || !Number.isSafeInteger(Number(value)) || Number(value) > max)
      throw new HttpError(503, 'upload_limits_unavailable');
    return Number(value);
  };
  return {
    daily_count: positive(env.UPLOAD_DAILY_COUNT, 50, 10000),
    daily_bytes: positive(env.UPLOAD_DAILY_BYTES, 512 * 1024 * 1024, 1024 ** 4),
    storage_bytes: positive(env.UPLOAD_STORAGE_BYTES, 2 * 1024 ** 3, 1024 ** 4),
  };
}

export async function uploadUsage(userId: string, env: AppEnv, now = nowSeconds()) {
  const row = await env.DB.prepare(`SELECT
    COALESCE(SUM(CASE WHEN created_at>? THEN 1 ELSE 0 END),0) daily_count,
    COALESCE(SUM(CASE WHEN created_at>? THEN bytes ELSE 0 END),0) daily_bytes,
    COALESCE(SUM(CASE WHEN released_at IS NULL THEN bytes ELSE 0 END),0) storage_bytes
    FROM upload_quota WHERE user_id=?`)
    .bind(now-QUOTA_WINDOW_SECONDS,now-QUOTA_WINDOW_SECONDS,userId)
    .first<{daily_count:number;daily_bytes:number;storage_bytes:number}>();
  return row!;
}

/** Called only after the atomic INSERT was refused. Never return internal keys or raw input. */
export async function uploadLimitResponse(userId: string, bytes: number, env: AppEnv): Promise<Response> {
  const limits=uploadLimits(env), usage=await uploadUsage(userId,env);
  const open=await env.DB.prepare("SELECT COUNT(*) n FROM uploads WHERE user_id=? AND state IN ('created','uploading','uploaded') AND expires_at>?")
    .bind(userId,nowSeconds()).first<{n:number}>();
  const code = (open?.n ?? 0)>=MAX_OPEN_UPLOADS ? 'limit_reached'
    : usage.storage_bytes+bytes>limits.storage_bytes ? 'storage_quota_exceeded'
    : usage.daily_count>=limits.daily_count ? 'upload_daily_count_exceeded'
    : usage.daily_bytes+bytes>limits.daily_bytes ? 'upload_daily_bytes_exceeded' : 'limit_reached';
  const response=json(429,{error:code});
  // Persistent storage exhaustion cannot be fixed by an automatic timed retry.
  if (code==='limit_reached') response.headers.set('Retry-After',String(UPLOAD_TTL_SECONDS));
  if (code.startsWith('upload_daily_')) response.headers.set('Retry-After',String(QUOTA_WINDOW_SECONDS));
  return response;
}
