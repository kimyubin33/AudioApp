import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {after,before,beforeEach,test} from 'node:test';
import {createTestHarness} from 'wrangler';
import {issueToken} from '../src/auth.ts';
const secret='post-test-secret-post-test-secret-1234';
const harness=createTestHarness({workers:[{configPath:'./wrangler.toml',secrets:{TOKEN_SECRET:secret,DEV_MODE:'1',ENABLE_EXTENDED_FORMATS:'1',GOOGLE_CLIENT_ID:'test',API_BASE_URL:'https://api.test'}}]});
const worker=harness.getWorker();let env;const tokens={};
before(async()=>{await harness.listen();await worker.applyD1Migrations('DB');env=await worker.getEnv();for(const id of ['owner','viewer','admin'])tokens[id]=await issueToken({TOKEN_SECRET:secret},id);});
after(async()=>harness.close());
beforeEach(async()=>{
 for(const table of ['moderation_events','reports','uploads','contents','blocks','subscriptions','users'])await env.DB.prepare('DELETE FROM '+table).run();
 for(const [id,role,creator] of [['owner','creator','creator_one'],['viewer','user',null],['admin','admin',null]])await env.DB.prepare('INSERT INTO users(id,google_sub,role,creator_id,created_at) VALUES(?,?,?,?,1)').bind(id,id,role,creator).run();
});
function metadata(){return{client_id:'a1111111-1111-1111-1111-111111111111',title:'바다에서 보낸 하루',kind:'post',duration_seconds:0,ai_generated:false,ai_tool:null,content_type:null,
 submission:{version:1,description:'가족과 함께 걸었던 해변\n다시 만나고 싶다.',tags:['여행'],file:null,location:null,
 rights:{holder:'원작자',license:'original',attribution:'',source_url:'',distribution_confirmed:true,person_consent_confirmed:true,ai_terms_confirmed:true}}};}
async function call(method,path,{as='owner',body,raw,headers={}}={}){
 if(as)headers.Authorization='Bearer '+tokens[as];if(body!==undefined){headers['Content-Type']='application/json';raw=JSON.stringify(body);}
 return worker.fetch(new URL(path,'https://api.test').href,{method,headers,body:raw,duplex:'half'});
}
async function approve(id){const r=await call('POST','/v1/admin/contents/'+id+'/review',{as:'admin',body:{action:'approve',revision:1,file_reviewed:true,rights_reviewed:true,metadata_reviewed:true}});assert.equal(r.status,204,await r.text());}
test('게시글: 권한·권리, 중복 방지, 비공개 검수, 게시글 검색과 저장용 상세',async()=>{
 const m=metadata();
 assert.equal((await call('POST','/v1/me/posts',{as:null,body:m})).status,401);
 assert.equal((await call('POST','/v1/me/posts',{as:'viewer',body:m})).status,403);
 const invalid=structuredClone(m);invalid.submission.rights.distribution_confirmed=false;
 assert.equal((await call('POST','/v1/me/posts',{body:invalid})).status,400);
 const first=await call('POST','/v1/me/posts',{body:m});assert.equal(first.status,201,await first.clone().text());
 const {content_id:id}=await first.json();
 assert.equal((await call('POST','/v1/me/posts',{body:m}).then(r=>r.json())).content_id,id);
 assert.equal((await env.DB.prepare('SELECT COUNT(*) AS n FROM contents').first()).n,1);
 assert.equal((await call('GET','/v1/contents/'+id,{as:null})).status,404);
 await approve(id);
 const page=await (await call('GET','/v1/contents?kind=post&q='+encodeURIComponent('해변'),{as:null})).json();
 assert.equal(page.items.length,1);assert.equal(page.items[0].kind,'post');assert.equal(page.items[0].has_image,false);
 const detail=await (await call('GET','/v1/contents/'+id,{as:null})).json();
 assert.equal(detail.stream_url,null);assert.equal(detail.body,m.submission.description);assert.equal(detail.ai_generated,false);
 assert.equal(detail.rights,undefined);assert.equal(detail.submission_json,undefined);
 await call('PUT','/v1/blocks/creator_one',{as:'viewer'});
 assert.equal((await call('GET','/v1/contents?kind=post',{as:'viewer'}).then(r=>r.json())).items.length,0);
 assert.equal((await call('GET','/v1/contents/'+id,{as:'viewer'})).status,404);
});
test('사진 게시글: 해시 검증, 검수 전 비공개, 이미지 MIME과 원본 유지',async()=>{
 const bytes=new Uint8Array([255,216,255,224,0,16,74,70,73,70,0,1,1,0,0,1,0,1,0,0,255,217]);
 const m=metadata();delete m.client_id;m.content_type='image/jpeg';m.submission.file={name:'moment.jpg',size:bytes.length,sha256:createHash('sha256').update(bytes).digest('hex')};
 const ticketResponse=await call('POST','/v1/uploads',{body:m});assert.equal(ticketResponse.status,201,await ticketResponse.clone().text());const ticket=await ticketResponse.json();
 assert.equal((await call('PUT',ticket.upload_url,{raw:bytes,headers:{'Content-Type':'image/jpeg','Content-Length':String(bytes.length)}})).status,204);
 assert.equal((await call('POST','/v1/uploads/'+ticket.upload_id+'/complete')).status,204);
 const id=(await env.DB.prepare('SELECT content_id FROM uploads WHERE id=?').bind(ticket.upload_id).first()).content_id;
 assert.equal((await call('GET','/v1/contents/'+id+'/media',{as:null})).status,404);
 await approve(id);
 const image=await call('GET','/v1/contents/'+id+'/media',{as:null});
 assert.equal(image.headers.get('content-type'),'image/jpeg');assert.deepEqual(new Uint8Array(await image.arrayBuffer()),bytes);
 const detail=await (await call('GET','/v1/contents/'+id,{as:null})).json();assert.equal(detail.has_image,true);assert.equal(detail.stream_url,null);
});
test('Story/Moment: 혼합 목록, 성격 필터·커서·검색, 구버전 분류 없음',async()=>{
 for(const [id,style,created]of [['v_story','story',4],['v_moment','moment',3],['v_old',null,2]]){
  await env.DB.prepare("INSERT INTO contents(id,creator_id,title,kind,duration_seconds,ai_generated,status,external_url,created_at,short_style) VALUES(?,'creator_one',?,'short_video',10,0,'approved','https://example.com/v.mp4',?,?)").bind(id,id,created,style).run();
 }
 const all=await (await call('GET','/v1/contents?kind=short_video&limit=2',{as:null})).json();
 assert.deepEqual(all.items.map(x=>x.short_style),['story','moment']);
 const next=await (await call('GET','/v1/contents?kind=short_video&limit=2&cursor='+all.next_cursor,{as:null})).json();assert.equal(next.items[0].short_style,null);
 const moment=await (await call('GET','/v1/contents?kind=short_video&short_style=moment&q=v_moment',{as:null})).json();
 assert.deepEqual(moment.items.map(x=>x.id),['v_moment']);
 for(const path of ['/v1/contents?short_style=story','/v1/contents?kind=post&short_style=moment','/v1/contents?kind=short_video&short_style=bad'])assert.equal((await call('GET',path,{as:null})).status,400);
});
test('오디오 연결은 승인된 오디오만 허용하고 공개 중단을 즉시 반영',async()=>{
 await env.DB.prepare("INSERT INTO contents(id,creator_id,title,kind,duration_seconds,ai_generated,status,external_url,created_at) VALUES('a','creator_one','a','audio',10,0,'approved','https://example.com/a.mp3',1)").run();
 const m=metadata();m.submission.audio_content_id='missing';
 assert.equal((await call('POST','/v1/me/posts',{body:m})).status,400);
 m.submission.audio_content_id='a';const {content_id:id}=await (await call('POST','/v1/me/posts',{body:m})).json();await approve(id);
 assert.equal((await call('GET','/v1/contents/'+id,{as:null}).then(r=>r.json())).audio_content_id,'a');
 await env.DB.prepare("UPDATE contents SET status='removed' WHERE id='a'").run();
 assert.equal((await call('GET','/v1/contents/'+id,{as:null}).then(r=>r.json())).audio_content_id,null);
 const changed=structuredClone(m);changed.title='다른 글';
 assert.equal((await call('POST','/v1/me/posts',{body:changed})).status,409);
 assert.equal((await call('POST','/v1/me/posts',{body:m})).status,201);
});
