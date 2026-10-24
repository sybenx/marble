// Builds the extension for each browser into dist/<target>: chrome (also Edge, Brave,
// Opera), firefox (desktop and Android) and safari (Mac, iPhone, iPad; wrapped by the
// Xcode project in safari/).
import { build } from 'esbuild';
import { cpSync, mkdirSync, rmSync, writeFileSync, readFileSync } from 'node:fs';

const { version } = JSON.parse(readFileSync('package.json', 'utf8'));
const targets = process.argv.slice(2).length ? process.argv.slice(2) : ['chrome', 'firefox', 'safari'];

const base = {
  manifest_version: 3,
  name: 'Cosign',
  short_name: 'Cosign',
  version,
  description: 'Sign in to Nostr sites with your phone. Your key never sits in the browser.',
  icons: { 16: 'icon-16.png', 32: 'icon-32.png', 48: 'icon-48.png', 96: 'icon-96.png', 128: 'icon-128.png', 256: 'icon-256.png', 512: 'icon-512.png' },
  action: { default_popup: 'popup.html', default_title: 'Cosign', default_icon: { 16: 'icon-16.png', 32: 'icon-32.png', 48: 'icon-48.png' } },
  permissions: ['storage', 'activeTab'],
  content_scripts: [{ matches: ['<all_urls>'], js: ['content.js'], run_at: 'document_start', all_frames: false }],
  // WebAssembly (Cosign's FROST, phase 2) needs 'wasm-unsafe-eval'; nothing else is relaxed.
  content_security_policy: { extension_pages: "script-src 'self' 'wasm-unsafe-eval'; object-src 'self'" },
  web_accessible_resources: [{ resources: ['inpage.js', 'card.html', 'card.js', 'style.css', 'icon-48.png'], matches: ['<all_urls>'] }],
};

const manifests = {
  chrome: { ...base, minimum_chrome_version: '116', background: { service_worker: 'background.js' } },
  firefox: {
    ...base,
    background: { scripts: ['background.js'] },
    browser_specific_settings: {
      gecko: { id: 'cosign@aaro.cc', strict_min_version: '128.0', data_collection_permissions: { required: ['none'] } },
      gecko_android: { strict_min_version: '128.0' },
    },
  },
  safari: { ...base, background: { scripts: ['background.js'], persistent: false } },
};

for (const t of targets) {
  const out = `dist/${t}`;
  rmSync(out, { recursive: true, force: true });
  mkdirSync(out, { recursive: true });
  await build({
    entryPoints: { background: 'src/background.ts', content: 'src/content.ts', inpage: 'src/inpage.ts', card: 'src/ui/card.ts', popup: 'src/ui/popup.ts', 'spike-weighted': 'src/spike/weighted.ts' },
    outdir: out, bundle: true, format: 'iife', target: ['chrome116', 'firefox128', 'safari16'], minify: false, logLevel: 'warning',
    // wasm-bindgen's default loader looks at import.meta.url; ours loads the module itself.
    define: { 'import.meta.url': 'undefined' },
  });
  cpSync('static', out, { recursive: true });
  cpSync('vendor/cosign-frost/cosign_frost_bg.wasm', `${out}/cosign_frost_bg.wasm`);
  writeFileSync(`${out}/spike-weighted.html`, '<!doctype html><meta charset="utf-8"><title>weighted spike</title><script src="spike-weighted.js"></script>');
  writeFileSync(`${out}/manifest.json`, JSON.stringify(manifests[t], null, 2));
  console.log(`built ${out}`);
}
