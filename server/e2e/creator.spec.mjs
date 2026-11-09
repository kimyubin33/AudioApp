import { test, expect } from '@playwright/test';
import { createTestHarness } from 'wrangler';
import { issueToken } from '../src/auth.ts';
import { zipSync } from 'fflate';
const secret='browser-integration-secret-not-a-real-service-secret';
const server=createTestHarness({workers:[{configPath:'./wrangler.toml',secrets:{
 TOKEN_SECRET:secret,GOOGLE_CLIENT_ID:'',DEV_MODE:'1',API_BASE_URL:'https://unused-app-origin.test'}}]});
const worker=server.getWorker();let env,base;
test.beforeAll(async()=>{base=(await server.listen()).url.origin;await worker.applyD1Migrations('DB');env=await worker.getEnv();});
test.afterAll(async()=>{await server.close();});
test.beforeEach(async()=>{
 for(const table of ['moderation_events','reports','uploads','contents','blocks','subscriptions','users'])await env.DB.prepare('DELETE FROM '+table).run();
 for(const [id,role,creator] of [['web_owner','creator','creator_web'],['web_admin','admin',null],['web_viewer','user',null]])
  await env.DB.prepare('INSERT INTO users(id,google_sub,role,creator_id,created_at) VALUES(?,?,?,?,1)').bind(id,id,role,creator).run();
});
async function signContext(browser,id){
 const context=await browser.newContext();await context.addCookies([{name:'audio_dev_session',value:await issueToken({TOKEN_SECRET:secret},id),
  url:base,httpOnly:true,sameSite:'Strict',secure:false}]);return context;
}
async function makeAudio(page,frequency=440){
 return Buffer.from(await page.evaluate(async frequency=>{
  const mime=['audio/mp4;codecs=mp4a.40.2','audio/mp4'].find(m=>MediaRecorder.isTypeSupported(m));
  if(!mime)throw new Error('이 테스트 브라우저는 AAC MP4 기록을 지원해야 합니다.');
  const context=new AudioContext(),destination=context.createMediaStreamDestination(),tone=context.createOscillator(),gain=context.createGain();
  tone.frequency.value=frequency;gain.gain.value=.02;tone.connect(gain);gain.connect(destination);
  await context.resume();const recorder=new MediaRecorder(destination.stream,{mimeType:mime,audioBitsPerSecond:96000}),chunks=[];
  const completed=new Promise(resolve=>{recorder.ondataavailable=e=>chunks.push(e.data);recorder.onstop=resolve;});
  recorder.start();tone.start();await new Promise(resolve=>setTimeout(resolve,1100));tone.stop();recorder.stop();await completed;await context.close();
  return Array.from(new Uint8Array(await new Blob(chunks,{type:mime}).arrayBuffer()));
 },frequency));
}
async function playAndConfirm(page,admin=false){
 const media=page.locator('audio').last();await expect(media).toBeVisible();
 await media.evaluate(async e=>{await e.play();});
 await page.getByLabel(admin?'원본을 재생해 파일·길이·코덱과 화면·소리를 확인했습니다.':'직접 재생해 올바른 파일·길이·화면과 소리임을 확인했습니다.').check();
 if(admin){
  await page.getByLabel('권리 진술·이용 근거와 필요 동의를 확인했습니다.').check();
  await page.getByLabel('제목·AI 표시·설명·태그와 선택적 공개 장소를 확인했습니다.').check();
 }
}
async function step(page,name){await page.getByRole('button',{name,exact:true}).click();}
test('여러 ZIP 검증·초안 복원 → 비공개 제출 → 버전 충돌 → 반려 수정 → 승인 → 공개 중단',async({browser},testInfo)=>{
 const owner=await signContext(browser,'web_owner'),admin=await signContext(browser,'web_admin');
 try{
 const page=await owner.newPage();page.on('dialog',d=>d.accept());await page.goto(base+'/creator');
 const audio=await makeAudio(page),second=await makeAudio(page,880);
 const zip=(name,entries)=>({name,mimeType:'application/zip',buffer:Buffer.from(zipSync(entries,{level:0}))});
 await page.locator('input[type=file]').setInputFiles([
  zip('one.zip',{'folder/first.m4a':audio}),zip('two.zip',{'second.m4a':second}),zip('unsafe.zip',{'../bad.m4a':audio})]);
 await expect(page.getByRole('button',{name:'first',exact:true})).toBeVisible({timeout:30_000});
 await expect(page.getByRole('button',{name:'second',exact:true})).toBeVisible();
 await expect(page.locator('#notice')).toContainText('경로');
 await page.reload();await expect(page.getByRole('button',{name:'first',exact:true})).toBeVisible();
 await playAndConfirm(page);const previousPreview=await page.locator('audio').elementHandle();await step(page,'2. 작품 정보');
 expect(await previousPreview.evaluate(media=>media.paused)).toBe(true);
 await page.getByLabel('작품 제목',{exact:true}).fill('브라우저에서 올린 AI 오디오');
 await page.getByLabel('사용한 AI 도구',{exact:true}).fill('테스트 합성 도구');
 await page.getByLabel('작품 설명',{exact:true}).fill('완성한 AI 오디오 공유 테스트');
 await page.getByLabel('태그 (쉼표로 구분)',{exact:true}).fill('테스트, 오디오');
 await step(page,'3. 권리 확인');await page.getByLabel('권리자',{exact:true}).fill('테스트 제작자');
 await page.getByLabel('원본·음악·영상·소재를 포함해 이 플랫폼에서 배포할 권한이 있습니다.').check();
 await page.getByLabel('실존 인물의 초상·음성이 있다면 필요한 동의를 받았습니다.').check();
 await page.getByLabel('사용한 AI 도구의 약관에서 이 결과물의 배포를 허용하는지 확인했습니다.').check();
 await step(page,'4. 장소 연결');await page.getByLabel('이 작품을 장소·지역 캐릭터·이야기와 연결합니다.').check();
 await page.getByLabel('공개 장소 이름',{exact:true}).fill('테스트 공개 공원');
 await page.getByLabel('위도 (-90 ~ 90)',{exact:true}).fill('37.5');await page.getByLabel('경도 (-180 ~ 180)',{exact:true}).fill('127');
 await page.getByLabel('지역 캐릭터 (선택)',{exact:true}).fill('달빛 수호자');
 await page.getByLabel('개인 주거지나 민감한 위치가 아닌 공개 장소임을 확인했습니다.').check();
 await step(page,'5. 최종 검토');await expect(page.getByRole('button',{name:'검수 요청',exact:true})).toBeEnabled();
 await page.screenshot({path:testInfo.outputPath('creator-desktop.png'),fullPage:true});
 await page.getByRole('button',{name:'검수 요청',exact:true}).click();
 await expect(page.getByRole('heading',{name:'검수를 요청했습니다'})).toBeVisible({timeout:30_000});
 const listed=await (await worker.fetch(base+'/v1/contents')).json();expect(listed.items).toHaveLength(0);
 const content=await env.DB.prepare('SELECT id,revision FROM contents').first();expect(content.revision).toBe(1);
 expect(await page.evaluate(()=>document.cookie)).toBe('');
 const review=await admin.newPage();await review.goto(base+'/admin');
 await review.getByRole('button',{name:'원본·제출 정보 검토',exact:true}).click();await playAndConfirm(review,true);
 // 크리에이터가 검수 중 내용을 바꾼 경우, 열려 있던 이전 버전은 승인할 수 없어야 한다.
 const row=await env.DB.prepare('SELECT * FROM contents WHERE id=?').bind(content.id).first();
 const submission=JSON.parse(row.submission_json);
 const changed={title:'수정한 AI 오디오',kind:'audio',duration_seconds:row.duration_seconds,ai_generated:true,ai_tool:row.ai_tool,
  content_type:'audio/mp4',submission,revision:1};
 const changedResult=await worker.fetch(base+'/v1/me/contents/'+content.id,{method:'PATCH',headers:{
  Authorization:'Bearer '+await issueToken({TOKEN_SECRET:secret},'web_owner'),'Content-Type':'application/json'},body:JSON.stringify(changed)});
 expect(changedResult.status).toBe(204);
 await review.getByRole('button',{name:'확인한 버전 승인',exact:true}).click();await expect(review.locator('#notice')).toContainText('변경되었습니다');
 await review.getByRole('button',{name:'현재 버전 다시 불러오기',exact:true}).click();
 await review.getByLabel('반려·공개 중단 사유 (필수, 최대 200자)').fill('출처 근거를 보완해 주세요.');
 await review.getByRole('button',{name:'반려',exact:true}).click();
 await expect(review.locator('#notice')).toContainText('반려 사유');
 await page.reload();await expect(page.getByText('검토 사유: 출처 근거를 보완해 주세요.',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:'정보 수정·재검수 요청',exact:true}).click();await playAndConfirm(page);await step(page,'3. 권리 확인');
 await page.getByLabel('이용 근거·허락 범위·표시할 출처',{exact:true}).fill('테스트 제작자가 직접 만든 음원과 동의한 AI 결과물');
 await step(page,'5. 최종 검토');await page.getByRole('button',{name:'수정하고 재검수 요청',exact:true}).click();
 await expect(page.getByRole('heading',{name:'검수를 요청했습니다'})).toBeVisible();
 await review.reload();await review.getByRole('button',{name:'원본·제출 정보 검토',exact:true}).click();await playAndConfirm(review,true);
 await review.screenshot({path:testInfo.outputPath('review-desktop.png'),fullPage:true});
 await review.getByRole('button',{name:'확인한 버전 승인',exact:true}).click();await expect(review.locator('#notice')).toContainText('승인했습니다');
 expect((await (await worker.fetch(base+'/v1/contents')).json()).items).toHaveLength(1);
 const range=await worker.fetch(base+'/v1/contents/'+content.id+'/media',{headers:{Range:'bytes=0-15'}});expect(range.status).toBe(206);
 await review.getByRole('button',{name:'공개 중 목록',exact:true}).click();await review.getByRole('button',{name:'원본·제출 정보 검토',exact:true}).click();
 await review.getByLabel('반려·공개 중단 사유 (필수, 최대 200자)').fill('테스트 공개 종료');
 await review.getByRole('button',{name:'공개 중단',exact:true}).last().click();
 await expect(review.locator('#notice')).toContainText('공개를 중단');
 expect((await worker.fetch(base+'/v1/contents/'+content.id+'/media')).status).toBe(404);
 const history=await env.DB.prepare('SELECT * FROM moderation_events ORDER BY id').all();expect(history.results.map(x=>x.to_status)).toEqual(['rejected','approved','removed']);
 await page.setViewportSize({width:390,height:844});await page.screenshot({path:testInfo.outputPath('creator-mobile.png'),fullPage:true});
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 }finally{await owner.close();await admin.close();}
});
test('초안 저장은 다른 탭의 최신 버전을 덮어쓰지 않는다',async({browser})=>{
 const context=await signContext(browser,'web_owner');try{
  const first=await context.newPage();first.on('dialog',d=>d.accept());await first.goto(base+'/creator');
  const audio=await makeAudio(first);await first.locator('input[type=file]').setInputFiles({name:'conflict.m4a',mimeType:'audio/mp4',buffer:audio});
  await expect(first.getByRole('button',{name:'conflict',exact:true})).toBeVisible({timeout:30_000});await step(first,'2. 작품 정보');
  const second=await context.newPage();second.on('dialog',d=>d.accept());await second.goto(base+'/creator');await step(second,'2. 작품 정보');
  await second.getByLabel('작품 제목',{exact:true}).fill('다른 탭의 최신 제목');await expect(second.locator('#save-status')).toContainText('저장됨');
  await first.getByLabel('작품 제목',{exact:true}).fill('오래된 탭의 제목');await expect(first.locator('#notice')).toContainText('다른 탭에서');
  await first.reload();await step(first,'2. 작품 정보');await expect(first.getByLabel('작품 제목',{exact:true})).toHaveValue('다른 탭의 최신 제목');
 }finally{await context.close();}
});
test('AI 쇼츠는 실제 영상 트랙·해상도·미리 재생을 확인하고 비공개 R2로 제출한다',async({browser},testInfo)=>{
 const context=await signContext(browser,'web_owner');try{
  const page=await context.newPage();page.on('dialog',d=>d.accept());await page.goto(base+'/creator');
  const fakeVideo=await makeAudio(page);
  const video=Buffer.from(await page.evaluate(async()=>{
   const mime=['video/mp4;codecs=avc1.42001E,mp4a.40.2','video/mp4'].find(m=>MediaRecorder.isTypeSupported(m));
   if(!mime)throw new Error('이 테스트 브라우저는 H.264/AAC MP4 기록을 지원해야 합니다.');
   const canvas=document.createElement('canvas');canvas.width=320;canvas.height=480;const paint=canvas.getContext('2d');
   paint.fillStyle='#fafaf7';paint.fillRect(0,0,320,480);
   const audio=new AudioContext(),destination=audio.createMediaStreamDestination(),tone=audio.createOscillator(),gain=audio.createGain();
   gain.gain.value=.01;tone.connect(gain);gain.connect(destination);await audio.resume();
   const stream=canvas.captureStream(12);stream.addTrack(destination.stream.getAudioTracks()[0]);
   const recorder=new MediaRecorder(stream,{mimeType:mime,videoBitsPerSecond:200000}),chunks=[];
   const completed=new Promise(resolve=>{recorder.ondataavailable=e=>chunks.push(e.data);recorder.onstop=resolve;});
   let frame=0;const draw=setInterval(()=>{paint.fillStyle='#fafaf7';paint.fillRect(0,0,320,480);paint.fillStyle='#181816';paint.fillRect(50+(frame++%20)*5,200,40,40);},80);
   recorder.start();tone.start();await new Promise(resolve=>setTimeout(resolve,1100));tone.stop();recorder.stop();await completed;clearInterval(draw);stream.getTracks().forEach(t=>t.stop());await audio.close();
   return Array.from(new Uint8Array(await new Blob(chunks,{type:mime}).arrayBuffer()));
  }));
  await page.locator('input[type=file]').setInputFiles([{name:'audio-only.mp4',mimeType:'video/mp4',buffer:fakeVideo},{name:'short.mp4',mimeType:'video/mp4',buffer:video}]);
  await expect(page.getByRole('button',{name:'short',exact:true})).toBeVisible({timeout:30_000});
  await expect(page.locator('#notice')).toContainText('영상 트랙');
  await expect(page.getByText('320 × 480',{exact:true})).toBeVisible();
  await page.locator('video').evaluate(async v=>{await v.play();});
  await page.getByLabel('직접 재생해 올바른 파일·길이·화면과 소리임을 확인했습니다.').check();
  await page.setViewportSize({width:390,height:844});await page.screenshot({path:testInfo.outputPath('shorts-mobile.png'),fullPage:true});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await step(page,'2. 작품 정보');await page.getByLabel('사용한 AI 도구',{exact:true}).fill('테스트 영상 도구');
  await step(page,'3. 권리 확인');await page.getByLabel('권리자',{exact:true}).fill('테스트 제작자');
  await page.getByLabel('원본·음악·영상·소재를 포함해 이 플랫폼에서 배포할 권한이 있습니다.').check();
  await page.getByLabel('실존 인물의 초상·음성이 있다면 필요한 동의를 받았습니다.').check();
  await page.getByLabel('사용한 AI 도구의 약관에서 이 결과물의 배포를 허용하는지 확인했습니다.').check();
  await step(page,'5. 최종 검토');await page.getByRole('button',{name:'검수 요청',exact:true}).click();await expect(page.getByRole('heading',{name:'검수를 요청했습니다'})).toBeVisible();
  const row=await env.DB.prepare('SELECT * FROM contents').first();expect(row.kind).toBe('short_video');expect(row.status).toBe('pending');
  const response=await worker.fetch(base+'/v1/admin/contents/'+row.id+'/review',{method:'POST',headers:{Authorization:'Bearer '+await issueToken({TOKEN_SECRET:secret},'web_admin'),'Content-Type':'application/json'},
   body:JSON.stringify({action:'approve',revision:1,file_reviewed:true,rights_reviewed:true,metadata_reviewed:true})});expect(response.status).toBe(204);
  expect((await (await worker.fetch(base+'/v1/contents?kind=short_video')).json()).items).toHaveLength(1);
  const media=await worker.fetch(base+'/v1/contents/'+row.id+'/media',{headers:{Range:'bytes=0-15'}});expect(media.status).toBe(206);expect(media.headers.get('content-type')).toBe('video/mp4');
 }finally{await context.close();}
});
test('일반 계정은 검수 데이터에 접근할 수 없고 로그인 전에도 초안을 시작할 수 있다',async({browser})=>{
 const context=await signContext(browser,'web_viewer');try{
 const page=await context.newPage();await page.goto(base+'/admin');await expect(page.getByText('운영자 권한이 필요합니다. 이 계정에는 검수 권한이 없습니다.')).toBeVisible();
 expect((await context.request.get(base+'/v1/admin/contents')).status()).toBe(403);
 await context.clearCookies();await page.goto(base+'/creator');await expect(page.getByRole('button',{name:'오디오·쇼츠·ZIP 추가',exact:true})).toBeEnabled();
 expect((await context.request.get(base+'/v1/admin/contents')).status()).toBe(401);
 }finally{await context.close();}
});
