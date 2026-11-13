import { verifyGoogleIdToken } from './auth';
import { HttpError, json, nowSeconds, readJson, requireUser, requireWebOrigin } from './http';
import type { AppEnv, UserRow } from './types';

/** Google reauthentication proves the account; bearer/cookie is additionally required by the in-app path. */
export async function requestAccountDeletion(request: Request, env: AppEnv, web = false): Promise<Response> {
  if (web) requireWebOrigin(request);
  const body = await readJson(request);
  if (body.confirm !== 'DELETE' || typeof body.id_token !== 'string' || body.id_token.length > 4096)
    throw new HttpError(400, 'invalid_request');
  const local = ['localhost','127.0.0.1','[::1]'].includes(new URL(request.url).hostname);
  const verificationEnv = web && !local ? { ...env, DEV_MODE: undefined } : env;
  let sub: string | null;
  try { sub = await verifyGoogleIdToken(verificationEnv, body.id_token, nowSeconds(), 300); }
  catch { throw new HttpError(503, 'unavailable'); }
  if (!sub) throw new HttpError(401, 'unauthorized');
  const user = await env.DB.prepare('SELECT * FROM users WHERE google_sub=?').bind(sub).first<UserRow>();
  if (!user) {
    // A different, unregistered Google account must not appear to delete the signed-in account.
    if (!web) { await requireUser(request, env); throw new HttpError(403, 'account_mismatch'); }
    return json(200, { status: 'deleted' });
  }
  if (!web) {
    // For an already accepted deletion, a fresh matching Google proof is enough to retry.
    if (user.deletion_requested_at == null) {
      const session = await requireUser(request, env);
      if (session.id !== user.id) throw new HttpError(403, 'account_mismatch');
    }
  }
  if (user.deletion_requested_at == null) {
    const now = nowSeconds();
    await env.DB.batch([
      env.DB.prepare('UPDATE users SET deletion_requested_at=? WHERE id=? AND deletion_requested_at IS NULL').bind(now,user.id),
      // Allow already-started R2 PUTs to settle before removing the durable object list.
      env.DB.prepare(`INSERT OR IGNORE INTO account_deletions(user_id,purge_after)
        SELECT id,MAX(?,COALESCE((SELECT MAX(expires_at) FROM uploads WHERE user_id=users.id),?))+3600
        FROM users WHERE id=?`).bind(now,now,user.id),
      env.DB.prepare(`INSERT OR IGNORE INTO account_deletion_objects(user_id,object_key)
        SELECT ?,object_key FROM uploads WHERE user_id=?
        UNION SELECT ?,object_key FROM contents WHERE creator_id=? AND object_key IS NOT NULL`)
        .bind(user.id,user.id,user.id,user.creator_id),
      env.DB.prepare("UPDATE contents SET status='removed',reject_reason=NULL,review_actor='account-deletion',reviewed_at=? WHERE creator_id=?")
        .bind(now,user.creator_id),
    ]);
  }
  return json(202, { status: 'pending' });
}

/** Bounded, retryable cron. R2 errors retain keys and user tombstones; no false 'deleted' response. */
export async function processAccountDeletions(env: AppEnv, now = nowSeconds()): Promise<void> {
  const { results: jobs } = await env.DB.prepare('SELECT user_id FROM account_deletions WHERE purge_after<=? ORDER BY purge_after LIMIT 10')
    .bind(now).all<{user_id:string}>();
  for (const job of jobs) {
    try {
      const user = await env.DB.prepare('SELECT * FROM users WHERE id=? AND deletion_requested_at IS NOT NULL').bind(job.user_id).first<UserRow>();
      if (!user) continue;
      const { results: objects } = await env.DB.prepare('SELECT object_key FROM account_deletion_objects WHERE user_id=? LIMIT 100')
        .bind(user.id).all<{object_key:string}>();
      // An object is only removed from the durable list after R2 confirms the delete.
      for (const object of objects) {
        await env.MEDIA.delete(object.object_key);
        await env.DB.prepare('DELETE FROM account_deletion_objects WHERE user_id=? AND object_key=?').bind(user.id,object.object_key).run();
      }
      if (await env.DB.prepare('SELECT 1 FROM account_deletion_objects WHERE user_id=? LIMIT 1').bind(user.id).first()) continue;
      await env.DB.batch([
        env.DB.prepare('DELETE FROM reports WHERE reporter_user_id=? OR creator_id=? OR content_id IN (SELECT id FROM contents WHERE creator_id=?)').bind(user.id,user.creator_id,user.creator_id),
        env.DB.prepare("UPDATE report_events SET actor='deleted-account' WHERE actor=?").bind(user.id),
        env.DB.prepare("UPDATE reports SET reviewed_by='deleted-account' WHERE reviewed_by=?").bind(user.id),
        env.DB.prepare('DELETE FROM moderation_events WHERE content_id IN (SELECT id FROM contents WHERE creator_id=?)').bind(user.creator_id),
        env.DB.prepare("UPDATE moderation_events SET actor='deleted-account' WHERE actor=?").bind(user.id),
        env.DB.prepare("UPDATE contents SET review_actor='deleted-account' WHERE review_actor=?").bind(user.id),
        env.DB.prepare('DELETE FROM contents WHERE creator_id=?').bind(user.creator_id),
        env.DB.prepare('DELETE FROM uploads WHERE user_id=?').bind(user.id),
        env.DB.prepare('DELETE FROM subscriptions WHERE user_id=? OR creator_id=?').bind(user.id,user.creator_id),
        env.DB.prepare('DELETE FROM blocks WHERE user_id=? OR creator_id=?').bind(user.id,user.creator_id),
        env.DB.prepare('DELETE FROM users WHERE id=? AND deletion_requested_at IS NOT NULL').bind(user.id),
      ]);
    } catch {
      // No raw identities, object keys or provider error bodies in logs.
      console.error('account deletion retry required');
    }
  }
}
