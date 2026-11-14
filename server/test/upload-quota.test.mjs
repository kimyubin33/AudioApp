import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { before, beforeEach, after, test } from 'node:test';
import { build } from 'esbuild';
import { createTestHarness } from 'wrangler';
import { issueToken } from '../src/auth.ts';

const bundled=await build({stdin:{contents:"export * from './src/upload-quota'; export { cleanupUploads,createUpload } from './src/uploads';",
  resolveDir:process.cwd(),loader:'ts'},bundle:true,write:false,platform:'node',format:'esm'});
const api=await import('data:text/javascript;base64,'+Buffer.from(bundled.outputFiles[0].text).toString('base64'));
const config={TOKEN_SECRET:'quota-test-secret-quota-test-secret-12345',GOOGLE_CLIENT_ID:'test.apps.googleusercontent.com',
  DEV_MODE:'1',API_BASE_URL:'https://api.test',UPLOAD_DAILY_COUNT:'4',UPLOAD_DAILY_BYTES:'1024',UPLOAD_STORAGE_BYTES:'2048'};
const server=createTestHarness({workers:[{configPath:'./wrangler.toml',env:'test',secrets:config}]});
const worker=server.getWorker();
let env,tokens={};
const now=()=>Math.floor(Date.now()/1000);
function bytes(size=128){const b=new Uint8Array(size);b.set([73,68,51,4]);return b;}
function metadata(size=128){
 const file=bytes(size);
 return {title:'용량 검사',kind:'audio',duration_seconds:10,ai_generated:true,ai_tool:'test',content_type:'audio/mpeg',
  submission:{version:1,description:'',tags:[],location:null,file:{name:'test.mp3',size,sha256:createHash('sha256').update(file).digest('hex')},
   rights:{holder:'creator_one',license:'original',attribution:'',source_url:'',distribution_confirmed:true,person_consent_confirmed:true,ai_terms_confirmed:true}}};
}
async function call(method,path,{as='owner',body,raw,headers={}}={}){
 return worker.fetch(new URL(path,'https://api.test').href,{method,
  headers:{...(as?{Authorization:'Bearer '+tokens[as]}:{}),...(body?{'Content-Type':'application/json'}:{}),...headers},
  body:body?JSON.stringify(body):raw});
}
async function create(size=128,as='owner'){
 const r=await call('POST','/v1/uploads',{as,body:metadata(size)});
 assert.equal(r.status,201,await r.clone().text());return r.json();
}
async function seed(key,size,time=now(),released=null){
 await env.DB.prepare('INSERT INTO upload_quota(object_key,user_id,bytes,created_at,released_at) VALUES (?,?,?,?,?)')
  .bind(key,'owner',size,time,released).run();
}
async function error(response,code){
 assert.equal(response.status,429,await response.clone().text());
 assert.deepEqual(await response.json(),{error:code});
}
before(async()=>{
 await server.listen();await worker.applyD1Migrations('DB');env={...await worker.getEnv(),...config};
 for(const id of ['owner','other'])tokens[id]=await issueToken(config,id);
});
after(async()=>{await server.close();});
beforeEach(async()=>{
 for(const table of ['reports','moderation_events','uploads','contents','users'])await env.DB.prepare('DELETE FROM '+table).run();
 for(const id of ['owner','other'])await env.DB.prepare("INSERT INTO users(id,google_sub,role,creator_id,created_at) VALUES(?,?,'creator',?,1)")
  .bind(id,id,'creator_'+id).run();
});

test('동시 발급은 일일 건수 상한을 넘지 않고 다른 계정은 독립적이다',async()=>{
 const responses=await Promise.all(Array.from({length:9},()=>call('POST','/v1/uploads',{body:metadata()})));
 assert.equal(responses.filter(r=>r.status===201).length,4);
 for(const r of responses.filter(r=>r.status!==201))await error(r,'upload_daily_count_exceeded');
 const usage=await api.uploadUsage('owner',env);
 assert.deepEqual(usage,{daily_count:4,daily_bytes:512,storage_bytes:512});
 assert.equal((await env.DB.prepare("SELECT COUNT(*) n FROM uploads WHERE user_id='owner'").first()).n,4);
 await create(128,'other');
 assert.equal((await call('POST','/v1/uploads',{as:null,body:metadata()})).status,401);
});

test('일일 바이트는 정확한 경계까지 허용하고 위조한 큰 본문은 예약량을 넘지 못한다',async()=>{
 await create(512);const u=await create(512);
 const r=await call('POST','/v1/uploads',{body:metadata(128)});
 await error(r,'upload_daily_bytes_exceeded');assert.equal(r.headers.get('retry-after'),'86400');
 const over=await call('PUT',u.upload_url,{raw:bytes(513),headers:{'Content-Type':'audio/mpeg','Content-Length':'513'}});
 assert.equal(over.status,400);
 assert.equal((await env.DB.prepare('SELECT state FROM uploads WHERE id=?').bind(u.upload_id).first()).state,'created');
 assert.equal((await api.uploadUsage('owner',env)).storage_bytes,1024);
});

test('동시 예약은 저장량 상한을 넘지 않고 오래된 보관 파일도 계속 차감된다',async()=>{
 await seed('retained',1920,now()-90000);
 const rs=await Promise.all([call('POST','/v1/uploads',{body:metadata()}),call('POST','/v1/uploads',{body:metadata()})]);
 assert.deepEqual(rs.map(r=>r.status).sort(),[201,429]);
 const denied=rs.find(r=>r.status===429);await error(denied,'storage_quota_exceeded');
 assert.equal(denied.headers.get('retry-after'),null);
 assert.equal((await api.uploadUsage('owner',env)).storage_bytes,2048);
});

test('취소와 R2 정리 실패는 공간을 조기에 반환하지 않으며 정리 후에도 일일 사용량은 남는다',async()=>{
 const u=await create();
 assert.equal((await call('PUT',u.upload_url,{raw:bytes(),headers:{'Content-Type':'audio/mpeg','Content-Length':'128'}})).status,204);
 assert.equal((await call('DELETE','/v1/uploads/'+u.upload_id)).status,204);
 assert.equal((await api.uploadUsage('owner',env)).storage_bytes,128);
 await env.DB.prepare('UPDATE uploads SET expires_at=1 WHERE id=?').bind(u.upload_id).run();
 await assert.rejects(api.cleanupUploads({...env,MEDIA:{delete:async()=>{throw new Error('simulated');}}}));
 assert.equal((await api.uploadUsage('owner',env)).storage_bytes,128);
 assert.ok(await env.DB.prepare('SELECT id FROM uploads WHERE id=?').bind(u.upload_id).first());
 await api.cleanupUploads(env);
 assert.deepEqual(await api.uploadUsage('owner',env),{daily_count:1,daily_bytes:128,storage_bytes:0});
 await env.DB.prepare('UPDATE upload_quota SET created_at=?').bind(now()-86401).run();
 await api.cleanupUploads(env);
 assert.deepEqual(await api.uploadUsage('owner',env),{daily_count:0,daily_bytes:0,storage_bytes:0});
 assert.equal((await env.DB.prepare('SELECT COUNT(*) n FROM upload_quota').first()).n,0);
});

test('완료 재시도는 이중 차감하지 않고 반려·공개 중단은 보관량을 반환하지 않는다',async()=>{
 const u=await create();
 assert.equal((await call('PUT',u.upload_url,{raw:bytes(),headers:{'Content-Type':'audio/mpeg','Content-Length':'128'}})).status,204);
 for(let i=0;i<2;i++)assert.equal((await call('POST','/v1/uploads/'+u.upload_id+'/complete')).status,204);
 const row=await env.DB.prepare('SELECT content_id FROM uploads WHERE id=?').bind(u.upload_id).first();
 for(const status of ['rejected','removed']){
  await env.DB.prepare('UPDATE contents SET status=? WHERE id=?').bind(status,row.content_id).run();
  assert.equal((await api.uploadUsage('owner',env)).storage_bytes,128);
 }
 await env.DB.prepare('UPDATE uploads SET expires_at=1').run();
 await api.cleanupUploads(env);
 assert.equal((await api.uploadUsage('owner',env)).storage_bytes,128);
 assert.equal((await env.DB.prepare('SELECT COUNT(*) n FROM upload_quota').first()).n,1);
});

test('24시간 경계·취소 반복·잘못된 운영 한도와 계정 삭제 정리를 검증한다',async()=>{
 const t=now();await seed('boundary',128,t-86400,t);
 assert.deepEqual(await api.uploadUsage('owner',env,t),{daily_count:0,daily_bytes:0,storage_bytes:0});
 for(let i=0;i<4;i++){const u=await create();await call('DELETE','/v1/uploads/'+u.upload_id);}
 await error(await call('POST','/v1/uploads',{body:metadata()}),'upload_daily_count_exceeded');
 for(const field of ['UPLOAD_DAILY_COUNT','UPLOAD_DAILY_BYTES','UPLOAD_STORAGE_BYTES'])
  for(const value of ['', '0','-1','1.5','abc','9007199254740992']){
   const bad={...env,[field]:value};
   assert.throws(()=>api.uploadLimits(bad),e=>e.status===503&&e.code==='upload_limits_unavailable');
  }
 const defaults=api.uploadLimits({});
 assert.deepEqual(defaults,{daily_count:50,daily_bytes:536870912,storage_bytes:2147483648});
 await env.DB.prepare("DELETE FROM uploads WHERE user_id='owner'").run();
 await env.DB.prepare("DELETE FROM users WHERE id='owner'").run();
 assert.equal((await env.DB.prepare("SELECT COUNT(*) n FROM upload_quota WHERE user_id='owner'").first()).n,0);
});
