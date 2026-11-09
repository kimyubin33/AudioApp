import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir:'./e2e',workers:1,fullyParallel:false,timeout:120_000,
  use:{browserName:'chromium',channel:process.platform==='win32'?'msedge':undefined,
    launchOptions:{args:['--autoplay-policy=no-user-gesture-required']},viewport:{width:1365,height:1000},
    actionTimeout:15_000,screenshot:'only-on-failure',trace:'retain-on-failure'},
  reporter:'list',
});
