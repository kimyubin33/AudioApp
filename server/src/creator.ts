import type { AppEnv } from './types';
import { requireFormat } from './features';
import { HttpError, json, noContent, readJson, requireCreator, requireUser } from './http';
import { isValidId } from './validate';
import { uploadMetadata } from './upload-validation';
import { validateSubmission } from './submission';
import { checkAttachedAudio } from './posts';

export async function creatorProfile(request: Request, env: AppEnv): Promise<Response> {
  const user = await requireUser(request, env);
  return json(200, { user_id: user.id, creator_id: user.creator_id,
    can_upload: !!user.creator_id && ['creator', 'admin'].includes(user.role) });
}

export async function uploadStatus(id: string, request: Request, env: AppEnv): Promise<Response> {
  const user = await requireUser(request, env);
  if (!isValidId(id)) throw new HttpError(400, 'invalid_request');
  const row = await env.DB.prepare('SELECT state, expires_at, content_id FROM uploads WHERE id = ? AND user_id = ?')
    .bind(id, user.id).first<{ state: string; expires_at: number; content_id: string }>();
  if (!row) throw new HttpError(404, 'not_found');
  return json(200, { ...row, state: row.state !== 'completed' && row.expires_at <= Math.floor(Date.now() / 1000) ? 'expired' : row.state });
}

/** 진행 중 PUT을 취소할 때 먼저 상태를 폐기한다. 늦게 끝난 PUT도 complete할 수 없게 한다. */
export async function cancelUpload(id: string, request: Request, env: AppEnv): Promise<Response> {
  const user = await requireUser(request, env);
  if (!isValidId(id)) throw new HttpError(400, 'invalid_request');
  const row = await env.DB.prepare('SELECT state, object_key FROM uploads WHERE id = ? AND user_id = ?')
    .bind(id, user.id).first<{ state: string; object_key: string }>();
  if (!row) throw new HttpError(404, 'not_found');
  const result = await env.DB.prepare("UPDATE uploads SET state = 'failed', token_hash = '' WHERE id = ? AND state != 'completed'")
    .bind(id).run();
  if (result.meta.changes !== 1) throw new HttpError(409, 'upload_completed');
  await env.MEDIA.delete(row.object_key);
  return noContent();
}

/** pending/rejected 정보만 수정한다. 검수 중 수정이나 반려 후 재제출은 revision으로 충돌을 감지한다. */
export async function editCreatorContent(id: string, request: Request, env: AppEnv): Promise<Response> {
  const user = await requireCreator(request, env);
  if (!isValidId(id)) throw new HttpError(400, 'invalid_request');
  const body = await readJson(request);
  if (typeof body.kind === 'string') requireFormat(body.kind,env);
  const meta = uploadMetadata(body), submission = validateSubmission(body.submission,body.kind==='post');
  if (!meta || !submission || !Number.isSafeInteger(body.revision) || (meta.kind==='post'&&!submission.description) ||
      (meta.kind!=='post'&&submission.audio_content_id)) throw new HttpError(400, 'invalid_request');
  await checkAttachedAudio(submission.audio_content_id,user.id,env);
  const row = await env.DB.prepare('SELECT kind, duration_seconds, submission_json FROM contents WHERE id = ? AND creator_id = ?')
    .bind(id, user.creator_id).first<{ kind: string; duration_seconds: number; submission_json: string | null }>();
  if (!row) throw new HttpError(404, 'not_found');
  const original = row.submission_json ? validateSubmission(JSON.parse(row.submission_json),row.kind==='post') : null;
  if (!original || JSON.stringify(original.file) !== JSON.stringify(submission.file) ||
      meta.kind !== row.kind || meta.duration_seconds !== row.duration_seconds) throw new HttpError(400, 'file_change_requires_upload');
  const result = await env.DB.prepare(`UPDATE contents SET title = ?, ai_generated = ?, ai_tool = ?, submission_json = ?, short_style = ?,
      status = 'pending', reject_reason = NULL, revision = revision + 1, review_actor = NULL, reviewed_at = NULL
    WHERE id = ? AND creator_id = ? AND revision = ? AND status IN ('pending', 'rejected')
      AND EXISTS (SELECT 1 FROM users WHERE id = ? AND role IN ('creator', 'admin'))`)
    .bind(meta.title, meta.ai_generated ? 1 : 0, meta.ai_tool, JSON.stringify(submission), meta.short_style, id, user.creator_id, body.revision as number, user.id).run();
  if (result.meta.changes !== 1) throw new HttpError(409, 'revision_conflict');
  return noContent();
}
