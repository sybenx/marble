// site/ on http://localhost:PORT with index.html for any other path (as the Worker does), for local tests only.
import http from "node:http"; import fs from "node:fs"; import path from "node:path";
const root = new URL("../site/", import.meta.url).pathname, port = +(process.argv[2] || 8788);
const types = { ".html": "text/html", ".js": "text/javascript", ".json": "application/json", ".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg", ".xml": "application/xml" };
http.createServer((q, r) => {
  let f = path.join(root, decodeURIComponent(new URL(q.url, "http://x").pathname));
  if (!f.startsWith(root) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) f = path.join(root, "index.html");
  r.writeHead(200, { "Content-Type": types[path.extname(f)] || "application/octet-stream", "Cache-Control": "no-store" });
  fs.createReadStream(f).pipe(r);
}).listen(port, () => console.log("serving site on http://localhost:" + port));
