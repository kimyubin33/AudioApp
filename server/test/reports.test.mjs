import assert from 'node:assert/strict';
import {before,after,beforeEach,test} from 'node:test';
import {createTestHarness} from 'wrangler';
import {issueToken} from '../src/auth.ts';
const secret='reports-integration-test-secret-reports-12345';
const harness=createTestHarness({workers:[{configPath:'./wrangler.toml',secrets:{TOKEN_SECRET:secret,DEV_MODE:'1',GOOGLE_CLIENT_ID:'test'}}]});
const worker=harness.getWorker();let env;const tokens={};
before(async()=>{await harness.listen();await worker.applyD1Migrations('DB');env=await worker.getEnv();
 for(const id of ['owner','viewer','other','admin'])tokens[id]=await issueToken({TOKEN_SECRET:secret},id);});
after(async()=>harness.close());
beforeEach(async()=>{
 for(const table of ['validation_jobs','reports','moderation_events','uploads','contents','blocks','subscriptions','users'])await env.DB.prepare('DELETE FROM '+table).run();
 for(const [id,role,creator]of [['owner','creator','creator_one'],['viewer','user',null],['other','user',null],['admin','admin',null]])
  await env.DB.prepare('INSERT INTO users(id,google_sub,role,creator_id,created_at) VALUES(?,?,?,?,1)').bind(id,id,role,creator).run();
 for(const [id,kind]of [['audio','audio'],['short','short_video']])
  await env.DB.prepare("INSERT INTO contents(id,creator_id,title,kind,duration_seconds,ai_generated,status,external_url,created_at) VALUES(?,'creator_one',?,?,10,1,'approved','https://media.test/sample',1)").bind(id,id,kind).run();
});
async function call(method,path,{as='viewer',body}={}){
 return worker.fetch('https://api.test'+path,{method,headers:{...(as?{Authorization:'Bearer '+tokens[as]}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{})},body:body===undefined?undefined:JSON.stringify(body)});
}
async function report(body={content_id:'audio',reason:'copyright',detail:'출처와 이용허락을 확인해 주세요.'},as='viewer'){
 const r=await call('POST','/v1/reports',{as,body});assert.equal(r.status,204,await r.text());
 return (await env.DB.prepare('SELECT id FROM reports WHERE reporter_user_id=? ORDER BY created_at DESC,id DESC LIMIT 1').bind(as).first()).id;
}
async function review(id,action,extra={}){
 return call('POST','/v1/admin/reports/'+id+'/review',{as:'admin',body:{action,revision:1,content_revision:1,resolution:'신고 검토 결과',confirmed:true,...extra}});
}
test('신고자 본인 내역만 반환하고 관리자 상세는 신고자 식별자를 노출하지 않는다',async()=>{
 const id=await report();
 assert.equal((await call('GET','/v1/admin/reports')).status,403);
 assert.equal((await call('GET','/v1/admin/reports/'+id,{as:null})).status,401);
 const detail=await(await call('GET','/v1/admin/reports/'+id,{as:'admin'})).json();
 assert.equal(detail.detail,'출처와 이용허락을 확인해 주세요.');
 assert.equal(detail.reporter_user_id,undefined);assert.equal(detail.google_sub,undefined);
 assert.equal((await(await call('GET','/v1/me/reports',{as:'other'})).json()).items.length,0);
 assert.equal((await(await call('GET','/v1/me/reports')).json()).items[0].status,'open');
 assert.equal((await call('GET','/v1/me/reports?cursor=bad')).status,400);
});
test('검토 → 공개 중단 → 신고 결과 조회와 감사 기록, 중복·오래된 조치 거부',async()=>{
 const id=await report();
 assert.equal((await review(id,'review')).status,204);
 assert.equal((await review(id,'remove_content')).status,409);
 assert.equal((await review(id,'remove_content',{revision:2,confirmed:false})).status,400);
 // 0009 신고 조치 트리거가 contents를 removed로 바꾸면 0012가 진행 중 검사 작업만 취소한다.
 for(const [job,state]of [['vj_active','queued'],['vj_done','passed']])
  await env.DB.prepare("INSERT INTO validation_jobs(job_id,content_id,object_key,source_sha256,validator_version,state,created_at,updated_at) VALUES(?,'audio','k',?,1,?,1,1)").bind(job,'a'.repeat(64),state).run();
 assert.equal((await review(id,'remove_content',{revision:2})).status,204);
 const jobStates=(await env.DB.prepare('SELECT job_id,state,updated_at FROM validation_jobs ORDER BY job_id').all()).results;
 assert.deepEqual(jobStates.map(j=>[j.job_id,j.state]),[['vj_active','cancelled'],['vj_done','passed']]);
 assert.ok(jobStates[0].updated_at>1);assert.equal(jobStates[1].updated_at,1);
 assert.equal((await call('GET','/v1/contents/audio',{as:null})).status,404);
 assert.equal((await call('GET','/v1/contents/audio/media',{as:null})).status,404);
 const own=(await(await call('GET','/v1/me/reports')).json()).items[0];
 assert.equal(own.status,'resolved');assert.equal(own.resolution,'신고 검토 결과');
 assert.equal((await env.DB.prepare('SELECT COUNT(*) AS n FROM report_events').first()).n,2);
 assert.equal((await review(id,'dismiss',{revision:3})).status,409);
});
test('동시 처리 한 건만 성공하며 콘텐츠가 수정되면 이전 검토 버전은 거부한다',async()=>{
 const id=await report();
 await env.DB.prepare("UPDATE contents SET revision=2 WHERE id='audio'").run();
 assert.equal((await review(id,'remove_content')).status,409);
 assert.equal((await env.DB.prepare('SELECT COUNT(*) AS n FROM report_events').first()).n,0);
 const results=await Promise.all([review(id,'remove_content',{content_revision:2}),review(id,'dismiss')]);
 assert.deepEqual(results.map(r=>r.status).sort(),[204,409]);
 assert.equal((await env.DB.prepare('SELECT COUNT(*) AS n FROM report_events').first()).n,1);
});
test('크리에이터 신고와 권한 회수는 콘텐츠 신고와 구별되며 기존 콘텐츠는 임의 삭제하지 않는다',async()=>{
 const id=await report({creator_id:'creator_one',reason:'other',detail:'음성 사칭 의심'});
 assert.equal((await review(id,'remove_content')).status,409);
 assert.equal((await review(id,'revoke_upload')).status,204);
 assert.equal((await env.DB.prepare("SELECT role FROM users WHERE id='owner'").first()).role,'user');
 assert.equal((await call('POST','/v1/uploads',{as:'owner',body:{}})).status,403);
 assert.equal((await call('GET','/v1/contents/audio',{as:null})).status,200);
 assert.equal((await call('POST','/v1/reports',{as:'owner',body:{creator_id:'creator_one',reason:'spam'}})).status,404);
});
test('신고 대상·사유·설명 검증 및 중복 방지, 새 신고 일일 상한은 원자적으로 제한',async()=>{
 assert.equal((await call('POST','/v1/reports',{body:{content_id:'audio',creator_id:'creator_one',reason:'spam'}})).status,400);
 assert.equal((await call('POST','/v1/reports',{body:{creator_id:'creator_one',reason:'other'}})).status,400);
 assert.equal((await call('POST','/v1/reports',{body:{content_id:'missing',reason:'spam'}})).status,404);
 await report();await report();
 assert.equal((await env.DB.prepare('SELECT COUNT(*) AS n FROM reports').first()).n,1);
 for(let i=0;i<20;i++)await env.DB.prepare("INSERT INTO contents(id,creator_id,title,kind,duration_seconds,ai_generated,status,external_url,created_at) VALUES(?,'creator_one','test','audio',1,0,'approved','https://media.test/a',1)").bind('a'+i).run();
 const results=await Promise.all(Array.from({length:20},(_,i)=>call('POST','/v1/reports',{body:{content_id:'a'+i,reason:'spam'}})));
 assert.equal(results.filter(r=>r.status===204).length,19);
 assert.equal(results.filter(r=>r.status===429).length,1);
 await report(); // duplicate remains safe at quota
});
test('기본 출시 범위는 API 조회·상세·미디어·업로드·수정·승인까지 오디오로 제한',async()=>{
 const page=await(await call('GET','/v1/contents',{as:null})).json();
 assert.deepEqual(page.items.map(i=>i.id),['audio']);
 assert.equal((await(await call('GET','/v1/contents?kind=short_video',{as:null})).json()).items.length,0);
 assert.equal((await call('GET','/v1/contents/short',{as:null})).status,404);
 assert.equal((await call('GET','/v1/contents/short/media',{as:null})).status,404);
 assert.equal((await call('POST','/v1/me/posts',{as:'owner',body:{}})).status,403);
 assert.equal((await call('PATCH','/v1/me/contents/short',{as:'owner',body:{kind:'short_video'}})).status,403);
 assert.equal((await call('POST','/v1/uploads',{as:'owner',body:{kind:'short_video',title:'new',duration_seconds:10,ai_generated:true,ai_tool:'test',content_type:'video/mp4',short_style:'story'}})).status,403);
 await env.DB.prepare("UPDATE contents SET status='pending' WHERE id='short'").run();
 const approval=await call('POST','/v1/admin/contents/short/review',{as:'admin',body:{action:'approve',revision:1,file_reviewed:true,rights_reviewed:true,metadata_reviewed:true}});
 assert.equal(approval.status,403);
 assert.equal((await(await call('GET','/v1/web/config',{as:null})).json()).extended_formats,false);
 await env.MEDIA.put('old-short',new Uint8Array([0,1,2]),{httpMetadata:{contentType:'video/mp4'}});
 await env.DB.prepare("UPDATE contents SET status='approved',object_key='old-short',external_url=NULL WHERE id='short'").run();
 assert.equal((await call('GET','/v1/me/contents/short/media',{as:'admin'})).status,200);
 assert.equal((await call('GET','/v1/contents/short/media',{as:null})).status,404);
 await env.DB.prepare("INSERT INTO uploads(id,user_id,creator_id,content_id,title,kind,duration_seconds,ai_generated,content_type,object_key,token_hash,created_at,expires_at) VALUES('old-ticket','owner','creator_one','future-short','old','short_video',10,1,'video/mp4','old-ticket','unused',1,9999999999)").run();
 assert.equal((await call('PUT','/v1/uploads/old-ticket/file',{as:'owner'})).status,403);
 assert.equal((await call('POST','/v1/uploads/old-ticket/complete',{as:'owner'})).status,403);
 const submission={version:1,description:'',tags:[],file:{name:'a.mp3',size:128,sha256:'0'.repeat(64)},
  rights:{holder:'owner',license:'original',attribution:'',source_url:'',distribution_confirmed:true,person_consent_confirmed:true,ai_terms_confirmed:true},
  location:{name:'공개 공원',latitude:37.5,longitude:127,radius_meters:1000,character:'',story:'',public_location_confirmed:true}};
 await env.DB.prepare('UPDATE contents SET submission_json=?').bind(JSON.stringify(submission)).run();
 const nearby=await(await call('POST','/v1/discovery/nearby',{as:null,body:{latitude:37.5,longitude:127,radius_meters:10000}})).json();
 assert.deepEqual(nearby.items.map(i=>i.id),['audio']);
});
