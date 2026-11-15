import type { AppEnv } from './types';
import { requireFormat } from './features';
import { HttpError, json, nowSeconds, readJson, requireCreator } from './http';
import { uploadMetadata } from './upload-validation';
import { validateSubmission } from './submission';

/** 오디오 연결은 승인된 공개 오디오에 한정한다. URL·파일 경로를 제출받지 않는다. */
export async function checkAttachedAudio(id: string | undefined, userId: string, env: AppEnv) {
  if (!id) return;
  const row = await env.DB.prepare(`SELECT id FROM contents c WHERE id=? AND kind='audio' AND status='approved' AND validation_status='passed'
    AND NOT EXISTS(SELECT 1 FROM blocks b WHERE b.user_id=? AND b.creator_id=c.creator_id)`).bind(id,userId).first();
  if (!row) throw new HttpError(400,'invalid_audio');
}

/** 텍스트 전용 게시글. 사진 게시글은 기존 R2 업로드 검증 흐름을 사용한다. */
export async function createPost(request: Request, env: AppEnv): Promise<Response> {
  const user = await requireCreator(request,env);
  requireFormat('post',env);
  const body = await readJson(request), meta = uploadMetadata(body), submission = validateSubmission(body.submission,true);
  if (!meta || meta.kind!=='post' || meta.content_type!==null || !submission || submission.file!==null || !submission.description ||
      typeof body.client_id!=='string' || !/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(body.client_id)) throw new HttpError(400,'invalid_request');
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(user.id+':'+body.client_id)));
  const id = 'c_post_'+Array.from(digest.slice(0,20),b=>b.toString(16).padStart(2,'0')).join('');
  const raw = JSON.stringify(submission);
  const previous = await env.DB.prepare('SELECT title,ai_generated,ai_tool,submission_json FROM contents WHERE id=? AND creator_id=?')
    .bind(id,user.creator_id).first<{title:string;ai_generated:number;ai_tool:string|null;submission_json:string}>();
  if (previous) {
    if (previous.title!==meta.title || previous.ai_generated!==(meta.ai_generated?1:0) || previous.ai_tool!==meta.ai_tool || previous.submission_json!==raw)
      throw new HttpError(409,'revision_conflict');
    return json(201,{content_id:id});
  }
  await checkAttachedAudio(submission.audio_content_id,user.id,env);
  const result = await env.DB.prepare(`INSERT OR IGNORE INTO contents
    (id,creator_id,title,kind,duration_seconds,ai_generated,ai_tool,status,created_at,submission_json)
    SELECT ?,?,?,'post',0,?,?,'pending',?,?
    WHERE (SELECT COUNT(*) FROM contents WHERE creator_id=? AND status='pending')<100
      AND EXISTS(SELECT 1 FROM users WHERE id=? AND creator_id=? AND role IN ('creator','admin'))`)
    .bind(id,user.creator_id,meta.title,meta.ai_generated?1:0,meta.ai_tool,nowSeconds(),raw,user.creator_id,user.id,user.creator_id).run();
  if (result.meta.changes!==1) {
    // 같은 요청의 동시 재시도만 성공으로 돌린다. 다른 메타데이터로 바꿔치기하지 않는다.
    const same = await env.DB.prepare('SELECT id FROM contents WHERE id=? AND creator_id=? AND title=? AND ai_generated=? AND ai_tool IS ? AND submission_json=?')
      .bind(id,user.creator_id,meta.title,meta.ai_generated?1:0,meta.ai_tool,raw).first();
    if (!same) throw new HttpError(409,'revision_conflict');
  }
  return json(201,{content_id:id});
}
