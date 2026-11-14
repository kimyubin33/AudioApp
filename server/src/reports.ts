import type { AppEnv } from './types';
import { HttpError,json,noContent,nowSeconds,newId,readJson,requireUser,requireAdmin } from './http';
import { cleanMultiline,cleanText,DETAIL_MAX,isValidId,isReportReason,parseLimit } from './validate';

export async function createReport(request:Request,env:AppEnv):Promise<Response>{
 const user=await requireUser(request,env),body=await readJson(request);
 const content=body.content_id??null,creator=body.creator_id??null;
 if((content===null)===(creator===null)||!isValidId(content??creator)||!isReportReason(body.reason))
  throw new HttpError(400,'invalid_request');
 let detail:string|null=null;
 if(body.detail!=null){
  if(typeof body.detail!=='string')throw new HttpError(400,'invalid_request');
  if(body.detail.trim()){detail=cleanMultiline(body.detail,DETAIL_MAX);if(!detail)throw new HttpError(400,'invalid_request');}
 }
 if(body.reason==='other'&&!detail)throw new HttpError(400,'invalid_request');
 const exists=content
  ?await env.DB.prepare("SELECT 1 FROM contents WHERE id=? AND status='approved' AND validation_status='passed'").bind(content).first()
  :await env.DB.prepare("SELECT 1 FROM users WHERE creator_id=? AND id!=? AND deletion_requested_at IS NULL").bind(creator,user.id).first();
 if(!exists)throw new HttpError(404,'not_found');
 const prior=await env.DB.prepare('SELECT id FROM reports WHERE reporter_user_id=? AND content_id IS ? AND creator_id IS ?')
  .bind(user.id,content,creator).first();
 if(prior)return noContent();
 const now=nowSeconds();
 const result=await env.DB.prepare(`INSERT OR IGNORE INTO reports(id,reporter_user_id,content_id,creator_id,reason,detail,created_at)
  SELECT ?,?,?,?,?,?,? WHERE
  (SELECT COUNT(*) FROM reports WHERE reporter_user_id=? AND created_at>?)<20
  AND EXISTS(SELECT 1 FROM users WHERE id=? AND deletion_requested_at IS NULL)`)
  .bind(newId('r'),user.id,content,creator,body.reason,detail,now,user.id,now-86400,user.id).run();
 if(result.meta.changes!==1){
  if(await env.DB.prepare('SELECT 1 FROM reports WHERE reporter_user_id=? AND content_id IS ? AND creator_id IS ?')
   .bind(user.id,content,creator).first())return noContent();
  throw new HttpError(429,'limit_reached');
 }
 return noContent();
}

export async function listReports(request:Request,env:AppEnv,admin=false):Promise<Response>{
 const user=admin?await requireAdmin(request,env):await requireUser(request,env);
 const p=new URL(request.url).searchParams,limit=parseLimit(p.get('limit')),cursor=p.get('cursor');
 const after=cursor===null?null:/^(\d{1,15})\.([A-Za-z0-9_-]{1,64})$/.exec(cursor);
 const status=p.get('status')??'open';
 if(limit===null||(cursor!==null&&!after)||(admin&&!['open','reviewing','resolved','dismissed'].includes(status)))throw new HttpError(400,'invalid_request');
 const {results}=await env.DB.prepare(`SELECT r.id,r.content_id,r.creator_id,r.reason,r.created_at,r.status,r.revision,r.action,r.resolution,r.reviewed_at,c.title
 FROM reports r LEFT JOIN contents c ON c.id=r.content_id
 WHERE ${admin?'r.status=?':'r.reporter_user_id=?'}
 AND (? IS NULL OR r.created_at<? OR (r.created_at=? AND r.id<?))
 ORDER BY r.created_at DESC,r.id DESC LIMIT ?`)
 .bind(admin?status:user.id,after?.[1]??null,after?.[1]??null,after?.[1]??null,after?.[2]??null,limit+1).all<Record<string,unknown>>();
 const items=results.slice(0,limit),last=items.at(-1);
 return json(200,{items,next_cursor:results.length>limit&&last?`${last.created_at}.${last.id}`:null});
}
export async function reportDetail(id:string,request:Request,env:AppEnv):Promise<Response>{
 await requireAdmin(request,env);
 if(!isValidId(id))throw new HttpError(400,'invalid_request');
 // Reporter identifiers and Google identities are deliberately excluded.
 const row=await env.DB.prepare(`SELECT r.id,r.content_id,r.creator_id,r.reason,r.detail,r.created_at,r.status,r.revision,
 r.action,r.resolution,r.reviewed_at,c.title,c.revision AS content_revision,c.status AS content_status,u.role AS creator_role
 FROM reports r LEFT JOIN contents c ON c.id=r.content_id LEFT JOIN users u ON u.creator_id=r.creator_id WHERE r.id=?`).bind(id).first();
 if(!row)throw new HttpError(404,'not_found');
 const {results}=await env.DB.prepare('SELECT from_status,to_status,action,actor,resolution,created_at FROM report_events WHERE report_id=? ORDER BY id DESC LIMIT 50').bind(id).all();
 return json(200,{...row,history:results});
}
export async function reviewReport(id:string,request:Request,env:AppEnv):Promise<Response>{
 const user=await requireAdmin(request,env),body=await readJson(request);
 const action=body.action,reason=cleanText(body.resolution,200);
 if(body.confirmed!==true||!isValidId(id)||!['review','dismiss','remove_content','revoke_upload'].includes(action as string)||
 !Number.isSafeInteger(body.revision)||(body.revision as number)<1||!reason)throw new HttpError(400,'invalid_request');
 if(action==='remove_content'&&!Number.isSafeInteger(body.content_revision))throw new HttpError(400,'invalid_request');
 const state=action==='review'?'reviewing':action==='dismiss'?'dismissed':'resolved';
 const result=await env.DB.prepare(`UPDATE reports SET status=?,action=?,resolution=?,reviewed_by=?,reviewed_at=?,revision=revision+1
 WHERE id=? AND revision=? AND status IN ('open','reviewing')
 AND (?!='review' OR status='open')
 AND (?!='remove_content' OR EXISTS(SELECT 1 FROM contents WHERE id=reports.content_id AND revision=? AND status IN ('approved','pending')))
 AND (?!='revoke_upload' OR EXISTS(SELECT 1 FROM users WHERE creator_id=reports.creator_id AND role='creator' AND deletion_requested_at IS NULL AND id!=?))
 AND EXISTS(SELECT 1 FROM users WHERE id=? AND role='admin' AND deletion_requested_at IS NULL) RETURNING id`)
 .bind(state,action,reason,user.id,nowSeconds(),id,body.revision as number,action,action,
 typeof body.content_revision==='number'?body.content_revision:0,action,user.id,user.id).first<{id:string}>();
 if(!result)throw new HttpError(409,'revision_conflict');
 return noContent();
}
