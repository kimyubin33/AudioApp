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
