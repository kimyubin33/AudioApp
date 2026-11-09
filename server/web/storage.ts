export interface WebDraft {
  id:string; file:File|null; source:string; info:{name:string;size:number;type:string;sha256:string};
  duration:number; width:number; height:number; metadata:Record<string,any>; previewConfirmed:boolean;
  ticket?:string; account?:string; submitted?:string; editId?:string; revision?:number; storageRevision?:number;
}
function database():Promise<IDBDatabase>{
  return new Promise((resolve,reject)=>{
    const req=indexedDB.open('audioapp-creator',1);
    req.onupgradeneeded=()=>req.result.createObjectStore('drafts',{keyPath:'id'});
    req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(new Error('브라우저 초안 저장소를 열 수 없습니다.'));
  });
}
const conflict=()=>new Error('다른 탭에서 이 초안이 변경되었습니다. 현재 입력을 복사한 뒤 새로고침해 주세요. 저장된 초안은 덮어쓰지 않았습니다.');
/** 읽은 버전과 같을 때만 저장한다. 탭 간의 오래된 쓰기로 업로드 ID/완료 상태를 지우지 않는다. */
export async function saveDraft(draft:WebDraft):Promise<number>{
  const db=await database(),next=(draft.storageRevision??0)+1;
  return new Promise<number>((resolve,reject)=>{
    const tx=db.transaction('drafts','readwrite'),store=tx.objectStore('drafts'),read=store.get(draft.id);
    read.onsuccess=()=>{
      if((read.result?.storageRevision??0)!==(draft.storageRevision??0)){reject(conflict());tx.abort();return;}
      store.put({...draft,storageRevision:next});
    };
    tx.oncomplete=()=>resolve(next);tx.onerror=()=>reject(new Error('초안을 저장하지 못했습니다. 저장 공간을 확인해 주세요.'));
    tx.onabort=()=>reject(new Error('초안 저장이 취소되었습니다.'));
  }).finally(()=>db.close());
}
/** ZIP 하나의 초안은 하나의 트랜잭션으로 저장한다. 할당량 초과도 기존 초안에 영향을 주지 않는다. */
export async function saveNewDrafts(drafts:WebDraft[]):Promise<void>{
  const db=await database();
  await new Promise<void>((resolve,reject)=>{
    const tx=db.transaction('drafts','readwrite'),store=tx.objectStore('drafts');
    for(const draft of drafts)store.add({...draft,storageRevision:1});
    tx.oncomplete=()=>resolve();tx.onerror=()=>reject(new Error('파일을 저장하지 못했습니다. 브라우저 저장 공간을 확인해 주세요.'));
    tx.onabort=()=>reject(new Error('파일 저장이 취소되었습니다.'));
  }).finally(()=>db.close());
  for(const draft of drafts)draft.storageRevision=1;
}
export async function allDrafts():Promise<WebDraft[]>{
  const db=await database();
  return new Promise<WebDraft[]>((resolve,reject)=>{
    const r=db.transaction('drafts').objectStore('drafts').getAll();
    r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(new Error('초안을 불러오지 못했습니다.'));
  }).finally(()=>db.close());
}
export async function deleteDraft(id:string,revision:number):Promise<void>{
  const db=await database();
  await new Promise<void>((resolve,reject)=>{
    const tx=db.transaction('drafts','readwrite'),store=tx.objectStore('drafts'),read=store.get(id);
    read.onsuccess=()=>{if((read.result?.storageRevision??0)!==revision){reject(conflict());tx.abort();return;}store.delete(id);};
    tx.oncomplete=()=>resolve();tx.onerror=()=>reject(new Error('초안을 삭제하지 못했습니다.'));
    tx.onabort=()=>reject(new Error('초안 삭제가 취소되었습니다.'));
  }).finally(()=>db.close());
}
