import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { zipSync } from 'fflate';
import { extractZip, inspectFile, safePath, FILE_MAX } from '../web/import.ts';
const mp3 = new Uint8Array(128);mp3.set([73,68,51,4]);
const archive = (entries, options={level:0}) => new File([zipSync(entries,options)],'batch.zip');
test('웹 원본: 청크 SHA-256과 실제 형식·이름·크기 검사',async()=>{
  const result=await inspectFile(new File([mp3],'first.mp3'));
  assert.equal(result.sha256,createHash('sha256').update(mp3).digest('hex'));
  for(const name of ['../a.mp3','C:a.mp3','dir\\a.mp3','/a.mp3','a/../b.mp3']) assert.equal(safePath(name),false);
  await assert.rejects(()=>inspectFile(new File([new Uint8Array(20)],'bad.mp3')));
  await assert.rejects(()=>inspectFile(new File([mp3],'bad.exe')));
});
test('웹 ZIP: 개별 원본과 원래 경로를 반환하고 비미디어는 명시적으로 제외',async()=>{
  const zip=archive({'dir/one.mp3':mp3,'notes.txt':new TextEncoder().encode('설명')});
  const result=await extractZip(zip,1024*1024,20);
  assert.equal(result.files.length,1);assert.equal(result.files[0].source,'batch.zip / dir/one.mp3');
  assert.deepEqual(new Uint8Array(await result.files[0].file.arrayBuffer()),mp3);
  assert.deepEqual(result.skipped,['notes.txt']);
  assert.equal((await extractZip(archive({'one.mp3':mp3},{level:6}),1024*1024,20)).files.length,1);
});
test('웹 ZIP: 경로·중복·할당량·CRC·손상·암호화·압축 폭탄은 통째로 거부',async()=>{
  for(const entries of [{'../bad.mp3':mp3},{'one.mp3':mp3,'ONE.mp3':mp3}]) await assert.rejects(()=>extractZip(archive(entries),1024*1024,20));
  const good=archive({'one.mp3':mp3});
  await assert.rejects(()=>extractZip(good,10,20));await assert.rejects(()=>extractZip(good,1024,0));
  const broken=new Uint8Array(await good.arrayBuffer());broken[30+'one.mp3'.length+10]^=1;
  await assert.rejects(()=>extractZip(new File([broken],'crc.zip'),1024,20),/CRC/);
  await assert.rejects(()=>extractZip(new File([broken.slice(0,-5)],'truncated.zip'),1024,20));
  const encrypted=new Uint8Array(await good.arrayBuffer()),view=new DataView(encrypted.buffer);
  let central=-1;for(let i=0;i<encrypted.length-4;i++)if(view.getUint32(i,true)===0x02014b50){central=i;break;}
  view.setUint16(central+8,1,true);await assert.rejects(()=>extractZip(new File([encrypted],'encrypted.zip'),1024,20),/암호화/);
  view.setUint16(central+8,0,true);view.setUint32(central+24,FILE_MAX+1,true);
  await assert.rejects(()=>extractZip(new File([encrypted],'bomb.zip'),1024,20));
});

test('웹 ZIP: 비미디어도 CRC를 검사하고 중첩 ZIP을 재귀 해제하지 않는다',async()=>{
  const nested=archive({'inner.mp3':mp3});
  const good=archive({'one.mp3':mp3,'nested.zip':new Uint8Array(await nested.arrayBuffer()),'notes.txt':mp3});
  const result=await extractZip(good,4096,20);
  assert.equal(result.files.length,1);assert.deepEqual(result.skipped,['nested.zip','notes.txt']);
  const raw=new Uint8Array(await archive({'notes.txt':mp3}).arrayBuffer());
  raw[30+'notes.txt'.length+10]^=1;
  await assert.rejects(()=>extractZip(new File([raw],'bad.zip'),4096,20),/CRC/);
});
test('웹 ZIP: 정규화 중복·빈 구간·제어 문자·디렉터리 별칭을 거부한다',async()=>{
  for(const names of [['é.mp3','e\u0301.mp3'],['a/','a'],['a//b.mp3'],[' a.mp3'],['a\u2066.mp3']]){
    const entries=Object.fromEntries(names.map(n=>[n,n.endsWith('/')?new Uint8Array():mp3]));
    await assert.rejects(()=>extractZip(archive(entries),4096,20));
  }
  await assert.rejects(()=>extractZip(archive({}),4096,20));
});
test('웹 ZIP: 로컬 파일명·크기 불일치, 링크와 항목 수 초과를 거부한다',async()=>{
  const raw=new Uint8Array(await archive({'one.mp3':mp3}).arrayBuffer());
  const c=raw.findIndex((_,i)=>i+4<=raw.length&&new DataView(raw.buffer).getUint32(i,true)===0x02014b50);
  for(const mutate of [
    b=>{b[30]=88;},
    b=>{new DataView(b.buffer).setUint32(22,129,true);},
    b=>{new DataView(b.buffer).setUint32(c+38,0xa0000000,true);},
    b=>{new DataView(b.buffer).setUint16(c+8,0x40,true);},
  ]){
    const bad=raw.slice();mutate(bad);
    await assert.rejects(()=>extractZip(new File([bad],'bad.zip'),4096,20));
  }
  await assert.rejects(()=>extractZip(archive(Object.fromEntries(Array.from({length:201},(_,i)=>['f'+i+'.txt',new Uint8Array()]))),4096,20));
});
test('웹 ZIP: 지원하지 않는 항목도 파일별·전체 해제 크기와 압축률 상한에 포함한다',async()=>{
  const raw=new Uint8Array(await archive({'notes.txt':mp3}).arrayBuffer());
  const view=new DataView(raw.buffer);
  const c=raw.findIndex((_,i)=>i+4<=raw.length&&view.getUint32(i,true)===0x02014b50);
  view.setUint32(c+24,FILE_MAX+1,true);
  await assert.rejects(()=>extractZip(new File([raw],'large.zip'),4096,20));
  await assert.rejects(()=>extractZip(archive({'notes.txt':new Uint8Array(100000)},{level:9}),4096,20),/압축률/);
});
