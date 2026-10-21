import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: '.', testMatch: '*.spec.js', workers: 1,
  use: { browserName: 'chromium', channel: process.env.CI ? undefined : 'chrome',
    viewport: { width: 390, height: 780 }, headless: true },
  reporter: 'list'
});
