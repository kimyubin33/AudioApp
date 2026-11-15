import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {VALIDATION_FAILURE_CODES} from '../src/media-validation.ts';

test('게시글 마이그레이션은 기존 콘텐츠·신고·업로드·심사 이력·장소와 트리거를 보존한다',()=>{
 const db=new DatabaseSync(':memory:');
 try{
  db.exec('PRAGMA foreign_keys=ON');
  for(const file of ['0001_init.sql','0002_subscriptions.sql','0003_uploads.sql','0004_submission.sql','0005_nearby.sql','0006_moderation.sql'])
   db.exec(readFileSync(new URL('../migrations/'+file,import.meta.url),'utf8'));
  db.exec("INSERT INTO users(id,google_sub,role,creator_id,created_at) VALUES('u','g','creator','creator_one',1)");
  db.prepare("INSERT INTO contents(id,creator_id,title,kind,duration_seconds,ai_generated,status,external_url,created_at,submission_json) VALUES('c','creator_one','old','audio',10,0,'pending','https://example.com/a.mp3',1,?)")
    .run(JSON.stringify({location:{latitude:37.5,longitude:127}}));
  db.exec("UPDATE contents SET status='approved' WHERE id='c'");
  db.exec("INSERT INTO reports(id,reporter_user_id,content_id,reason,created_at) VALUES('r','u','c','spam',1)");
  db.exec("INSERT INTO uploads(id,user_id,creator_id,content_id,title,kind,duration_seconds,ai_generated,content_type,object_key,token_hash,created_at,expires_at) VALUES('up','u','creator_one','future','old','audio',10,0,'audio/mpeg','uploads/up','hash',1,2)");
  db.exec('BEGIN');
  db.exec(readFileSync(new URL('../migrations/0007_posts_and_short_styles.sql',import.meta.url),'utf8'));
  db.exec('COMMIT');
  assert.equal(db.prepare('SELECT title FROM contents WHERE id=\'c\'').get().title,'old');
  assert.equal(db.prepare('SELECT place_latitude FROM contents WHERE id=\'c\'').get().place_latitude,37.5);
  assert.equal(db.prepare('SELECT COUNT(*) n FROM reports').get().n,1);
  assert.equal(db.prepare('SELECT COUNT(*) n FROM moderation_events').get().n,1);
  assert.equal(db.prepare('SELECT COUNT(*) n FROM uploads').get().n,1);
  assert.equal(db.prepare('SELECT short_style FROM contents').get().short_style,null);
  db.exec("UPDATE contents SET status='removed' WHERE id='c'");
  assert.equal(db.prepare('SELECT COUNT(*) n FROM moderation_events').get().n,2);
  for(const file of ['0008_account_lifecycle.sql','0009_report_workflow.sql'])
    db.exec(readFileSync(new URL('../migrations/'+file,import.meta.url),'utf8'));
  assert.equal(db.prepare("SELECT status FROM reports WHERE id='r'").get().status,'open');
  db.exec("UPDATE reports SET status='reviewing',revision=2,action='review',resolution='검토 시작',reviewed_by='u',reviewed_at=2 WHERE id='r'");
  assert.equal(db.prepare('SELECT COUNT(*) n FROM report_events').get().n,1);
  db.exec("DELETE FROM reports WHERE id='r'");
  assert.equal(db.prepare('SELECT COUNT(*) n FROM report_events').get().n,0);
  db.exec("UPDATE contents SET object_key='legacy/content' WHERE id='c'");
  db.exec(`INSERT INTO uploads(id,user_id,creator_id,content_id,title,kind,duration_seconds,ai_generated,content_type,object_key,token_hash,created_at,expires_at,submission_json,uploaded_bytes)
    SELECT 'up_small',user_id,creator_id,'small',title,kind,duration_seconds,ai_generated,content_type,'uploads/small',token_hash,created_at,expires_at,'{"file":{"size":128}}',256 FROM uploads WHERE id='up'`);
  db.exec(readFileSync(new URL('../migrations/0010_upload_quota.sql',import.meta.url),'utf8'));
  assert.equal(db.prepare("SELECT bytes FROM upload_quota WHERE object_key='uploads/up'").get().bytes,94371840);
  assert.equal(db.prepare("SELECT bytes FROM upload_quota WHERE object_key='uploads/small'").get().bytes,256);
  assert.equal(db.prepare("SELECT bytes FROM upload_quota WHERE object_key='legacy/content'").get().bytes,94371840);
  assert.equal(db.prepare('SELECT COUNT(*) n FROM upload_quota').get().n,3);
  assert.equal(db.prepare('SELECT COUNT(*) n FROM contents').get().n,1);
  db.exec("DELETE FROM uploads WHERE id='up_small'");
  assert.ok(db.prepare("SELECT released_at FROM upload_quota WHERE object_key='uploads/small'").get().released_at);
  assert.deepEqual(db.prepare('PRAGMA foreign_key_check').all(),[]);
 } finally{db.close();}
});

test('검사 상태 마이그레이션 0011은 기존 데이터를 보존하고 탈퇴·신고·할당량 트리거와 공존한다',()=>{
 const db=new DatabaseSync(':memory:');
 const run=file=>db.exec(readFileSync(new URL('../migrations/'+file,import.meta.url),'utf8'));
 const count=table=>db.prepare(`SELECT COUNT(*) n FROM ${table}`).get().n;
 const sha='a'.repeat(64);
 const addJob=(id,{content='a',state='pending_publish',version=1}={})=>db.prepare(
  "INSERT INTO validation_jobs(job_id,content_id,object_key,source_sha256,validator_version,state,created_at,updated_at) VALUES(?,?,'k/a',?,?,?,1,1)")
  .run(id,content,sha,version,state);
 try{
  db.exec('PRAGMA foreign_keys=ON');
  for(const file of ['0001_init.sql','0002_subscriptions.sql','0003_uploads.sql','0004_submission.sql','0005_nearby.sql',
   '0006_moderation.sql','0007_posts_and_short_styles.sql','0008_account_lifecycle.sql','0009_report_workflow.sql','0010_upload_quota.sql']) run(file);
  // 1. 승인된 오디오·게시글·신고·심사 이력·업로드·할당량·장소
  db.exec("INSERT INTO users(id,google_sub,role,creator_id,created_at) VALUES('u','g1','creator','cr',1),('v','g2','user',NULL,1)");
  db.prepare("INSERT INTO contents(id,creator_id,title,kind,duration_seconds,ai_generated,status,object_key,created_at,submission_json) VALUES('a','cr','오디오','audio',10,0,'pending','k/a',1,?)")
   .run(JSON.stringify({location:{latitude:37.5,longitude:127}}));
  db.exec(`INSERT INTO contents(id,creator_id,title,kind,duration_seconds,ai_generated,status,created_at) VALUES('p','cr','게시글','post',0,0,'pending',1);
   INSERT INTO contents(id,creator_id,title,kind,duration_seconds,ai_generated,status,external_url,created_at) VALUES('x','cr','외부','audio',5,1,'pending','https://example.com/x.mp3',1);
   UPDATE contents SET status='approved',review_actor='v',reviewed_at=2;
   INSERT INTO reports(id,reporter_user_id,content_id,reason,created_at) VALUES('r','v','x','spam',1);
   INSERT INTO uploads(id,user_id,creator_id,content_id,title,kind,duration_seconds,ai_generated,content_type,object_key,token_hash,created_at,expires_at,submission_json)
    VALUES('up','u','cr','future','업로드','audio',10,0,'audio/mpeg','uploads/up','hash',1,2,'{"file":{"size":128}}')`);
  const snapshot=()=>({
   contents:db.prepare('SELECT id,title,kind,status,revision,place_latitude,review_actor FROM contents ORDER BY id').all(),
   reports:count('reports'),events:count('moderation_events'),uploads:count('uploads'),
   quota:db.prepare('SELECT object_key,bytes,released_at FROM upload_quota ORDER BY object_key').all(),
  });
  const before=snapshot();
  assert.equal(before.events,3);
  db.exec('BEGIN'); run('0011_validation_schema.sql'); db.exec('COMMIT');
  // 2. 기존 값 보존, 게시글·외부 URL 포함 전부 pending
  assert.deepEqual(snapshot(),before);
  assert.deepEqual(db.prepare('SELECT validation_status s,validation_version v,validation_failure_code f,validation_job_id j FROM contents GROUP BY 1,2,3,4').all().map(r=>({...r})),
   [{s:'pending',v:null,f:null,j:null}]);
  // 3. CHECK 제약
  assert.throws(()=>db.exec("UPDATE contents SET validation_status='bogus' WHERE id='a'"),/CHECK/);
  assert.throws(()=>db.exec("UPDATE contents SET validation_status='passed',validation_failure_code='codec' WHERE id='a'"),/CHECK/);
  assert.throws(()=>addJob('bad',{state:'bogus'}),/CHECK/);
  assert.throws(()=>db.exec("INSERT INTO validation_jobs(job_id,content_id,object_key,source_sha256,validator_version,created_at,updated_at) VALUES('short','a','k/a','abc',1,1,1)"),/CHECK/);
  // c. 실패 코드 형식: 상수 목록은 모두 허용, 대문자·공백·33자는 거부
  for(const code of VALIDATION_FAILURE_CODES)
   db.prepare("UPDATE contents SET validation_status='failed',validation_failure_code=? WHERE id='a'").run(code);
  for(const code of ['Codec','bad code','','a'.repeat(33),'codec-1']){
   assert.throws(()=>db.prepare("UPDATE contents SET validation_status='failed',validation_failure_code=? WHERE id='a'").run(code),/CHECK/,code);
   assert.throws(()=>db.prepare("INSERT INTO validation_jobs(job_id,content_id,object_key,source_sha256,validator_version,state,failure_code,created_at,updated_at) VALUES('f','a','k/a',?,1,'failed',?,1,1)").run(sha,code),/CHECK/,code);
  }
  // 4. 검사 컬럼 갱신은 심사 이력을 남기지 않는다
  db.exec("UPDATE contents SET validation_status='passed',validation_version=1,validation_failure_code=NULL,validation_job_id='j1' WHERE id='a'");
  assert.equal(count('moderation_events'),3);
  // b. 진행 중인 같은 조합의 두 번째 작업은 거부
  addJob('j1');
  assert.throws(()=>addJob('j2'),/UNIQUE/);
  db.exec("UPDATE validation_jobs SET state='running',attempts=1 WHERE job_id='j1'");
  assert.throws(()=>addJob('j2',{state:'queued'}),/UNIQUE/);
  assert.throws(()=>db.exec("UPDATE validation_jobs SET failure_code='timeout' WHERE job_id='j1'"),/CHECK/);
  addJob('v2',{version:2});
  // a. failed·cancelled 뒤에는 같은 조합으로 수동 재처리 가능
  db.exec("UPDATE validation_jobs SET state='failed',failure_code='corrupt' WHERE job_id='j1'");
  addJob('j2');
  db.exec("UPDATE validation_jobs SET state='cancelled' WHERE job_id='j2'");
  addJob('j3');
  assert.equal(count('validation_jobs'),4);
  // 7. 0009 신고 조치와 0010 할당량 해제는 0011 이후에도 동작
  db.exec("UPDATE reports SET status='resolved',revision=2,action='remove_content',resolution='삭제',reviewed_by='v',reviewed_at=3 WHERE id='r'");
  assert.equal(db.prepare("SELECT status FROM contents WHERE id='x'").get().status,'removed');
  assert.equal(db.prepare("SELECT validation_status FROM contents WHERE id='x'").get().validation_status,'pending');
  assert.equal(count('moderation_events'),4);
  assert.equal(count('report_events'),1);
  db.exec("DELETE FROM uploads WHERE id='up'");
  assert.ok(db.prepare("SELECT released_at FROM upload_quota WHERE object_key='uploads/up'").get().released_at);
  // 5. 탈퇴 요청 뒤: 검사 결과 반영은 허용, 재공개와 신규 작업은 거부
  db.exec("UPDATE users SET deletion_requested_at=5 WHERE id='u'");
  db.exec("UPDATE contents SET status='removed',reject_reason=NULL,review_actor='account-deletion',reviewed_at=5 WHERE creator_id='cr'");
  db.exec("UPDATE contents SET validation_status='failed',validation_failure_code='infra' WHERE id='a'");
  assert.throws(()=>db.exec("UPDATE contents SET status='pending' WHERE id='a'"),/inactive_account/);
  assert.throws(()=>addJob('late',{state:'pending_publish',version:3}),/inactive_account/);
  // 6. account-deletion.ts의 purge 순서로 지우면 작업 원장도 함께 삭제
  db.exec(`DELETE FROM reports WHERE reporter_user_id='u' OR creator_id='cr' OR content_id IN (SELECT id FROM contents WHERE creator_id='cr');
   DELETE FROM moderation_events WHERE content_id IN (SELECT id FROM contents WHERE creator_id='cr');
   DELETE FROM contents WHERE creator_id='cr';
   DELETE FROM uploads WHERE user_id='u';
   DELETE FROM users WHERE id='u' AND deletion_requested_at IS NOT NULL;`);
  assert.equal(count('validation_jobs'),0);
  assert.equal(count('contents'),0);
  assert.deepEqual(db.prepare('PRAGMA foreign_key_check').all(),[]);
 } finally{db.close();}
});
