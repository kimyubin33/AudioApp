import { Inflate } from 'fflate';
import { sha256 } from '@noble/hashes/sha2.js';

export const FILE_MAX = 90 * 1024 * 1024, TOTAL_MAX = 360 * 1024 * 1024, ZIP_MAX = 180 * 1024 * 1024;
export function mediaType(name: string): string | null {
  const ext = name.split('.').at(-1)?.toLowerCase();
  return ext === 'mp3' ? 'audio/mpeg' : ext === 'm4a' ? 'audio/mp4' : ext === 'mp4' ? 'video/mp4' : null;
}
export function safePath(name: string): boolean {
  if (!name || name.length > 1024 || /[\\\u0000-\u001f\u007f:]/.test(name) || name.startsWith('/')) return false;
  const parts = name.replace(/\/$/,'').split('/');
  return parts.every(p=>p!==''&&p!=='.'&&p!=='..'&&p.trim()===p&&![...p].some(c=>c==='\u202e'));
}
export async function fileHash(blob: Blob): Promise<string> {
  const hash = sha256.create();
  for (let offset=0;offset<blob.size;offset+=256*1024) hash.update(new Uint8Array(await blob.slice(offset,offset+256*1024).arrayBuffer()));
  return Array.from(hash.digest(),b=>b.toString(16).padStart(2,'0')).join('');
}
export async function inspectFile(file: File) {
  const type = mediaType(file.name);
  if (!type || !safePath(file.name) || file.name.includes('/') || [...file.name].length > 255 ||
      file.size<1||file.size>FILE_MAX) throw new Error('MP3·M4A·MP4 파일을 90MiB 이하로 선택해 주세요.');
  const b = new Uint8Array(await file.slice(0,64).arrayBuffer());
  const valid = type==='audio/mpeg'
    ? b.length>=3 && ((b[0]===73&&b[1]===68&&b[2]===51)||(b[0]===255&&(b[1]&224)===224&&(b[1]&6)!==0))
    : b.length>=12&&String.fromCharCode(...b.slice(4,8))==='ftyp';
  if (!valid) throw new Error('확장자와 실제 파일 형식이 다릅니다.');
  return {name:file.name,size:file.size,type,sha256:await fileHash(file)};
}
const crcTable = Uint32Array.from({length:256},(_,i)=>{
  for(let j=0;j<8;j++) i=i&1?0xedb88320^(i>>>1):i>>>1;
  return i>>>0;
});
function crcUpdate(crc: number, data: Uint8Array): number {
  for(const b of data) crc=crcTable[(crc^b)&255]^(crc>>>8);
  return crc;
}
async function bytes(file: Blob, offset: number, length: number) {
  if (offset<0||length<0||offset+length>file.size) throw new Error('ZIP 범위가 올바르지 않습니다.');
  return new Uint8Array(await file.slice(offset,offset+length).arrayBuffer());
}
interface Entry { name:string; flags:number; method:number; size:number; packed:number; crc:number; offset:number; directory:boolean; }
export async function extractZip(file: File, remainingBytes: number, remainingFiles: number) {
  if (file.size<22||file.size>ZIP_MAX) throw new Error('ZIP은 180MiB 이하로 선택해 주세요.');
  const tail=await bytes(file,Math.max(0,file.size-65557),Math.min(file.size,65557));
  const t=new DataView(tail.buffer);
  let end=-1;
  for(let i=tail.length-22;i>=0;i--) if(t.getUint32(i,true)===0x06054b50&&i+22+t.getUint16(i+20,true)===tail.length){end=i;break;}
  if(end<0) throw new Error('ZIP 끝 정보가 없거나 손상되었습니다.');
  const count=t.getUint16(end+10,true), size=t.getUint32(end+12,true), offset=t.getUint32(end+16,true);
  if(t.getUint16(end+4,true)!==0||t.getUint16(end+6,true)!==0||count!==t.getUint16(end+8,true)||count>200||
      size>2*1024*1024||size===0xffffffff||offset===0xffffffff||offset+size!==file.size-tail.length+end)
    throw new Error('분할·ZIP64 ZIP 또는 200개를 넘는 항목은 지원하지 않습니다.');
  const dir=await bytes(file,offset,size), view=new DataView(dir.buffer), entries:Entry[]=[], paths=new Set<string>();
  let pos=0,total=0,mediaCount=0;
  for(let n=0;n<count;n++){
    if(pos+46>dir.length||view.getUint32(pos,true)!==0x02014b50) throw new Error('ZIP 항목 정보가 손상되었습니다.');
    const flags=view.getUint16(pos+8,true),method=view.getUint16(pos+10,true);
    const packed=view.getUint32(pos+20,true),unpacked=view.getUint32(pos+24,true),nameLen=view.getUint16(pos+28,true);
    const extra=view.getUint16(pos+30,true),comment=view.getUint16(pos+32,true),entryOffset=view.getUint32(pos+42,true);
    if(pos+46+nameLen+extra+comment>dir.length||view.getUint16(pos+34,true)!==0||[packed,unpacked,entryOffset].includes(0xffffffff))
      throw new Error('분할·ZIP64 또는 손상된 ZIP입니다.');
    if((flags&1)!==0||![0,8].includes(method)) throw new Error('암호화 ZIP 또는 지원하지 않는 압축 방식입니다.');
    const name=new TextDecoder('utf-8',{fatal:true}).decode(dir.slice(pos+46,pos+46+nameLen));
    if(!safePath(name)||paths.has(name.normalize('NFC').toLowerCase())) throw new Error('ZIP 경로 조작 또는 중복 경로를 발견했습니다.');
    paths.add(name.normalize('NFC').toLowerCase());
    const directory=name.endsWith('/');
    // Unix 심볼릭 링크나 특수 파일을 가져오지 않는다.
    const unixType=(view.getUint32(pos+38,true)>>>16)&0xf000;
    if(unixType!==0&&unixType!==0x8000&&unixType!==0x4000) throw new Error('ZIP의 링크·특수 파일은 지원하지 않습니다.');
    if(unpacked>TOTAL_MAX || (unpacked>1024*1024&&unpacked>Math.max(1,packed)*200)) throw new Error('ZIP 압축 해제 크기 또는 압축률이 너무 큽니다.');
    total+=unpacked;
    if(total>TOTAL_MAX) throw new Error('ZIP 전체 해제 크기는 360MiB 이하여야 합니다.');
    if(!directory&&mediaType(name)){
      if(unpacked<1||unpacked>FILE_MAX) throw new Error('ZIP 안의 미디어는 각각 90MiB 이하여야 합니다.');
      mediaCount++;
    }
    entries.push({name,flags,method,size:unpacked,packed,crc:view.getUint32(pos+16,true),offset:entryOffset,directory});
    pos+=46+nameLen+extra+comment;
  }
  if(pos!==dir.length) throw new Error('ZIP 중앙 디렉터리가 손상되었습니다.');
  const selected=entries.filter(e=>!e.directory&&mediaType(e.name));
  if(mediaCount>remainingFiles||selected.reduce((n,e)=>n+e.size,0)>remainingBytes)
    throw new Error('초안은 최대 20개·총 360MiB입니다.');
  // 중앙/로컬 이름과 범위를 모두 확인한다. 한 파일 실패 시 이 ZIP의 결과는 전부 폐기한다.
  const ranges:Array<[number,number]>=[],files:Array<{file:File;source:string}>=[];
  for(const entry of entries){
    const header=await bytes(file,entry.offset,30),h=new DataView(header.buffer);
    if(h.getUint32(0,true)!==0x04034b50||h.getUint16(6,true)!==entry.flags||h.getUint16(8,true)!==entry.method)
      throw new Error('ZIP의 로컬 항목 정보가 일치하지 않습니다.');
    const nameLen=h.getUint16(26,true),extraLen=h.getUint16(28,true);
    const name=new TextDecoder('utf-8',{fatal:true}).decode(await bytes(file,entry.offset+30,nameLen));
    const start=entry.offset+30+nameLen+extraLen,stop=start+entry.packed;
    if(name!==entry.name||stop>offset||ranges.some(([a,b])=>entry.offset<b&&stop>a)) throw new Error('ZIP의 파일 범위가 겹치거나 손상되었습니다.');
    ranges.push([entry.offset,stop]);
    if(entry.directory||!mediaType(entry.name)) continue;
    const chunks:Uint8Array<ArrayBuffer>[]=[];
    let emitted=0,crc=0xffffffff,finished=false;
    const consume=(chunk:Uint8Array,final:boolean)=>{
      emitted+=chunk.length;
      if(emitted>entry.size||emitted>FILE_MAX) throw new Error('실제 압축 해제 크기가 명세와 다릅니다.');
      crc=crcUpdate(crc,chunk);
      chunks.push(Uint8Array.from(chunk));finished=final;
    };
    const inflater=entry.method===8?new Inflate(consume):null;
    for(let p=start;p<stop;p+=8192){
      const part=await bytes(file,p,Math.min(8192,stop-p));
      if(inflater) inflater.push(part,p+part.length===stop); else consume(part,p+part.length===stop);
    }
    if(inflater&&entry.packed===0) inflater.push(new Uint8Array(),true);
    if(!finished||emitted!==entry.size||((crc^0xffffffff)>>>0)!==entry.crc) throw new Error('ZIP CRC 또는 해제 크기 검증에 실패했습니다.');
    const nameOnly=entry.name.split('/').at(-1)!;
    files.push({file:new File(chunks,nameOnly,{type:mediaType(nameOnly)!,lastModified:file.lastModified}),source:file.name+' / '+entry.name});

  }
  return {files,skipped:entries.filter(e=>!e.directory&&!mediaType(e.name)).map(e=>e.name)};
}
