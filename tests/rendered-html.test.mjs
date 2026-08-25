import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function render() {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request("http://localhost/", { headers: { accept: "text/html" } }),
    { ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) } },
    { waitUntil() {}, passThroughOnException() {} },
  );
}

test("server-renders the A.R.C. command center with hydration-safe clock", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /<title>A\.R\.C\. — AI Resource Command<\/title>/i);
  assert.match(html, /COMMAND CENTER/);
  assert.match(html, /--:--/);
  assert.match(html, /AI RESOURCE COMMAND/);
  assert.doesNotMatch(html, /Адилет Абдуллов|Payment gateway integration/);
});

test("keeps navigation functional and never falls back to demo Jira data", async () => {
  const page = await readFile(new URL("../app/page.tsx", import.meta.url), "utf8");
  const styles = await readFile(new URL("../app/globals.css", import.meta.url), "utf8");
  const vite = await readFile(new URL("../vite.config.ts", import.meta.url), "utf8");
  const compose = await readFile(new URL("../docker-compose.yml", import.meta.url), "utf8");

  for (const label of ["Command", "Portfolio", "Projects", "People", "AI Planner", "Analytics", "Reports", "Integrations", "Settings"]) {
    assert.match(page, new RegExp(`"${label}"`));
  }
  assert.match(page, /Promise\.allSettled/);
  assert.match(page, /json<Person\[]>\(`\$\{API\}\/people`\)/);
  assert.match(page, /PeopleDirectory/);
  assert.match(page, /onPositionChange/);
  assert.match(page, /const \[projectFilter, setProjectFilter\] = useState\(selectedProject\)/);
  assert.match(page, /SimulationResult/);
  assert.match(page, /НЕ РЕКОМЕНДУЕТСЯ/);
  assert.doesNotMatch(page, /JSON\.stringify\(simulation/);
  assert.match(page, /setEmployee\(\(current\) => data\.people\.some/);
  assert.match(page, /cosmic-field/);
  assert.match(page, /orbit-energy/);
  assert.match(page, /orbit-telemetry/);
  assert.match(page, /cosmic-particles/);
  assert.match(page, /energy-sweep/);
  assert.match(styles, /arc-portfolio-core-v2\.png/);
  assert.match(page, /json\(`\$\{API\}\/people\/position`/);
  assert.doesNotMatch(page, /encodeURIComponent\(employee\)\/position/);
  assert.match(page, /setModal\("integrations"\)/);
  assert.match(page, /setModal\("settings"\)/);
  assert.doesNotMatch(page, /const fallback|preview:\s*true/);
  assert.match(vite, /"\/api"/);
  assert.match(compose, /ARC_PUBLIC_PORT:-3000/);
});
