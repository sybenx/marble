// The test site, on http://localhost:8799 (and your LAN address, for a phone).
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
const page = readFileSync(new URL('./site/index.html', import.meta.url));
createServer((_, res) => { res.setHeader('content-type', 'text/html; charset=utf-8'); res.end(page); }).listen(8799, '0.0.0.0', () => console.log('test site on http://localhost:8799'));
