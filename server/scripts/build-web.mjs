import { build } from 'esbuild';
import { mkdir, copyFile } from 'node:fs/promises';
await mkdir('public/assets',{recursive:true});
await build({entryPoints:{app:'web/app.ts','import-worker':'web/import-worker.ts'},outdir:'public/assets',
  bundle:true,format:'esm',platform:'browser',target:['chrome120','firefox120','safari17'],minify:true,sourcemap:false});
await copyFile('web/index.html','public/index.html');
await copyFile('web/styles.css','public/assets/styles.css');
