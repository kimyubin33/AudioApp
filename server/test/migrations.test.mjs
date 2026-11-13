import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';

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
