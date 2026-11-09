import { extractZip, inspectFile, fileHash } from './import';
self.onmessage=async (event:MessageEvent)=>{
  const {file,remainingBytes,remainingFiles,hashOnly}=event.data;
  try{
    if(hashOnly){self.postMessage({hash:await fileHash(file)});return;}
    const result=file.name.toLowerCase().endsWith('.zip')?await extractZip(file,remainingBytes,remainingFiles):{files:[{file,source:file.name}],skipped:[]};
    const inspected=[];
    for(const item of result.files) inspected.push({...item,info:await inspectFile(item.file)});
    self.postMessage({files:inspected,skipped:result.skipped});
  }catch(e){self.postMessage({error:e instanceof Error?e.message:'파일 검사에 실패했습니다.'});}
};
