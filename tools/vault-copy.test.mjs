// site/js/vault/ is the Vaults engine from ~/vaults (js/vault/), copied as it is (its tools/vault-copy.mjs; its
// SOURCE names the commit). This checks the copy was not edited here, that site/js/vendor/sign.js gives every name the
// engine imports from it, and that SOURCE is not served.
//   node tools/vault-copy.test.mjs
// The vaults checkout: VAULTS_DIR, else the worktree of ~/vaults at the commit SOURCE names,
// else one whose history has it, else ~/vaults. Without one holding the tool, the first check is skipped and
// says so.
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";

let n = 0;
const ok = (c, m) => { if (!c) throw new Error("FAIL: " + m); n++; };
const root = new URL("..", import.meta.url).pathname;
const COPY = path.join(root, "site/js/vault");
const tool = (dir) => path.join(dir, "tools/vault-copy.mjs");

function checkout() {
  const given = process.env.VAULTS_DIR;
  if (given) return existsSync(tool(given)) ? given : null;
  const home = path.join(os.homedir(), "vaults");
  if (!existsSync(home)) return null;
  const commit = readFileSync(path.join(COPY, "SOURCE"), "utf8").split("\n")[0].replace(/^(vaults|nostr-sync-nip) /, "").split(" ")[0];
  const list = spawnSync("git", ["-C", home, "worktree", "list", "--porcelain"], { encoding: "utf8" });
  const trees = (list.stdout ?? "").split("\n\n").map((b) => ({ dir: /^worktree (.+)$/m.exec(b)?.[1], head: /^HEAD ([0-9a-f]{40})$/m.exec(b)?.[1] }))
    .filter((t) => t.dir && t.head && existsSync(tool(t.dir)));
  const at = trees.find((t) => t.head === commit)
    ?? trees.find((t) => spawnSync("git", ["-C", t.dir, "merge-base", "--is-ancestor", commit, "HEAD"]).status === 0);
  if (at) return at.dir;
  return existsSync(tool(home)) ? home : null;
}

// the copy is the engine as it is
const dir = checkout();
if (!dir) console.log("vault copy: SKIPPED the --check (no vaults checkout with tools/vault-copy.mjs)");
else {
  const r = spawnSync(process.execPath, [tool(dir), "--check", COPY], { encoding: "utf8" });
  if (r.status !== 0) console.error(r.stdout + r.stderr);
  ok(r.status === 0, `site/js/vault is the vaults repo's copy, unedited and the same as ${dir}`);
}

// what the host provides: ../vendor/sign.js beside the copy, with every name crypto.js takes from it
{
  const src = readFileSync(path.join(COPY, "crypto.js"), "utf8");
  const m = /import \{([^}]+)\} from "\.\.\/vendor\/sign\.js";/.exec(src);
  ok(m, "crypto.js imports from ../vendor/sign.js");
  const names = m[1].split(",").map((s) => s.trim().split(/\s+as\s+/)[0]);
  const sign = await import(new URL("../site/js/vendor/sign.js", import.meta.url));
  ok(names.length === 7 && names.every((k) => sign[k] !== undefined), `site/js/vendor/sign.js gives ${names.join(", ")}`);
  const others = [...readFileSync(path.join(COPY, "sync.js"), "utf8").matchAll(/from "([^"]+)"/g), ...["core", "notes", "state"].flatMap((f) =>
    [...readFileSync(path.join(COPY, f + ".js"), "utf8").matchAll(/from "([^"]+)"/g)])].map((x) => x[1]);
  ok(others.every((p) => /^\.\/[a-z]+\.js$/.test(p)), "no other file of the copy imports from outside it");
}

// SOURCE is not served
ok(readFileSync(path.join(root, "site/.assetsignore"), "utf8").split("\n").includes("js/vault/SOURCE"), ".assetsignore keeps js/vault/SOURCE off the site");

console.log(`vault copy: ${n} checks passed`);
