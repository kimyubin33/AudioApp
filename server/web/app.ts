import { validateSubmission } from '../src/submission';
import { uploadMetadata } from '../src/upload-validation';
import { cleanText, isValidId } from '../src/validate';
import { allDrafts, saveDraft, saveNewDrafts, deleteDraft, type WebDraft } from './storage';
import { FILE_MAX, TOTAL_MAX, mediaType } from './import';

import { mountReports } from './reports';

type Profile={user_id:string;creator_id:string|null;can_upload:boolean;is_admin:boolean};
type Json=Record<string,any>;
const $=(id:string)=>document.getElementById(id)!;
const admin=location.pathname==='/admin';
const deletionPage=location.pathname==='/delete-account';
let creatorPolicyAccepted=false;
let profile:Profile|null=null, drafts:WebDraft[]=[],selected='',step=0,busy=false,currentXhr:XMLHttpRequest|null=null;
let loginConfig:Json={},booting=true;
let importController:AbortController|null=null;
let previewUrl:string|null=null,saveTimer:ReturnType<typeof setTimeout>|undefined,saveQueue=Promise.resolve();
const channel=typeof BroadcastChannel==='undefined'?null:new BroadcastChannel('audioapp-creator');
const statusName:Json={pending:'검수 대기',approved:'공개 중',rejected:'반려',removed:'공개 중단'};
const validationName:Json={pending:'검사 대기',passed:'검사 통과',failed:'검사 실패'};
const errorName:Json={format_unavailable:'현재 출시 버전은 오디오만 지원합니다.',policy_required:'최신 이용약관을 확인하고 다시 동의해 주세요.',policy_unavailable:'운영 약관이 준비되지 않았습니다. 운영자에게 문의해 주세요.',account_deleting:'계정 삭제를 처리 중입니다. 처리가 끝난 뒤 이용해 주세요.',account_mismatch:'현재 로그인한 계정과 같은 Google 계정으로 확인해 주세요.',unauthorized:'로그인이 필요합니다. 초안은 보관되어 있습니다.',forbidden:'이 작업을 할 권한이 없습니다.',
 revision_conflict:'정보나 상태가 변경되었습니다. 새로 불러온 내용을 확인해 주세요.',invalid_origin:'요청 원점을 확인해 주세요.',
 validation_required:'미디어 검사를 통과하지 않아 승인할 수 없습니다. 검사 결과를 확인한 뒤 다시 시도해 주세요.',
 invalid_request:'입력 항목을 확인해 주세요.',review_required:'파일·권리·메타데이터 확인을 마쳐 주세요.',creator_conflict:'크리에이터 ID 또는 현재 권한 상태가 맞지 않습니다.',
 upload_expired:'업로드가 만료되었습니다. 상태를 확인한 뒤 다시 제출해 주세요.',limit_reached:'진행 중인 업로드가 많습니다. 잠시 후 다시 시도해 주세요.',
 upload_daily_count_exceeded:'최근 24시간의 업로드 건수 한도에 도달했습니다. 초안은 보관됩니다. 최대 24시간 뒤 다시 시도해 주세요.',
 upload_daily_bytes_exceeded:'최근 24시간의 업로드 용량 한도를 넘습니다. 초안은 보관됩니다. 파일 크기를 확인하고 최대 24시간 뒤 다시 시도해 주세요.',
 storage_quota_exceeded:'보관·예약 용량 한도를 넘습니다. 취소한 전송의 정리가 끝난 뒤 확인하거나 운영자에게 문의해 주세요. 초안은 보관됩니다.',
 upload_limits_unavailable:'업로드 설정을 확인 중입니다. 초안은 보관됩니다. 잠시 후 다시 시도해 주세요.',
 file_verification_failed:'서버에서 원본 파일 검증에 실패했습니다.',file_size_mismatch:'원본 파일 크기가 제출 명세와 다릅니다.',not_found:'항목을 찾을 수 없습니다.'};
function node<K extends keyof HTMLElementTagNameMap>(tag:K,text='',cls=''):HTMLElementTagNameMap[K]{
 const e=document.createElement(tag);if(text)e.textContent=text;if(cls)e.className=cls;return e;
}
function stopMedia(container:HTMLElement){for(const media of container.querySelectorAll('audio,video')){const m=media as HTMLMediaElement;m.onerror=null;m.pause();m.removeAttribute('src');m.load();}}
function button(text:string,run:()=>unknown,cls='secondary'){const b=node('button',text,cls);b.type='button';b.addEventListener('click',()=>void attempt(run));return b;}
function message(text:string,error=false){$('notice').textContent=text;$('notice').className=error?'error':'';}
async function attempt(fn:()=>unknown){try{await fn();}catch(e){message(e instanceof Error?e.message:'작업에 실패했습니다. 다시 시도해 주세요.',true);}}
async function api(path:string,method='GET',body?:unknown):Promise<any>{
 const r=await fetch(path,{method,credentials:'same-origin',redirect:'error',signal:AbortSignal.timeout(25_000),
  headers:{...(method!=='GET'?{'X-Audio-Web':'1'}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{})},
  body:body===undefined?undefined:JSON.stringify(body)});
 const data=r.status===204?null:await r.json();
 if(!r.ok){if(r.status===401){profile=null;renderAccount();if(!booting&&!deletionPage)await renderLogin(loginConfig);}throw new Error(errorName[data?.error]??(r.status===429?'요청이 많습니다. 잠시 후 다시 시도해 주세요.':'요청을 완료하지 못했습니다. 다시 시도해 주세요.'));}
 return data;
}
function persist(d:WebDraft){const snapshot=structuredClone(d);const next=saveQueue.catch(()=>{}).then(async()=>{snapshot.storageRevision=d.storageRevision??0;d.storageRevision=await saveDraft(snapshot);});saveQueue=next;return next;}
function autosave(d:WebDraft){clearTimeout(saveTimer);saveTimer=undefined;saveTimer=setTimeout(()=>{saveTimer=undefined;void attempt(async()=>{await persist(d);const mark=document.getElementById('save-status');if(mark)mark.textContent='브라우저에 초안 저장됨';});},350);}
function current(){return drafts.find(d=>d.id===selected);}
function factList(values:Array<[string,unknown]>){const dl=node('dl','','facts');for(const [key,value]of values){dl.append(node('dt',key),node('dd',typeof value==='boolean'?(value?'확인':'미확인'):String(value===null||value===undefined||value===''?'—':value)));}return dl;}
function formatBytes(n:number){return(n/1024/1024).toFixed(2)+' MiB';}
function workerJob(data:Json,signal?:AbortSignal):Promise<any>{return new Promise((resolve,reject)=>{
 signal?.throwIfAborted();
 const worker=new Worker('/assets/import-worker.js',{type:'module'});
 const finish=()=>{clearTimeout(timer);signal?.removeEventListener('abort',abort);worker.terminate();};
 const abort=()=>{finish();reject(new DOMException('가져오기 중지','AbortError'));};
 const timer=setTimeout(()=>{finish();reject(new Error('파일 검사 시간이 초과되었습니다. 더 작은 파일이나 ZIP으로 나누어 주세요.'));},60_000);
 signal?.addEventListener('abort',abort,{once:true});
 worker.onmessage=e=>{finish();e.data.error?reject(new Error(e.data.error)):resolve(e.data);};
 worker.onerror=()=>{finish();reject(new Error('파일 검사 중 오류가 발생했습니다.'));};
 try{worker.postMessage(data);}catch(e){finish();reject(e);}
});}
function metadataFor(info:WebDraft['info'],duration:number):Json{
 return {title:info.name.replace(/\.[^.]+$/,''),kind:info.type==='video/mp4'?'short_video':'audio',duration_seconds:duration,ai_generated:true,ai_tool:'',
  short_style:null,content_type:info.type,submission:{version:1,description:'',tags:[],file:{name:info.name,size:info.size,sha256:info.sha256},
   rights:{holder:'',license:'original',attribution:'',source_url:'',distribution_confirmed:false,person_consent_confirmed:false,ai_terms_confirmed:false},location:null}};
}
function readMedia(file:File,signal?:AbortSignal):Promise<{duration:number;width:number;height:number}>{
 return new Promise((resolve,reject)=>{
  signal?.throwIfAborted();
  const abort=()=>finish(new DOMException('가져오기 중지','AbortError'));
  const media=document.createElement('video'),url=URL.createObjectURL(file);
  const timer=setTimeout(()=>finish(new Error('미디어 정보를 읽는 시간이 초과되었습니다.')),20_000);
  function finish(error?:Error){clearTimeout(timer);signal?.removeEventListener('abort',abort);media.onerror=null;media.onloadedmetadata=null;media.removeAttribute('src');media.load();URL.revokeObjectURL(url);if(error)reject(error);}
  media.preload='metadata';media.onloadedmetadata=()=>{const duration=Math.ceil(media.duration),video=media as HTMLVideoElement;
   if(!Number.isFinite(duration)||duration<1||duration>(file.type==='video/mp4'?180:1200)){finish(new Error('오디오는 20분, 쇼츠는 3분 이내여야 합니다.'));return;}
   if(file.type==='video/mp4'&&(video.videoWidth<1||video.videoHeight<1)){finish(new Error('MP4 쇼츠에는 영상 트랙이 필요합니다. 오디오 파일은 M4A로 선택해 주세요.'));return;}
   if(file.type!=='video/mp4'&&(video.videoWidth>0||video.videoHeight>0)){finish(new Error('오디오 파일에 영상 트랙이 포함되어 있습니다. 오디오만 내보낸 파일을 선택해 주세요.'));return;}
   const result={duration,width:video.videoWidth??0,height:video.videoHeight??0};finish();resolve(result);};
  media.onerror=()=>finish(new Error('브라우저가 재생할 수 없는 미디어입니다. MP3·AAC M4A·H.264/AAC MP4를 확인해 주세요.'));
  signal?.addEventListener('abort',abort,{once:true});
  media.src=url;
 });
}
async function importFiles(files:File[]){
 if(busy)return;if(files.length>20)throw new Error('한 번에 선택하는 입력 파일은 20개 이하여야 합니다.');
 const controller=new AbortController(),signal=controller.signal;importController=controller;
 busy=true;renderCreator();const notices:string[]=[];
 try{
  for(const file of files){
   message(file.name+' 검사 중…');const pending:WebDraft[]=[];
   try{
    signal.throwIfAborted();
    const total=drafts.filter(d=>d.file).reduce((n,d)=>n+d.info.size,0);
    const result=await workerJob({file,remainingBytes:TOTAL_MAX-total,remainingFiles:20-drafts.length},signal);
    for(const entry of result.files){
     signal.throwIfAborted();
     if(drafts.length+pending.length>=20||total+pending.reduce((n,d)=>n+d.info.size,0)+entry.info.size>TOTAL_MAX)throw new Error('초안은 최대 20개·총 360MiB입니다.');
     if(drafts.some(d=>d.info.sha256===entry.info.sha256)||pending.some(d=>d.info.sha256===entry.info.sha256))throw new Error('이미 가져온 것과 같은 원본 파일이 있습니다.');
     if(!loginConfig.extended_formats && entry.info.type==='video/mp4')throw new Error('현재 버전은 오디오만 업로드할 수 있습니다.');
     const typedFile=new File([entry.file],entry.info.name,{type:entry.info.type,lastModified:entry.file.lastModified});
     const media=await readMedia(typedFile,signal),d:WebDraft={id:crypto.randomUUID(),file:typedFile,source:entry.source,info:entry.info,
      duration:media.duration,width:media.width,height:media.height,metadata:metadataFor(entry.info,media.duration),previewConfirmed:false};
     pending.push(d);
    }
    // 한 ZIP에 대한 검사 전체가 성공한 뒤 저장한다. 다른 정상 ZIP의 초안은 유지한다.
    signal.throwIfAborted();
    await saveNewDrafts(pending);
    drafts.push(...pending);if(!selected&&pending.length)selected=pending[0].id;
    notices.push(file.name+': '+pending.length+'개 가져옴'+(result.skipped.length?' · 지원하지 않는 항목 '+result.skipped.length+'개 제외':''));
   }catch(e){if(signal.aborted){notices.push('가져오기를 중지했습니다. 이미 저장한 초안은 유지됩니다.');break;}notices.push(file.name+': '+(e instanceof Error?e.message:'가져오기 실패'));}
  }
 }finally{importController=null;busy=false;renderCreator();message(notices.join('\n'));channel?.postMessage('changed');}
}
function field(parent:HTMLElement,label:string,value:unknown,change:(v:string)=>void,options:{area?:boolean;number?:boolean;max?:number;hint?:string;readOnly?:boolean}={}){
 const wrap=node('label','','field');wrap.append(node('span',label));const input=options.area?node('textarea'):node('input');
 input.setAttribute('aria-label',label);
 if(input instanceof HTMLInputElement)input.type=options.number?'number':'text';
 input.value=value===null||value===undefined||Number.isNaN(value)?'':String(value);input.disabled=busy||(!admin&&(!!current()?.ticket || (!!current()?.account && current()?.metadata.kind==='post' && !current()?.editId)))||!!options.readOnly;
 if(options.max&&!options.number)input.maxLength=options.max;
 input.addEventListener('input',()=>{change(input.value);const d=current();if(d&&!admin)autosave(d);});wrap.append(input);
 if(options.hint){const hint=node('small',options.hint,'muted');hint.id='hint-'+crypto.randomUUID();input.setAttribute('aria-describedby',hint.id);wrap.append(hint);}parent.append(wrap);return input;
}
function check(parent:HTMLElement,label:string,value:boolean,change:(v:boolean)=>void,disabled=false,independent=false){
 const wrap=node('label','','check'),input=node('input');input.type='checkbox';input.checked=value;input.disabled=disabled||busy||(!independent&&!admin&&(!!current()?.ticket || (!!current()?.account && current()?.metadata.kind==='post' && !current()?.editId)));
 input.addEventListener('change',()=>{change(input.checked);const d=current();if(d&&!admin&&!independent)autosave(d);});wrap.append(input,node('span',label));parent.append(wrap);return input;
}
function validation(d:WebDraft):string[]{
 const m=d.metadata,s=m.submission,r=s.rights,errors:string[]=[];
 if(!d.previewConfirmed)errors.push('파일을 직접 미리 재생하고 원본 확인에 체크해 주세요.');
 if(!cleanText(m.title,100))errors.push('제목은 1~100자로 입력해 주세요.');
 if(m.ai_generated&&!cleanText(m.ai_tool,50))errors.push('사용한 AI 도구를 1~50자로 입력해 주세요.');
 if(!cleanText(r.holder,100))errors.push('권리자 이름을 입력해 주세요.');
 if(r.license!=='original'&&!r.attribution.trim())errors.push('이용 허락 또는 퍼블릭 도메인의 근거를 입력해 주세요.');
 if(!r.distribution_confirmed)errors.push('배포 권한을 확인해 주세요.');
 if(!r.person_consent_confirmed)errors.push('실존 인물의 초상·음성 사용 동의를 확인해 주세요.');
 if(!r.ai_terms_confirmed)errors.push('AI 도구의 배포 약관을 확인해 주세요.');
 if(s.location&&!s.location.public_location_confirmed)errors.push('개인 주거지가 아닌 공개 장소인지 확인해 주세요.');
 if(m.kind==='short_video'&&!['story','moment'].includes(m.short_style))errors.push('Story / Moment를 선택해 주세요.');
 if(m.kind==='post'&&!s.description.trim())errors.push('게시글 내용을 입력해 주세요.');
 if(!uploadMetadata(m)||!validateSubmission(s,m.kind==='post'))errors.push('파일 명세·설명·태그·권리 출처·장소 좌표와 반경의 입력 범위를 확인해 주세요.');
 return errors;
}
function policyLinks(target:HTMLElement,policy:Json){
 for(const [title,key] of [['이용약관','terms_url'],['개인정보처리방침','privacy_url'],['크리에이터 약관','creator_terms_url']]){
  try{const u=new URL(policy?.[key]);if(u.protocol!=='https:'||u.username||u.password)continue;
   const a=node('a',title);a.href=u.href;a.target='_blank';a.rel='noopener noreferrer';target.append(a,node('span',' · '));
  }catch{/* configuration is not published */}
 }
 const deletion=node('a','계정 삭제');deletion.href='/delete-account';target.append(deletion);
}
function renderAccount(){
 const target=$('account');target.replaceChildren();const links=node('div');policyLinks(links,loginConfig.policy);target.append(links);if(profile){target.append(node('span',profile.is_admin?'운영자':profile.creator_id??'일반 계정'),button('로그아웃',async()=>{if(busy)throw new Error('진행 중인 작업을 마치거나 중지해 주세요.');clearTimeout(saveTimer);saveTimer=undefined;const d=current();if(d)await persist(d);await api('/v1/web/session','DELETE');location.reload();}));}
 else target.append(node('span','로그인 전에도 초안을 만들 수 있어요.'));
}
function renderCreator(){
 if(admin)return;if(selected)sessionStorage.setItem('audioapp-selected',selected);if(previewUrl){URL.revokeObjectURL(previewUrl);previewUrl=null;}
 const workspace=$('workspace');stopMedia(workspace);workspace.replaceChildren();
 const tools=node('div','','toolbar'),input=node('input');
 const policy=loginConfig.policy;
 if(policy?.ready) {
  const consent=node('div','','panel');policyLinks(consent,policy);
  check(consent,'크리에이터 이용약관에 동의합니다.',creatorPolicyAccepted,v=>creatorPolicyAccepted=v,false,true);
  workspace.append(consent);
 } else if(!loginConfig.dev_login) workspace.append(node('p','운영 약관 준비 후 업로드할 수 있습니다.','warning'));input.type='file';input.multiple=true;input.accept='.mp3,.m4a,.mp4,.zip';input.hidden=true;
 input.addEventListener('change',()=>void attempt(()=>importFiles(Array.from(input.files??[]))));tools.append(input);
 if(!loginConfig.extended_formats)input.accept='.mp3,.m4a,.zip';
 const add=button(loginConfig.extended_formats?'오디오·쇼츠·ZIP 추가':'오디오·ZIP 추가',()=>input.click(),'');add.disabled=busy;tools.append(add,node('p','원본당 90MiB · ZIP당 180MiB · 초안 20개 / 총 360MiB','small muted'));
 if(importController){const stop=button('가져오기 중지',()=>{importController?.abort();stop.disabled=true;stop.textContent='중지 중…';});tools.append(stop);}
 const newPost=button('New Post',async()=>{
  if(busy)return;if(drafts.length>=20)throw new Error('초안은 최대 20개입니다.');
  const metadata=metadataFor({name:'Post',size:0,type:'',sha256:''},0);
  metadata.kind='post';metadata.title='';metadata.ai_generated=false;metadata.ai_tool=null;metadata.content_type=null;metadata.submission.file=null;
  const d:WebDraft={id:crypto.randomUUID(),file:null,source:'텍스트 게시글',info:{name:'Post',size:0,type:'',sha256:''},duration:0,width:0,height:0,metadata,previewConfirmed:false};
  await saveNewDrafts([d]);drafts.push(d);selected=d.id;step=0;renderCreator();
 });newPost.disabled=busy;if(loginConfig.extended_formats)tools.append(newPost);
 workspace.append(tools);
 const layout=node('div','','layout'),sidebar=node('aside','','sidebar');sidebar.setAttribute('aria-label','파일별 초안');
 for(const d of drafts){const b=button(d.metadata.title||d.info.name,async()=>{if(busy)return;clearTimeout(saveTimer);saveTimer=undefined;const old=current();if(old)await persist(old);selected=d.id;step=0;renderCreator();},'secondary'+(d.id===selected?' selected':''));sidebar.append(b);}
 layout.append(sidebar);const editor=node('section','','panel');layout.append(editor);workspace.append(layout);
 const d=current();
 if(!d){editor.append(node('h2','파일을 추가해 시작하세요'),node('p',loginConfig.extended_formats?'여러 파일과 ZIP을 함께 선택할 수 있습니다. ZIP 안의 오디오·영상은 각각 확인하고 제출합니다.':'완성된 오디오와 ZIP을 선택해 파일별로 확인하고 제출합니다.','muted'),
  node('p','RAR·7z·암호화·분할·ZIP64·중첩 압축의 자동 해제는 지원하지 않습니다. 경로 조작, 중복 경로, CRC 오류와 과도한 압축률은 거부합니다.','small muted'));}
 else if(d.submitted){editor.append(node('h2','검수를 요청했습니다'),factList([['제목',d.metadata.title],['콘텐츠 ID',d.submitted]]),node('p','승인 후 검색·재생 목록에 공개됩니다. 아래 제출 목록에서 결과를 확인하세요.'),button('기기 초안 삭제',()=>removeDraft(d),'danger'));}
 else{
  const steps=node('ol','','steps');['파일 확인','작품 정보','권리 확인','장소 연결','최종 검토'].forEach((name,i)=>{const li=node('li'),b=button((i+1)+'. '+name,async()=>{if(busy)return;clearTimeout(saveTimer);saveTimer=undefined;await persist(d);step=i;renderCreator();});if(i===step)b.setAttribute('aria-current','step');li.append(b);steps.append(li);});editor.append(steps);
  const m=d.metadata,s=m.submission,r=s.rights,locked=busy||!!d.ticket||!!(d.account&&m.kind==='post'&&!d.editId);
  if(d.editId)editor.append(node('p','제출한 작품 정보 수정 · 버전 '+d.revision+' · 원본 파일과 길이는 유지됩니다.','warning'));
  if(step===0){
   if(m.kind!=='post'||s.file) editor.append(node('h2','정확한 원본인지 확인하세요'),factList([['파일 이름',d.info.name],['가져온 위치',d.source],['실제 크기',formatBytes(d.info.size)],
    ['재생 길이',d.duration+'초'],['해상도',d.width?d.width+' × '+d.height:'오디오'],['SHA-256',d.info.sha256]]));
   if(m.kind==='post') {
    if(d.metadata.submission.file){const image=node('img');image.alt=m.title;image.src=d.file?(previewUrl=URL.createObjectURL(d.file)):'/v1/me/contents/'+d.editId+'/media';editor.append(image);}
    editor.append(node('p',s.description||'다음 단계에서 글을 작성해 주세요.'));
    check(editor,'공유할 사진과 글을 확인했습니다.',d.previewConfirmed,v=>d.previewConfirmed=v);
   } else {
   const preview=node(d.info.type==='video/mp4'?'video':'audio');preview.controls=true;preview.preload='metadata';
   if(d.file){previewUrl=URL.createObjectURL(d.file);preview.src=previewUrl;}else if(d.editId)preview.src='/v1/me/contents/'+d.editId+'/media';
   editor.append(preview);const confirm=check(editor,'직접 재생해 올바른 파일·길이·화면과 소리임을 확인했습니다.',d.previewConfirmed,v=>{d.previewConfirmed=v;},!d.previewConfirmed);
   preview.addEventListener('play',()=>{if(!locked)confirm.disabled=false;});
   preview.onerror=()=>editor.append(node('p','미리 재생할 수 없습니다. 원본 형식이나 로그인 상태를 확인해 주세요.','preview-error'));
   }
  }else if(step===1){
   editor.append(node('h2','다른 사용자가 발견할 작품 정보'));
   field(editor,'작품 제목',m.title,v=>m.title=v,{max:100});field(editor,'작품 설명',s.description,v=>s.description=v,{area:true,max:2000});
   field(editor,'태그 (쉼표로 구분)',s.tags.join(', '),v=>s.tags=v.split(',').map(t=>t.trim()).filter(Boolean),{hint:'중복 없이 최대 10개, 태그마다 30자 이하'});
   if(m.kind==='short_video'){
    const wrap=node('label','','field'),select=node('select');wrap.append(node('span','쇼츠 성격'));
    for(const [value,label]of [['','선택'],['story','Story · 연출한 이야기'],['moment','Moment · 편집한 추억']]){const option=node('option',label);option.value=value;select.append(option);}
    select.value=m.short_style??'';select.disabled=locked;select.onchange=()=>{m.short_style=select.value||null;autosave(d);};wrap.append(select);editor.append(wrap);
   }
   check(editor,'AI로 생성한 콘텐츠입니다.',m.ai_generated,v=>{m.ai_generated=v;if(!v)m.ai_tool='';});
   field(editor,'사용한 AI 도구',m.ai_tool??'',v=>m.ai_tool=v,{max:50});
  }else if(step===2){
   editor.append(node('h2','배포 권리와 출처를 검토하세요'),node('p','아래 내용은 운영자의 검수에 전달됩니다. 체크만으로 저작권이 자동 인증되지는 않습니다.','muted'));
   field(editor,'권리자',r.holder,v=>r.holder=v,{max:100});
   const label=node('label','','field');label.append(node('span','이용 근거'));const select=node('select');[['original','직접 권리 보유'],['licensed','이용 허락을 받음'],['public_domain','퍼블릭 도메인']].forEach(([value,text])=>{const o=node('option',text);o.value=value;select.append(o);});select.value=r.license;select.disabled=locked;select.onchange=()=>{r.license=select.value;autosave(d);};label.append(select);editor.append(label);
   field(editor,'이용 근거·허락 범위·표시할 출처',r.attribution,v=>r.attribution=v,{area:true,max:1000,hint:'이용 허락·퍼블릭 도메인을 선택했다면 근거를 반드시 적어 주세요.'});
   field(editor,'권리 출처 URL (선택, HTTPS)',r.source_url,v=>r.source_url=v,{max:2048});
   check(editor,'원본·음악·영상·소재를 포함해 이 플랫폼에서 배포할 권한이 있습니다.',r.distribution_confirmed,v=>r.distribution_confirmed=v);
   check(editor,'실존 인물의 초상·음성이 있다면 필요한 동의를 받았습니다.',r.person_consent_confirmed,v=>r.person_consent_confirmed=v);
   check(editor,'사용한 AI 도구의 약관에서 이 결과물의 배포를 허용하는지 확인했습니다.',r.ai_terms_confirmed,v=>r.ai_terms_confirmed=v);
  }else if(step===3){
   editor.append(node('h2','공개 장소에 연결하기'),node('p','선택 기능입니다. 업로드와 감상에는 장소 연결이 필요하지 않습니다. 현재 GPS나 기업 차량 위치를 수집하지 않습니다.','muted'));
   check(editor,'이 작품을 장소·지역 캐릭터·이야기와 연결합니다.',!!s.location,v=>{s.location=v?{name:'',latitude:NaN,longitude:NaN,radius_meters:200,character:'',story:'',public_location_confirmed:false}:null;autosave(d);renderCreator();});
   if(s.location){const p=s.location;field(editor,'공개 장소 이름',p.name,v=>p.name=v,{max:100});const row=node('div','','row');editor.append(row);
    field(row,'위도 (-90 ~ 90)',p.latitude,v=>p.latitude=parseFloat(v),{number:true});field(row,'경도 (-180 ~ 180)',p.longitude,v=>p.longitude=parseFloat(v),{number:true});
    field(editor,'장소 반경 (50 ~ 5000m)',p.radius_meters,v=>p.radius_meters=Number(v),{number:true});
    field(editor,'지역 캐릭터 (선택)',p.character,v=>p.character=v,{max:100});field(editor,'장소 이야기 (선택)',p.story,v=>p.story=v,{area:true,max:2000});
    check(editor,'개인 주거지나 민감한 위치가 아닌 공개 장소임을 확인했습니다.',p.public_location_confirmed,v=>p.public_location_confirmed=v);}
  }else{
   editor.append(node('h2','제출 내용을 마지막으로 확인하세요'),factList([['작품',m.title],['종류',m.kind==='post'?'Post':m.kind==='audio'?'오디오':m.short_style==='moment'?'Moment':m.short_style==='story'?'Story':'쇼츠'],['원본',d.info.name],
    ['재생 길이',m.duration_seconds+'초'],['권리자',r.holder],['장소',s.location?.name??'연결하지 않음']]));
   const errors=validation(d);if(errors.length){const list=node('ul','','errors');errors.forEach(e=>list.append(node('li',e)));editor.append(list);}
   const detail=node('details');detail.append(node('summary','파일·권리·장소 전체 명세'),node('pre',JSON.stringify(m,null,2)));editor.append(detail);
   const send=button(d.editId?'수정하고 재검수 요청':'검수 요청',()=>submit(d),'');send.disabled=locked||!profile?.can_upload||!!errors.length;editor.append(send);
   if(!profile)editor.append(node('p','로그인 후 제출할 수 있습니다. 초안은 이 브라우저에 저장됩니다.','muted'));
   else if(!profile.can_upload)editor.append(node('p','크리에이터 승인 후 제출할 수 있습니다. 운영자에게 아래 내 계정 ID를 알려 주세요.','muted'));
  }
  if(d.ticket){const transfer=node('div','','transfer');transfer.append(node('p','진행 중이거나 결과를 확인할 업로드가 있습니다. 중복 제출을 막기 위해 정보 수정을 잠갔습니다.'),button('서버 상태 확인·전송 재시도',()=>submit(d)),button('현재 전송 폐기 후 수정',()=>discardTicket(d),'danger'));editor.append(transfer);}
  const actions=node('div','','actions spaced');
  if(step>0)actions.append(button('이전',async()=>{if(!busy){clearTimeout(saveTimer);saveTimer=undefined;await persist(d);step--;renderCreator();}}));
  if(step<4)actions.append(button('다음',async()=>{if(!busy){clearTimeout(saveTimer);saveTimer=undefined;await persist(d);step++;renderCreator();}}));
  const del=button('기기 초안 삭제',()=>removeDraft(d),'danger');del.disabled=locked;actions.append(del);editor.append(actions,node('p','변경 내용은 이 브라우저에 저장됩니다.','small muted'));const saved=node('p','','small muted');saved.id='save-status';editor.append(saved);
 }
 if(busy){const progress=node('div','','transfer');progress.append(node('p','파일 검사 또는 전송 중…'));const bar=node('progress');bar.id='progress';bar.max=100;progress.append(bar);const stop=button('전송 중지',()=>currentXhr?.abort(),'danger');stop.disabled=!currentXhr;progress.append(stop);workspace.prepend(progress);}
 if(profile){workspace.append(factList([['내 계정 ID',profile.user_id],['크리에이터 ID',profile.creator_id??'승인 전']]));}
 workspace.append(node('p','초안에는 원본 파일과 권리 진술이 포함됩니다. 공유 PC에서는 사용 후 기기 초안을 삭제해 주세요.','small muted'));
 const remote=node('section','','panel spaced');remote.id='submissions';remote.append(node('h2','제출한 작품'),button('상태 새로고침',()=>loadSubmissions(remote)));workspace.append(remote);
 if(profile)void attempt(()=>loadSubmissions(remote));else remote.append(node('p','로그인하면 검수 결과와 반려 사유를 확인할 수 있습니다.','muted'));
}
async function removeDraft(d:WebDraft){if(busy||d.ticket)throw new Error('먼저 전송 상태를 확인하거나 폐기해 주세요.');clearTimeout(saveTimer);saveTimer=undefined;await saveQueue.catch(()=>{});await deleteDraft(d.id,d.storageRevision??0);drafts=drafts.filter(x=>x.id!==d.id);if(selected===d.id)selected=drafts[0]?.id??'';renderCreator();channel?.postMessage('changed');}
function putFile(url:string,file:File,type:string):Promise<void>{
 return new Promise((resolve,reject)=>{
  const xhr=new XMLHttpRequest();currentXhr=xhr;xhr.open('PUT',url);xhr.timeout=900_000;xhr.withCredentials=true;
  xhr.setRequestHeader('Content-Type',type);xhr.setRequestHeader('X-Audio-Web','1');
  xhr.upload.onprogress=e=>{const bar=document.getElementById('progress') as HTMLProgressElement|null;if(bar&&e.lengthComputable)bar.value=e.loaded/e.total*100;};
  xhr.onload=()=>{currentXhr=null;xhr.status===204?resolve():reject(new Error('파일 전송을 완료하지 못했습니다. 서버 상태를 확인해 주세요.'));};
  xhr.onerror=xhr.ontimeout=()=>{currentXhr=null;reject(new Error('연결이 끊겼습니다. 서버 상태를 확인한 뒤 다시 시도해 주세요.'));};
  xhr.onabort=()=>{currentXhr=null;reject(new Error('전송을 중지했습니다. 다음 시도에서 서버 상태를 먼저 확인합니다.'));};xhr.send(file);
 });
}
async function submitted(d:WebDraft,id:string){d.submitted=id;d.ticket=undefined;await persist(d);message('검수를 요청했습니다. 승인 후 공개됩니다.');channel?.postMessage('changed');}
async function submit(d:WebDraft){
 if(!loginConfig.extended_formats && d.metadata.kind!=='audio')throw new Error('기존 초안은 보관되지만 현재 버전에서는 오디오만 제출할 수 있습니다.');
 if(busy)return;if(!profile?.can_upload)throw new Error('승인된 크리에이터로 로그인해 주세요.');
 if(loginConfig.policy?.ready){
  if(!creatorPolicyAccepted)throw new Error('크리에이터 이용약관을 확인하고 동의해 주세요.');
  await api('/v1/me/policies/creator','POST',{version:loginConfig.policy.version,accepted:true});
 }else if(!loginConfig.dev_login)throw new Error('운영 약관이 준비되지 않았습니다.');
 if(d.account&&(d.ticket||d.editId||d.metadata.kind==='post')&&d.account!==profile.user_id)throw new Error('이 전송·수정을 시작한 계정으로 로그인해 주세요.');
 if(!navigator.locks)throw new Error('중복 전송 방지를 지원하는 최신 Chrome·Edge·Firefox·Safari에서 사용해 주세요.');
 await navigator.locks.request('audio-upload:'+d.id,{ifAvailable:true},async lock=>{
  if(!lock)throw new Error('다른 탭에서 이 파일을 전송하고 있습니다.');
  // 잠금을 얻은 뒤 저장소를 다시 읽어 다른 탭에서 완료한 제출을 되풀이하지 않는다.
  const stored=(await allDrafts()).find(x=>x.id===d.id);if(stored?.submitted){Object.assign(d,stored);renderCreator();return;}
  if(stored?.ticket&&!d.ticket){Object.assign(d,stored);}
  const errors=validation(d);if(errors.length)throw new Error(errors.join('\n'));
  clearTimeout(saveTimer);saveTimer=undefined;await persist(d);busy=true;renderCreator();
  try{
   if(d.editId){await api('/v1/me/contents/'+d.editId,'PATCH',{...d.metadata,revision:d.revision});await submitted(d,d.editId);return;}
   if(d.metadata.kind==='post'&&!d.metadata.submission.file){d.account=profile!.user_id;await persist(d);
    const result=await api('/v1/me/posts','POST',{...d.metadata,client_id:d.id});await submitted(d,result.content_id);return;}
   if(!d.file)throw new Error('원본 파일을 다시 선택해 주세요.');
   if((await workerJob({file:d.file,hashOnly:true})).hash!==d.info.sha256)throw new Error('원본 해시가 달라졌습니다. 파일을 다시 가져와 확인해 주세요.');
   if(d.ticket){const state=await api('/v1/uploads/'+d.ticket);
    if(state.state==='completed'){await submitted(d,state.content_id);return;}
    if(state.state==='uploaded'){await api('/v1/uploads/'+d.ticket+'/complete','POST');await submitted(d,state.content_id);return;}
    // URL 토큰은 저장하지 않는다. 불완전한 이전 업로드를 폐기하고 새 전송을 만든다.
    await api('/v1/uploads/'+d.ticket,'DELETE');d.ticket=undefined;await persist(d);
   }
   const ticket=await api('/v1/uploads','POST',d.metadata),url=new URL(ticket.upload_url,location.origin);
   if(!isValidId(ticket.upload_id)||url.origin!==location.origin||url.pathname!=='/v1/uploads/'+ticket.upload_id+'/file'||
      url.searchParams.size!==1||!/^[a-f0-9]{64}$/.test(url.searchParams.get('token')??'')||ticket.max_bytes<d.info.size)throw new Error('서버 전송 주소가 올바르지 않습니다.');
   d.ticket=ticket.upload_id;d.account=profile!.user_id;await persist(d);channel?.postMessage('changed');renderCreator();
   const sending=putFile(url.href,d.file,d.info.type);renderCreator();await sending;
   await api('/v1/uploads/'+d.ticket+'/complete','POST');
   const state=await api('/v1/uploads/'+d.ticket);await submitted(d,state.content_id);
  }finally{busy=false;currentXhr=null;renderCreator();}
 });
}
async function discardTicket(d:WebDraft){
 if(busy||!d.ticket)return;const state=await api('/v1/uploads/'+d.ticket);
 if(state.state==='completed'){await submitted(d,state.content_id);renderCreator();return;}
 await api('/v1/uploads/'+d.ticket,'DELETE');d.ticket=undefined;await persist(d);renderCreator();message('이전 전송을 폐기했습니다. 원본과 정보를 다시 확인할 수 있습니다.');
}
async function loadSubmissions(target:HTMLElement,cursor:string|null=null){
 const page=await api('/v1/me/contents?limit=20'+(cursor?'&cursor='+encodeURIComponent(cursor):''));
 if(!target.isConnected)return;if(!cursor)target.querySelectorAll('.submission,.empty,.more').forEach(e=>e.remove());
 if(!page.items.length&&!cursor)target.append(node('p','제출한 작품이 없습니다.','empty'));
 for(const row of page.items){const box=node('article','','submission');box.append(node('span',statusName[row.status]??row.status,'badge'),node('strong',row.title),
  node('p',(row.kind==='post'?'게시글':row.kind==='audio'?'오디오':row.short_style==='moment'?'Moment':row.short_style==='story'?'Story':'쇼츠')+' · 버전 '+row.revision+' · '+row.id,'small muted'));
  if(row.reject_reason)box.append(node('p','검토 사유: '+row.reject_reason));
  if(profile?.can_upload&&['pending','rejected'].includes(row.status)&&row.submission_json)box.append(button('정보 수정·재검수 요청',async()=>{
   const s=JSON.parse(row.submission_json),old=drafts.find(d=>d.editId===row.id&&!d.submitted);
   if(old){selected=old.id;step=0;renderCreator();return;}
   if(drafts.length>=20)throw new Error('초안은 최대 20개입니다. 먼저 필요 없는 기기 초안을 삭제해 주세요.');
   const info={name:'Post',size:0,sha256:'',...s.file,type:row.kind==='post'?(s.file?'image/jpeg':''):row.kind==='short_video'?'video/mp4':mediaType(s.file.name)??'audio/mpeg'};
   const d:WebDraft={id:crypto.randomUUID(),file:null,source:'서버에 제출한 원본',info,duration:row.duration_seconds,width:0,height:0,
    metadata:{title:row.title,kind:row.kind,duration_seconds:row.duration_seconds,ai_generated:row.ai_generated,ai_tool:row.ai_tool??'',content_type:row.kind==='post'&&!s.file?null:info.type,short_style:row.short_style,submission:s},
    previewConfirmed:false,editId:row.id,revision:row.revision,account:profile!.user_id};await persist(d);drafts.push(d);selected=d.id;step=0;renderCreator();
  }));target.append(box);
 }
 target.querySelector('.more')?.remove();
 if(page.next_cursor)target.append(button('제출 내역 더 보기',()=>loadSubmissions(target,page.next_cursor),'secondary more'));
}
async function renderLogin(config:Json){
 const target=$('login');target.replaceChildren();
 if(profile)return;
 const box=node('div','','panel login-panel');box.append(node('h2','Google 계정으로 이어서 작업하기'),node('p','앱과 같은 계정을 사용합니다. 로그인 토큰은 JavaScript 저장소에 보관하지 않습니다.','muted'));target.append(box);
 let accepted=false;policyLinks(box,config.policy);
 if(config.policy?.ready)check(box,'서비스 이용약관에 동의합니다.',false,v=>accepted=v,false,true);
 else if(!config.dev_login)box.append(node('p','운영 약관이 아직 준비되지 않아 가입을 진행할 수 없습니다.','warning'));
 async function signIn(id_token:string){
  if(!config.dev_login&&(!config.policy?.ready||!accepted))throw new Error('서비스 이용약관에 동의해 주세요.');
  profile=await api('/v1/web/session','POST',{id_token,policy_version:config.policy?.version});
  renderAccount();target.replaceChildren();if(admin)await renderAdmin();else renderCreator();
 }
 if(config.dev_login)box.append(button('로컬 개발 로그인',()=>signIn('dev-id-token')));
 if(config.google_client_id&&!config.dev_login){
  const holder=node('div');box.append(holder);const script=node('script');script.src='https://accounts.google.com/gsi/client';script.async=true;
  script.onload=()=>{const google=(window as any).google;google.accounts.id.initialize({client_id:config.google_client_id,callback:(r:Json)=>void attempt(()=>signIn(r.credential)),auto_select:false});
   google.accounts.id.renderButton(holder,{type:'standard',theme:'outline',size:'large',text:'signin_with'});};
  script.onerror=()=>box.append(node('p','Google 로그인을 불러오지 못했습니다. 연결을 확인하고 새로고침해 주세요.','errors'));document.head.append(script);
 }else if(!config.dev_login)box.append(node('p','운영자가 Google 웹 클라이언트 ID와 허용된 JavaScript 원점을 설정하면 로그인할 수 있습니다.','muted'));
}
async function renderAdmin(){
 const workspace=$('workspace');stopMedia(workspace);workspace.replaceChildren();
 if(!profile){workspace.append(node('p','관리자 계정으로 로그인해 주세요.','panel'));return;}
 if(!profile.is_admin){workspace.append(node('p','운영자 권한이 필요합니다. 이 계정에는 검수 권한이 없습니다.','panel'));return;}
 const tools=node('div','','toolbar');tools.append(node('p','원본과 제출 정보를 검토한 버전에 대해서만 승인합니다.'));
 workspace.append(tools);const account=node('details','','panel');account.append(node('summary','크리에이터 승인·업로드 권한 관리'));
 const lookup=node('div');account.append(lookup);let userId='';field(lookup,'사용자 계정 ID','',v=>userId=v,{max:64});
 const userResult=node('div');lookup.append(button('사용자 조회',async()=>{const u=await api('/v1/admin/user?id='+encodeURIComponent(userId));userResult.replaceChildren();
  userResult.append(factList([['계정',u.id],['역할',u.role],['크리에이터 ID',u.creator_id]]));
  let creatorId=u.creator_id??'';field(userResult,'승인할 크리에이터 ID',creatorId,v=>creatorId=v,{max:64,readOnly:!!u.creator_id});
  userResult.append(node('p','크리에이터 ID는 최초 지정 후 변경할 수 없습니다. 권한 회수는 새 업로드를 막으며 기존 작품의 공개 상태는 별도로 검토합니다.','small muted'));
  const approve=button('크리에이터 승인',async()=>{await api('/v1/admin/creators/'+u.id,'POST',{action:'approve',creator_id:creatorId});message('크리에이터 업로드 권한을 승인했습니다.');await renderAdmin();},'');
  const revoke=button('업로드 권한 회수',async()=>{await api('/v1/admin/creators/'+u.id,'POST',{action:'revoke'});message('업로드 권한을 회수했습니다.');await renderAdmin();},'danger');
  approve.disabled=u.role==='admin';revoke.disabled=u.role!=='creator';userResult.append(approve,revoke);
 }),userResult);workspace.append(account);
 const reportBox=node('details','','panel');reportBox.append(node('summary','신고 처리'));workspace.append(reportBox);
 mountReports(reportBox,api,(id,target)=>showReview(id,target,renderAdmin));
 const nav=node('div','','tabs');workspace.append(nav);const list=node('section','','panel'),detail=node('section','','panel');workspace.append(list,detail);
 let active='pending',listVersion=0;
 async function load(status:string,cursor:string|null=null){
  const version=++listVersion;active=status;
  if(!cursor){list.replaceChildren(node('p','검수 목록을 불러오는 중…'));detail.dataset.generation=crypto.randomUUID();stopMedia(detail);detail.replaceChildren();}
  const page=await api('/v1/admin/contents?status='+status+(cursor?'&cursor='+encodeURIComponent(cursor):''));
  if(version!==listVersion||!list.isConnected)return;
  if(!cursor){list.replaceChildren(node('h2',statusName[status]+' 작품'));detail.replaceChildren();}
  list.querySelector('.more')?.remove();
  if(!page.items.length&&!cursor)list.append(node('p','해당 상태의 작품이 없습니다.','empty'));
  for(const row of page.items){const box=node('article','','submission');box.append(node('strong',row.title),node('p',row.creator_id+' · 버전 '+row.revision+' · 신고 '+row.reports+'건','small muted'),
   button('원본·제출 정보 검토',()=>showReview(row.id,detail,()=>load(active))));list.append(box);}
  if(page.next_cursor)list.append(button('더 보기',()=>load(status,page.next_cursor),'secondary more'));
 }
 for(const status of ['pending','approved','rejected','removed'])nav.append(button(statusName[status]+' 목록',()=>load(status)));
 await load(active);
}
async function showReview(id:string,target:HTMLElement,reload:()=>Promise<void>){
 const generation=crypto.randomUUID();target.dataset.generation=generation;
 stopMedia(target);target.replaceChildren(node('p','최신 검토 정보를 불러오는 중…'));
 const data=await api('/v1/admin/contents/'+id);
 if(!target.isConnected||target.dataset.generation!==generation)return;
 target.replaceChildren();target.scrollIntoView({behavior:'smooth',block:'start'});
 const validated=data.validation_status==='passed';
 target.append(node('h2',data.title),factList([['콘텐츠 ID',data.id],['크리에이터',data.creator_id],['상태',statusName[data.status]],
  ['미디어 검사',(validationName[data.validation_status]??data.validation_status)+(data.validation_failure_code?' ('+data.validation_failure_code+')':'')],['검토 버전',data.revision],
  ['제출된 재생 길이',data.duration_seconds+'초'],['종류',data.kind==='post'?'게시글':data.kind==='audio'?'오디오':data.short_style==='moment'?'Moment':data.short_style==='story'?'Story':'쇼츠'],['AI 표시',data.ai_generated?'AI 생성':'표시 없음'],['AI 도구',data.ai_tool]]));
 const submission=data.submission_json?JSON.parse(data.submission_json):null;
 if(submission){target.append(node('h3','정확한 원본 파일'),factList([['파일 이름',submission.file?.name??'텍스트 게시글'],['크기',submission.file?formatBytes(submission.file.size):'—'],['SHA-256',submission.file?.sha256]]),
  node('h3','권리 진술'),factList([['권리자',submission.rights.holder],['이용 근거',({original:'직접 권리 보유',licensed:'이용 허락',public_domain:'퍼블릭 도메인'} as Json)[submission.rights.license]??submission.rights.license],['허락 범위·출처',submission.rights.attribution],
   ['출처 URL',submission.rights.source_url],['배포 권한',submission.rights.distribution_confirmed],['초상·음성 동의',submission.rights.person_consent_confirmed],['AI 약관',submission.rights.ai_terms_confirmed]]),
  node('h3','공개할 설명·태그·장소'),factList([['설명',submission.description],['태그',submission.tags.join(' · ')],
   ['장소',submission.location?.name],['위도·경도',submission.location?submission.location.latitude+', '+submission.location.longitude:null],
   ['장소 반경',submission.location?submission.location.radius_meters+'m':null],['캐릭터',submission.location?.character],['장소 이야기',submission.location?.story],
   ['공개 장소 확인',submission.location?.public_location_confirmed]]));}
 let played=data.kind==='post'&&!data.media_url,fileReviewed=false,rightsReviewed=false,metadataReviewed=false,approveButton:HTMLButtonElement|null=null;
 const updateApproval=()=>{if(approveButton)approveButton.disabled=!validated||(!data.media_url&&data.kind!=='post')||!played||!fileReviewed||!rightsReviewed||!metadataReviewed;};
 if(!validated)target.append(node('p',data.validation_status==='failed'?'미디어 검사에 실패해 승인할 수 없습니다. 원본 미리 재생도 제공하지 않습니다.':'미디어 검사가 끝나지 않아 아직 승인할 수 없습니다. 검사를 통과하면 원본을 재생해 검수할 수 있습니다.','warning'));
 const checks=node('div');target.append(checks);
 const fileCheck=check(checks,data.kind==='post'?'게시글의 사진과 내용을 확인했습니다.':'원본을 재생해 파일·길이·코덱과 화면·소리를 확인했습니다.',false,v=>{fileReviewed=v;updateApproval();},!played);
 if(!validated){/* 관리자 미리 재생은 검사 통과 뒤에만 제공된다. */}
 else if(data.kind==='post'&&data.media_url){const image=node('img');image.alt=data.title;
  image.onload=()=>{played=true;fileCheck.disabled=false;updateApproval();};image.onerror=()=>target.prepend(node('p','사진을 불러오지 못했습니다.','errors'));
  image.src=data.media_url;target.insertBefore(image,checks);
 }else if(data.media_url){const media=node(data.kind==='short_video'?'video':'audio');media.controls=true;media.preload='metadata';media.src=data.media_url;
  media.addEventListener('play',()=>{played=true;fileCheck.disabled=false;updateApproval();});media.onerror=()=>target.prepend(node('p','원본을 재생하지 못했습니다. 파일 또는 세션을 확인해 주세요.','errors'));
  target.insertBefore(media,checks);const download=node('a','검수 원본 내려받기');download.href=data.media_url;download.download=submission?.file?.name??data.id;target.insertBefore(download,checks);
 }else if(data.kind!=='post') target.insertBefore(node('p','R2 원본이 없는 외부 샘플은 이 화면에서 승인할 수 없습니다. 기존 운영 CLI에서 원본을 확인해 주세요.','warning'),checks);
 check(checks,'권리 진술·이용 근거와 필요 동의를 확인했습니다.',false,v=>{rightsReviewed=v;updateApproval();});
 check(checks,'제목·AI 표시·설명·태그와 선택적 공개 장소를 확인했습니다.',false,v=>{metadataReviewed=v;updateApproval();});
 let reason='';field(target,'반려·공개 중단 사유 (필수, 최대 200자)','',v=>reason=v,{max:200,area:true});
 const actions=node('div','','actions');target.append(actions);let deciding=false;
 async function review(action:string){
  if(deciding)return;
  if(action==='approve'&&!validated)throw new Error(errorName.validation_required);
  if(action==='approve'&&(!played||!fileReviewed||!rightsReviewed||!metadataReviewed))throw new Error('원본을 직접 재생하고 세 가지 확인에 체크해 주세요.');
  if(action!=='approve'&&!cleanText(reason,200))throw new Error('반려·공개 중단 사유를 1~200자로 입력해 주세요.');
  deciding=true;const controls=Array.from(actions.querySelectorAll('button')).map(b=>({b,disabled:b.disabled}));controls.forEach(x=>x.b.disabled=true);
  try{
   await api('/v1/admin/contents/'+id+'/review','POST',{action,revision:data.revision,reason,file_reviewed:fileReviewed,rights_reviewed:rightsReviewed,metadata_reviewed:metadataReviewed});
   message(action==='approve'?'승인했습니다. 이제 공개 목록에서 재생할 수 있습니다.':action==='reject'?'반려 사유를 전달했습니다.':'콘텐츠 공개를 중단했습니다.');await reload();
  }finally{deciding=false;controls.forEach(x=>x.b.disabled=x.disabled);}
 }
 if(data.status==='pending'){const approve=button('확인한 버전 승인',()=>review('approve'),'');approveButton=approve;updateApproval();actions.append(approve,button('반려',()=>review('reject'),'danger'));}
 if(['pending','approved'].includes(data.status))actions.append(button('공개 중단',()=>review('remove'),'danger'));
 actions.append(button('현재 버전 다시 불러오기',()=>showReview(id,target,reload)));
 if(data.reports.length){target.append(node('h3','신고 사유별 건수'),node('pre',JSON.stringify(data.reports,null,2)));}
 target.append(node('h3','최근 심사 이력 (최대 50건)'));const table=node('table'),head=node('tr');['시각','버전','변경','검토자','사유'].forEach(t=>head.append(node('th',t)));table.append(head);
 for(const h of data.history){const tr=node('tr');[new Date(h.created_at*1000).toLocaleString('ko-KR'),h.revision,(statusName[h.from_status]??h.from_status)+' → '+(statusName[h.to_status]??h.to_status),h.actor==='cli'?'운영 CLI':h.actor,h.reason??''].forEach(t=>tr.append(node('td',String(t))));table.append(tr);}
 const wrap=node('div','','audit-wrap');wrap.append(table);target.append(wrap);
}
channel?.addEventListener('message',()=>{if(deletionPage)return;if(saveTimer){message('다른 탭의 초안 변경이 있습니다. 입력을 저장하기 전에 최신 상태를 확인해 주세요.',true);return;}if(!busy)void attempt(async()=>{drafts=await allDrafts();if(!drafts.some(d=>d.id===selected))selected=drafts[0]?.id??'';renderCreator();});});
window.addEventListener('beforeunload',e=>{if(busy||saveTimer){e.preventDefault();e.returnValue='';}});
async function renderDeletion(config:Json){
 $('page-title').textContent='AudioApp 계정 삭제';
 const intro=document.querySelector('.intro p:last-child');if(intro)intro.textContent='앱을 설치하지 않고 계정 삭제를 요청할 수 있습니다.';
 $('account').replaceChildren();$('workspace').replaceChildren();$('login').replaceChildren();
 const box=node('section','','panel');$('workspace').append(box);
 box.append(node('h2','계정과 업로드 콘텐츠 삭제'),
  node('p','Google 계정 자체는 삭제되지 않습니다. 삭제 요청 즉시 AudioApp 계정 접근과 콘텐츠 공개를 중단합니다. 전송 중인 파일의 정리가 가능한 시점부터 원본 파일과 계정·구독·차단·신고 데이터 삭제를 순차 처리합니다. 파일 정리 실패 시 자동 재시도하며, 처리 중에는 같은 계정으로 가입할 수 없습니다.'),
  node('p','기기에만 남은 초안·청취 기록·저장 목록은 해당 기기에서 별도로 삭제해 주세요. 다른 콘텐츠의 운영 이력에 남은 계정 식별자는 익명화합니다.'));
 policyLinks(box,config.policy);
 if(config.policy?.support_email)box.append(node('p','처리 문의: '+config.policy.support_email));
 let confirmed=false,inFlight=false;
 check(box,'삭제 범위를 확인했으며 계정 삭제를 요청합니다.',false,v=>confirmed=v,false,true);
 async function remove(id_token:string){
  if(!confirmed)throw new Error('계정 삭제 확인에 체크해 주세요.');
  if(inFlight)return;inFlight=true;
  try{
   const result=await api('/v1/web/account-deletion','POST',{id_token,confirm:'DELETE'});
   await api('/v1/web/session','DELETE').catch(()=>{});
   profile=null;
   message(result.status==='deleted'?'계정 삭제가 완료되었거나 해당 서비스 계정이 없습니다.':'삭제 요청을 접수했습니다. 계정 접근과 콘텐츠 공개는 중지되었으며 파일 정리를 진행합니다.');
  }finally{inFlight=false;}
 }
 if(config.dev_login)box.append(button('개발 계정 삭제 요청',()=>remove('dev-id-token'),'danger'));
 else if(config.google_client_id){
  box.append(node('p','삭제할 계정으로 Google 계정 확인을 진행해 주세요. 새 서비스 계정을 만들지 않습니다.'));
  const holder=node('div');box.append(holder);const script=node('script');script.src='https://accounts.google.com/gsi/client';script.async=true;
  script.onload=()=>{const google=(window as any).google;google.accounts.id.initialize({client_id:config.google_client_id,auto_select:false,
   callback:(r:Json)=>void attempt(()=>remove(r.credential))});google.accounts.id.renderButton(holder,{type:'standard',theme:'outline',size:'large'});};
  script.onerror=()=>message('Google 계정 확인을 불러오지 못했습니다. 새로고침해 주세요.',true);document.head.append(script);
 }else box.append(node('p','계정 확인 서비스가 아직 설정되지 않았습니다. 운영자에게 문의해 주세요.','warning'));
}
async function boot(){
 $('page-title').textContent=admin?'운영자 검수':'크리에이터 스튜디오';
 if(admin){const intro=document.querySelector('.intro p:last-child');if(intro)intro.textContent='크리에이터의 원본과 권리·공개 정보를 검토하고 승인 결과를 전달합니다.';}
 const config=await api('/v1/web/config');loginConfig=config;
 if(deletionPage){await renderDeletion(config);booting=false;return;}
 try{profile=await api('/v1/web/session');}catch{profile=null;}
 drafts=await allDrafts();const remembered=sessionStorage.getItem('audioapp-selected');selected=drafts.some(d=>d.id===remembered)?remembered!:drafts[0]?.id??'';renderAccount();await renderLogin(config);
 if(admin)await renderAdmin();else renderCreator();booting=false;
}
void attempt(boot);
