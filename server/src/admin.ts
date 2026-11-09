import type { AppEnv } from './types';
import { HttpError, json, noContent, nowSeconds, readJson, requireAdmin } from './http';
import { cleanText, isValidId, parseLimit } from './validate';

const statuses = ['pending','approved','rejected','removed'];
const fields = 'c.id,c.creator_id,c.title,c.kind,c.duration_seconds,c.ai_generated,c.ai_tool,c.status,c.reject_reason,c.created_at,c.revision';
export async function adminContents(request: Request, env: AppEnv): Promise<Response> {
  await requireAdmin(request, env);
  const p = new URL(request.url).searchParams;
  const status = p.get('status') ?? 'pending', limit = parseLimit(p.get('limit'));
  const cursor = p.get('cursor');
  const after = cursor === null ? null : /^(\d{1,15})\.([A-Za-z0-9_-]{1,64})$/.exec(cursor);
  if (!statuses.includes(status) || limit === null || (cursor !== null && !after)) throw new HttpError(400,'invalid_request');
  const { results } = await env.DB.prepare(`SELECT ${fields},
    (SELECT COUNT(*) FROM reports r WHERE r.content_id = c.id) AS reports FROM contents c
    WHERE c.status = ? AND (? IS NULL OR c.created_at < ? OR (c.created_at = ? AND c.id < ?))
    ORDER BY c.created_at DESC,c.id DESC LIMIT ?`)
    .bind(status,after?.[1]??null,after?.[1]??null,after?.[1]??null,after?.[2]??null,limit+1).all<Record<string,unknown>>();
  const items = results.slice(0,limit), last = items.at(-1);
  return json(200,{items:items.map(r=>({...r,ai_generated:r.ai_generated===1})),
    next_cursor:results.length>limit&&last?`${last.created_at}.${last.id}`:null});
}
export async function adminDetail(id: string, request: Request, env: AppEnv): Promise<Response> {
  await requireAdmin(request, env);
  if (!isValidId(id)) throw new HttpError(400,'invalid_request');
  const row = await env.DB.prepare(`SELECT ${fields},c.submission_json,c.object_key,c.external_url FROM contents c WHERE c.id=?`).bind(id).first<Record<string,unknown>>();
  if (!row) throw new HttpError(404,'not_found');
  const [reports, history] = await Promise.all([
    env.DB.prepare('SELECT reason, COUNT(*) AS count FROM reports WHERE content_id=? GROUP BY reason').bind(id).all(),
    env.DB.prepare('SELECT revision,from_status,to_status,actor,reason,created_at FROM moderation_events WHERE content_id=? ORDER BY id DESC LIMIT 50').bind(id).all(),
  ]);
  const { object_key, external_url, ...content } = row;
  return json(200,{...content,ai_generated:row.ai_generated===1,media_url:object_key?`/v1/me/contents/${id}/media`:null,
    external_media:!!external_url,reports:reports.results,history:history.results});
}
export async function reviewContent(id: string, request: Request, env: AppEnv): Promise<Response> {
  const user = await requireAdmin(request, env);
  if (!isValidId(id)) throw new HttpError(400,'invalid_request');
  const body = await readJson(request);
  const action = body.action;
  if (!['approve','reject','remove'].includes(action as string) || !Number.isSafeInteger(body.revision) ||
      (body.revision as number) < 1) throw new HttpError(400,'invalid_request');
  const reason = action === 'approve' ? null : cleanText(body.reason,200);
  if (action !== 'approve' && !reason) throw new HttpError(400,'invalid_request');
  if (action === 'approve' && (body.file_reviewed !== true || body.rights_reviewed !== true || body.metadata_reviewed !== true))
    throw new HttpError(400,'review_required');
  const status = action === 'approve'?'approved':action==='reject'?'rejected':'removed';
  const allowed = action === 'remove' ? "('pending','approved')" : "('pending')";
  const result = await env.DB.prepare(`UPDATE contents SET status=?,reject_reason=?,review_actor=?,reviewed_at=?
    WHERE id=? AND revision=? AND status IN ${allowed}
    AND (? != 'approved' OR object_key IS NOT NULL OR external_url IS NOT NULL)
    AND EXISTS(SELECT 1 FROM users WHERE id=? AND role='admin') RETURNING id`)
    .bind(status,reason,user.id,nowSeconds(),id,body.revision as number,status,user.id).first<{id:string}>();
  if (!result) throw new HttpError(409,'revision_conflict');
  return noContent();
}
export async function adminUser(request: Request, env: AppEnv): Promise<Response> {
  await requireAdmin(request,env);
  const id = new URL(request.url).searchParams.get('id');
  if (!isValidId(id)) throw new HttpError(400,'invalid_request');
  const row = await env.DB.prepare('SELECT id,role,creator_id,created_at FROM users WHERE id=?').bind(id).first();
  if (!row) throw new HttpError(404,'not_found');
  return json(200,row);
}
export async function creatorAccess(id: string, request: Request, env: AppEnv): Promise<Response> {
  const user = await requireAdmin(request,env);
  if (!isValidId(id)) throw new HttpError(400,'invalid_request');
  const body = await readJson(request);
  if (!['approve','revoke'].includes(body.action as string) || (body.action==='approve'&&!isValidId(body.creator_id))) throw new HttpError(400,'invalid_request');
  let result;
  try {
    result = body.action==='approve'
      ? await env.DB.prepare(`UPDATE users SET role='creator',creator_id=? WHERE id=? AND role IN ('user','creator')
          AND (creator_id IS NULL OR creator_id=?) AND EXISTS(SELECT 1 FROM users admin WHERE admin.id=? AND admin.role='admin')`)
          .bind(body.creator_id as string,id,body.creator_id as string,user.id).run()
      : await env.DB.prepare(`UPDATE users SET role='user' WHERE id=? AND role='creator'
          AND EXISTS(SELECT 1 FROM users admin WHERE admin.id=? AND admin.role='admin')`).bind(id,user.id).run();
  } catch { throw new HttpError(409,'creator_conflict'); }
  if (result.meta.changes!==1) throw new HttpError(409,'creator_conflict');
  return noContent();
}
