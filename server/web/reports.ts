type Json=Record<string,any>;
type Api=(path:string,method?:string,body?:unknown)=>Promise<any>;
const states:Json={open:'접수',reviewing:'검토 중',resolved:'조치 완료',dismissed:'기각'};
const actions:Json={review:'검토 시작',dismiss:'기각',remove_content:'콘텐츠 공개 중단',revoke_upload:'업로드 권한 회수'};
function el(tag:string,text=''){const n=document.createElement(tag);n.textContent=text;return n;}
export function mountReports(parent:HTMLDetailsElement,api:Api,openContent:(id:string,target:HTMLElement)=>Promise<void>){
 const nav=el('div'),list=el('div'),detail=el('div'),error=el('p');error.setAttribute('role','alert');
 parent.append(nav,error,list,detail);let generation=0,active='open',loaded=false;
 function button(label:string,run:()=>Promise<void>){const b=el('button',label) as HTMLButtonElement;b.type='button';
  b.onclick=async()=>{b.disabled=true;error.textContent='';try{await run();}catch(e){error.textContent=e instanceof Error?e.message:'처리하지 못했습니다.';}finally{b.disabled=false;}};return b;}
 async function load(status:string,cursor:string|null=null){
  const current=++generation;active=status;
  const data=await api('/v1/admin/reports?status='+status+(cursor?'&cursor='+encodeURIComponent(cursor):''));
  if(current!==generation||!parent.isConnected)return;
  if(!cursor){list.replaceChildren();detail.replaceChildren();}
  list.querySelector('.more')?.remove();
  if(!data.items.length&&!cursor)list.append(el('p','해당 상태의 신고가 없습니다.'));
  for(const r of data.items){const row=el('article');row.append(el('p',(r.title??r.creator_id)+' · '+r.reason+' · '+new Date(r.created_at*1000).toLocaleString('ko-KR')),
   button('신고 상세',()=>show(r.id)));list.append(row);}
  if(data.next_cursor){const more=button('신고 더 보기',()=>load(status,data.next_cursor));more.className='more';list.append(more);}
 }
 async function show(id:string){
  const current=++generation,r=await api('/v1/admin/reports/'+id);
  if(current!==generation||!parent.isConnected)return;
  detail.replaceChildren(el('h3','신고 상세'),el('p','접수 번호: '+r.id),el('p','대상: '+(r.title??r.creator_id)),
   el('p','사유: '+r.reason),el('p','상태: '+states[r.status]),el('p','접수: '+new Date(r.created_at*1000).toLocaleString('ko-KR')),
   el('p','신고 설명 (신고자 진술): '+(r.detail??'없음')));
  if(r.content_id){const preview=el('div');detail.append(button('원본·제출 정보 열기',()=>openContent(r.content_id,preview)),preview);}
  if(['open','reviewing'].includes(r.status)){
   const label=el('label','처리 사유 (신고자에게 표시됨)'),input=document.createElement('textarea');input.maxLength=200;input.setAttribute('aria-label','처리 사유 (신고자에게 표시됨)');label.append(input);detail.append(label);
   const confirmation=el('label','신고 내용과 조치 범위를 확인했습니다.'),check=document.createElement('input');check.type='checkbox';confirmation.prepend(check);detail.append(confirmation);
   detail.append(el('p','공개 중단은 해당 콘텐츠를 숨깁니다. 업로드 권한 회수는 새 제출을 막으며 기존 콘텐츠는 별도로 검토합니다.'));
   for(const action of ['review','dismiss',...(r.content_id?['remove_content']:['revoke_upload'])]){
    if(action==='review'&&r.status!=='open')continue;
    detail.append(button(actions[action],async()=>{
     if(!check.checked||!input.value.trim())throw new Error('처리 사유와 조치 확인이 필요합니다.');
     await api('/v1/admin/reports/'+id+'/review','POST',{action,revision:r.revision,content_revision:r.content_revision,resolution:input.value,confirmed:true});
     await load(active);await show(id);
    }));
   }
  }
  detail.append(el('h4','신고 처리 이력'));
  for(const h of r.history)detail.append(el('p',new Date(h.created_at*1000).toLocaleString('ko-KR')+' · '+actions[h.action]+' · '+h.resolution));
  detail.append(button('신고 새로고침',()=>show(id)));
 }
 for(const status of Object.keys(states))nav.append(button(states[status]+' 신고',()=>load(status)));
 parent.addEventListener('toggle',()=>{if(parent.open&&!loaded){loaded=true;void load('open').catch(e=>{loaded=false;error.textContent=String(e.message??e);});}});
}
