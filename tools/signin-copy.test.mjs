// site/js/signin/ is Keykin's sign-in package, copied as it is (keykin tools/signin-copy.mjs; its SOURCE names the commit).
// This checks the copy was not edited here, and that the old vendor/ paths still give every name from the one copy.
//   node tools/signin-copy.test.mjs
// Needs the Keykin checkout at ~/keykin for the first check; without it that check is skipped and says so.
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import os from "node:os";
import path from "node:path";

let n = 0;
const ok = (c, m) => { if (!c) throw new Error("FAIL: " + m); n++; };
const root = new URL("..", import.meta.url).pathname;

// the copy is the package as it is
const tool = path.join(os.homedir(), "keykin/tools/signin-copy.mjs");
if (!existsSync(tool)) console.log(`signin copy: SKIPPED the --check (no Keykin checkout at ${tool})`);
else {
  const r = spawnSync(process.execPath, [tool, "--check", "site/js/signin"], { cwd: root, encoding: "utf8" });
  if (r.status !== 0) console.error(r.stdout + r.stderr);
  ok(r.status === 0, "site/js/signin is Keykin's copy, unedited");
}

// vendor/noble.js and vendor/sign.js (vault/ imports sign.js) give the same names, from the same module, as signin/vendor/
for (const f of ["noble.js", "sign.js"]) {
  const shim = await import(new URL(`../site/js/vendor/${f}`, import.meta.url)), real = await import(new URL(`../site/js/signin/vendor/${f}`, import.meta.url));
  ok(Object.keys(real).length > 0 && JSON.stringify(Object.keys(shim).sort()) === JSON.stringify(Object.keys(real).sort()), `vendor/${f}: the same names`);
  ok(Object.keys(real).every((k) => shim[k] === real[k]), `vendor/${f}: the same things, one copy`);
}

console.log(`signin copy: ${n} checks passed`);
