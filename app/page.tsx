"use client";

import { FormEvent, ReactNode, useCallback, useEffect, useMemo, useRef, useState } from "react";

type ProjectHealth = {
  project: string;
  project_key: string;
  health_score: number;
  status: "CRITICAL" | "ATTENTION" | "ON_TRACK" | string;
  blocked: number;
  stuck: number;
  testing: number;
  release_readiness: number;
};

type Sprint = {
  sprint_id: string;
  name: string;
  total: number;
  blocked: number;
  in_progress: number;
  review: number;
  test: number;
  done_dev: number;
  done_prod: number;
  done: number;
  stuck: number;
  progress_percent: number;
  health_score: number;
};

type Person = {
  employee: string;
  jira_username?: string;
  role: string;
  role_source?: "DIRECTORY" | "JIRA" | "UNSPECIFIED" | string;
  active_tasks: number;
  testing_tasks?: number;
  done_dev_tasks?: number;
  blocked_tasks: number;
  high_priority?: number;
  load_score: number;
  free_capacity: number;
  status: string;
  projects?: Array<{ project: string; allocation: number }>;
};

type Task = {
  key: string;
  summary: string;
  status: string;
  assignee: string;
  stuck_days?: number;
};

type Delivery = {
  planning_stability: number;
  scope_control: number;
  blocker_resolution: number;
  task_hygiene: number;
  delivery_predictability: number;
};

type SimulationResultData = {
  simulation: boolean;
  employee: string;
  capacity_percent: number;
  before: Record<string, number>;
  after: Record<string, number>;
  estimated_delay_from?: number;
  estimated_gain_to?: number;
  assumptions: string[];
};

type DashboardData = {
  portfolio: {
    health_score: number;
    critical_projects: number;
    at_risk_projects: number;
    healthy_projects: number;
    projects: ProjectHealth[];
  };
  attention: ProjectHealth[];
  recommendations: string[];
  sprint: Sprint;
  people: Person[];
  all_people: Person[];
  stuck: Task[];
  release: { score: number; status: string; blocked: number; testing: number; critical_bugs: number };
  anomalies: { baseline_available: boolean; baseline_source?: string; history_coverage_percent?: number; anomalies: Array<{ metric: string; before: number; now: number; delta: number; change_percent: number; severity: string }>; message?: string };
  delivery: Delivery;
  weekly: Record<string, unknown>;
  planner: { recommendations: Array<{ employee: string; to_project: string; capacity_percent: number; reason: string }> };
};

type ModalName = "people" | "stuck" | "simulation" | "planner" | "weekly" | "anomalies" | "delivery" | "release" | "integrations" | "settings" | null;
type ClockValue = { date: string; time: string };

const API = "/api";
const AI_API = "/ai";
const JIRA_BROWSE_URL = "https://tasks.belesai.kz/browse";
const POSITION_OPTIONS = [
  "Не указана", "Backend", "Frontend", "Fullstack", "Mobile", "QA",
  "DevOps", "Product / PM", "Business Analyst", "Product Design", "Team Lead",
];

function jiraTaskUrl(issueKey: string) {
  return `${JIRA_BROWSE_URL}/${encodeURIComponent(issueKey)}`;
}

const emptyDashboard: DashboardData = {
  portfolio: {
    health_score: 0,
    critical_projects: 0,
    at_risk_projects: 0,
    healthy_projects: 0,
    projects: [],
  },
  attention: [],
  recommendations: [],
  sprint: { sprint_id: "", name: "Загрузка данных Jira…", total: 0, blocked: 0, in_progress: 0, review: 0, test: 0, done_dev: 0, done_prod: 0, done: 0, stuck: 0, progress_percent: 0, health_score: 0 },
  people: [],
  all_people: [],
  stuck: [],
  release: { score: 0, status: "LOADING", blocked: 0, testing: 0, critical_bugs: 0 },
  anomalies: { baseline_available: false, anomalies: [], message: "Накопление истории началось." },
  delivery: { planning_stability: 0, scope_control: 0, blocker_resolution: 0, task_hygiene: 0, delivery_predictability: 0 },
  weekly: { baseline_available: false, message: "Weekly baseline формируется." },
  planner: { recommendations: [] },
};

const navItems = [
  ["⌘", "Command"], ["◫", "Portfolio"], ["▣", "Projects"], ["♙", "People"],
  ["✦", "AI Planner"], ["⌁", "Analytics"], ["▤", "Reports"], ["⎔", "Integrations"], ["⚙", "Settings"],
];

async function json<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, { ...options, headers: { "Content-Type": "application/json", ...(options?.headers || {}) } });
  if (!response.ok) throw new Error(`Request failed: ${response.status}`);
  return response.json() as Promise<T>;
}

function riskTone(status: string) {
  if (status === "CRITICAL" || status === "NOT_READY" || status === "OVERLOADED") return "red";
  if (status === "ATTENTION" || status === "READY_WITH_RISK" || status === "HIGH") return "amber";
  return "green";
}

function healthTone(score: number) {
  if (score < 55) return "red";
  if (score < 75) return "amber";
  return "green";
}

function healthLabel(score: number) {
  if (score < 55) return "Критично";
  if (score < 75) return "Требует внимания";
  return "В норме";
}

function shortRole(role: string) {
  if (role === "Не указана") return "POSITION NEEDED";
  return role.replace(" Engineering", "");
}

function Panel({ title, kicker, action, children, className = "", id }: { title: string; kicker?: string; action?: ReactNode; children: ReactNode; className?: string; id?: string }) {
  return (
    <section className={`panel ${className}`} id={id}>
      <header className="panel-title">
        <div><span className="title-mark">◈</span><strong>{title}</strong>{kicker && <small>{kicker}</small>}</div>
        {action}
      </header>
      {children}
    </section>
  );
}

function Sparkline({ tone = "cyan" }: { tone?: string }) {
  const bars = [24, 31, 27, 42, 36, 54, 48, 63, 55, 72, 66, 81];
  return <span className={`sparkline ${tone}`}>{bars.map((height, index) => <i key={index} style={{ height: `${height}%` }} />)}</span>;
}

function Gauge({ value, label }: { value: number; label: string }) {
  return (
    <div className="gauge" style={{ "--score": `${value * 3.6}deg` } as React.CSSProperties}>
      <div><strong>{value}</strong><small>/100</small><span>{label}</span></div>
    </div>
  );
}

function ProjectDropdown({ projects, value, onChange }: { projects: ProjectHealth[]; value: string; onChange: (projectKey: string) => void }) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const selected = projects.find((project) => project.project_key === value) || projects[0];

  useEffect(() => {
    if (!open) return;

    const closeOnOutsideClick = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
        triggerRef.current?.focus();
      }
    };
    const frame = window.requestAnimationFrame(() => {
      menuRef.current?.querySelector<HTMLButtonElement>("[aria-selected='true']")?.focus();
    });

    document.addEventListener("pointerdown", closeOnOutsideClick);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      window.cancelAnimationFrame(frame);
      document.removeEventListener("pointerdown", closeOnOutsideClick);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [open]);

  const moveFocus = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const options = Array.from(menuRef.current?.querySelectorAll<HTMLButtonElement>("[role='option']") || []);
    if (!options.length) return;
    const current = Math.max(0, options.indexOf(document.activeElement as HTMLButtonElement));
    let next = current;
    if (event.key === "ArrowDown") next = (current + 1) % options.length;
    else if (event.key === "ArrowUp") next = (current - 1 + options.length) % options.length;
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = options.length - 1;
    else return;
    event.preventDefault();
    options[next]?.focus();
  };

  return (
    <div className={`project-switcher${open ? " open" : ""}`} ref={rootRef}>
      <span id="project-focus-label">PROJECT FOCUS</span>
      <button
        ref={triggerRef}
        type="button"
        className="project-dropdown-trigger"
        aria-label={`Project focus: ${selected ? `${selected.project} · ${selected.project_key}` : "загрузка проектов"}`}
        aria-haspopup="listbox"
        aria-expanded={open}
        disabled={!selected}
        onClick={() => setOpen((current) => !current)}
        onKeyDown={(event) => {
          if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            setOpen(true);
          }
        }}
      >
        {selected ? (
          <>
            <span className={`project-menu-avatar ${riskTone(selected.status)}`}>{selected.project_key.slice(0, 2)}</span>
            <span className="project-menu-copy"><strong>{selected.project}</strong><small>{selected.project_key}</small></span>
          </>
        ) : <span className="project-menu-copy"><strong>Загрузка проектов…</strong></span>}
        <span className="project-menu-chevron" aria-hidden="true">⌄</span>
      </button>

      {open && (
        <div className="project-dropdown-menu" ref={menuRef} role="listbox" aria-labelledby="project-focus-label" onKeyDown={moveFocus}>
          {projects.map((project) => {
            const isSelected = project.project_key === value;
            return (
              <button
                type="button"
                role="option"
                aria-selected={isSelected}
                key={project.project_key}
                className={`project-dropdown-option${isSelected ? " selected" : ""}`}
                onClick={() => {
                  onChange(project.project_key);
                  setOpen(false);
                  window.requestAnimationFrame(() => triggerRef.current?.focus());
                }}
              >
                <span className={`project-menu-avatar ${riskTone(project.status)}`}>{project.project_key.slice(0, 2)}</span>
                <span className="project-menu-copy"><strong>{project.project}</strong><small>{project.project_key} · {project.blocked} blocked</small></span>
                <span className={`project-menu-state ${riskTone(project.status)}`} aria-label={project.status}>{isSelected ? "✓" : "●"}</span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

export default function Home() {
  const [data, setData] = useState<DashboardData>(emptyDashboard);
  const [selectedProject, setSelectedProject] = useState("KIN");
  const [activeNav, setActiveNav] = useState("Command");
  const [connected, setConnected] = useState(false);
  const [loading, setLoading] = useState(true);
  const [modal, setModal] = useState<ModalName>(null);
  const [chatOpen, setChatOpen] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [clock, setClock] = useState<ClockValue>({ date: "—", time: "--:--" });

  const loadDashboard = useCallback(async (project: string) => {
    setLoading(true);
    setLoadError(null);
    setData((current) => ({
      ...current,
      sprint: { ...emptyDashboard.sprint, name: `Загрузка ${project}…` },
      people: [],
      stuck: [],
      release: emptyDashboard.release,
      anomalies: emptyDashboard.anomalies,
      delivery: emptyDashboard.delivery,
      weekly: emptyDashboard.weekly,
    }));
    const results = await Promise.allSettled([
        json<{ portfolio: DashboardData["portfolio"]; attention: ProjectHealth[]; recommendations: string[] }>(`${API}/briefing`),
        json<Sprint>(`${API}/projects/${project}/sprint`),
        json<Person[]>(`${API}/people?project=${project}`),
        json<Person[]>(`${API}/people`),
        json<Task[]>(`${API}/projects/${project}/stuck?minDays=3`),
        json<DashboardData["release"]>(`${API}/projects/${project}/release-readiness`),
        json<DashboardData["anomalies"]>(`${API}/projects/${project}/anomalies?periodDays=7`),
        json<Delivery>(`${API}/projects/${project}/delivery-management`),
        json<Record<string, unknown>>(`${API}/projects/${project}/weekly-review`),
        json<DashboardData["planner"]>(`${API}/resource-plan?period=next_week`),
    ]);
    const [briefing, sprint, people, allPeople, stuck, release, anomalies, delivery, weekly, planner] = results;
    setData((current) => ({
      portfolio: briefing.status === "fulfilled" ? briefing.value.portfolio : current.portfolio,
      attention: briefing.status === "fulfilled" ? briefing.value.attention : current.attention,
      recommendations: briefing.status === "fulfilled" ? briefing.value.recommendations : current.recommendations,
      sprint: sprint.status === "fulfilled" ? sprint.value : emptyDashboard.sprint,
      people: people.status === "fulfilled" ? people.value : [],
      all_people: allPeople.status === "fulfilled" ? allPeople.value : current.all_people,
      stuck: stuck.status === "fulfilled" ? stuck.value : [],
      release: release.status === "fulfilled" ? release.value : emptyDashboard.release,
      anomalies: anomalies.status === "fulfilled" ? anomalies.value : emptyDashboard.anomalies,
      delivery: delivery.status === "fulfilled" ? delivery.value : emptyDashboard.delivery,
      weekly: weekly.status === "fulfilled" ? weekly.value : emptyDashboard.weekly,
      planner: planner.status === "fulfilled" ? planner.value : current.planner,
    }));
    const moduleNames = ["briefing", "sprint", "people", "people directory", "stuck", "release", "anomalies", "delivery", "weekly", "planner"];
    const failed = results.flatMap((result, index) => result.status === "rejected" ? [moduleNames[index]] : []);
    setConnected(failed.length === 0);
    setLoadError(failed.length ? `Не загружены модули: ${failed.join(", ")}. Повторите синхронизацию.` : null);
    setLoading(false);
  }, []);

  const updateEmployeePosition = useCallback(async (employee: string, position: string) => {
    await json(`${API}/people/position`, {
      method: "PUT",
      body: JSON.stringify({ employee, position }),
    });
    const applyPosition = (person: Person): Person => person.employee === employee
      ? { ...person, role: position, role_source: "DIRECTORY" }
      : person;
    setData((current) => ({
      ...current,
      people: current.people.map(applyPosition),
      all_people: current.all_people.map(applyPosition),
    }));
  }, []);

  useEffect(() => {
    const frame = window.requestAnimationFrame(() => { void loadDashboard(selectedProject); });
    return () => window.cancelAnimationFrame(frame);
  }, [loadDashboard, selectedProject]);
  useEffect(() => {
    const updateClock = () => {
      const now = new Date();
      setClock({
        date: now.toLocaleDateString("ru-RU", { weekday: "short", day: "2-digit", month: "short", year: "numeric" }),
        time: now.toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" }),
      });
    };
    updateClock();
    const timer = window.setInterval(updateClock, 30_000);
    return () => window.clearInterval(timer);
  }, []);

  const selectedHealth = useMemo(
    () => data.portfolio.projects.find((project) => project.project_key === selectedProject) || data.portfolio.projects[0],
    [data.portfolio.projects, selectedProject],
  );

  const scrollTo = (label: string) => {
    const ids: Record<string, string> = { Portfolio: "portfolio-health", Projects: "project-health", People: "people-load", "AI Planner": "resource-planner", Analytics: "analytics", Reports: "weekly", Integrations: "system-status" };
    document.getElementById(ids[label] || "command")?.scrollIntoView({ behavior: "smooth", block: label === "Command" ? "start" : "center" });
  };

  const navigate = (label: string) => {
    setActiveNav(label);
    if (label === "People") return setModal("people");
    if (label === "AI Planner") return setModal("planner");
    if (label === "Reports") return setModal("weekly");
    if (label === "Integrations") return setModal("integrations");
    if (label === "Settings") return setModal("settings");
    scrollTo(label);
  };

  return (
    <main className="command-shell" id="command">
      <div className="space-noise" />
      <Topbar clock={clock} connected={connected} loading={loading} onChat={() => setChatOpen(true)} />
      <aside className="sidebar" aria-label="Основная навигация">
        {navItems.map(([icon, label]) => (
          <button key={label} className={activeNav === label ? "active" : ""} onClick={() => navigate(label)} aria-label={label} aria-current={activeNav === label ? "page" : undefined}>
            <span>{icon}</span><small>{label}</small>
          </button>
        ))}
      </aside>

      <div className="dashboard">
        <div className="dashboard-heading">
          <div><span className="eyebrow">A.R.C. / LIVE OPERATIONS</span><h1>COMMAND CENTER</h1></div>
          <ProjectDropdown projects={data.portfolio.projects} value={selectedProject} onChange={setSelectedProject} />
        </div>

        {loadError && <div className="load-error" role="alert"><span>△ {loadError}</span><button onClick={() => loadDashboard(selectedProject)}>RETRY</button></div>}

        <div className="primary-grid">
          <Panel title="MORNING BRIEFING" kicker="LIVE FROM JIRA" className="briefing-panel">
            <p className="briefing-copy">Доброе утро. Вот что требует управленческого внимания по всему портфелю.</p>
            <div className="briefing-metrics">
              <Metric value={data.portfolio.critical_projects} label="Critical projects" tone="red" delta="фокус сегодня" />
              <Metric value={data.portfolio.at_risk_projects} label="At risk" tone="amber" delta="требуют решения" />
              <Metric value={data.portfolio.projects.length} label="Active projects" tone="cyan" delta="в контуре" />
              <Metric value={`${Math.max(0, 100 - data.portfolio.at_risk_projects * 5)}%`} label="Team focus" tone="green" delta="portfolio signal" />
            </div>
            <div className="ai-highlight">
              <div className="holo-orb"><i /><i /><i /></div>
              <div><span>PRIORITY SIGNAL</span><p>{data.recommendations[0] || "Критических сигналов не обнаружено."}</p></div>
            </div>
          </Panel>

          <section
            className="core-panel"
            id="portfolio-health"
            aria-label="Portfolio health"
            style={{ "--health-angle": `${data.portfolio.health_score * 3.6}deg` } as React.CSSProperties}
          >
            <div className="cosmic-field" aria-hidden="true" />
            <div className="cosmic-particles" aria-hidden="true"><i /><i /><i /><i /><i /><i /><i /><i /><i /><i /><i /><i /></div>
            <div className="energy-sweep" aria-hidden="true" />
            <div className="plasma-ripple ripple-a" aria-hidden="true" />
            <div className="plasma-ripple ripple-b" aria-hidden="true" />
            <div className="core-crosshair" aria-hidden="true" />
            <div className="orbit orbit-a" aria-hidden="true" />
            <div className="orbit orbit-telemetry" aria-hidden="true" />
            <div className="orbit orbit-b" aria-hidden="true" />
            <div className="orbit orbit-flare" aria-hidden="true" />
            <div className="orbit orbit-energy" aria-hidden="true" />
            <div className="orbit orbit-c" aria-hidden="true" />
            <div className="core-beacons" aria-hidden="true"><i /><i /><i /><i /><i /><i /></div>
            <div className="core-score">
              <span>A.R.C.</span>
              <small>PORTFOLIO HEALTH</small>
              <div className="core-value"><strong>{data.portfolio.health_score}</strong><em>/100</em></div>
              <p className={riskTone(data.portfolio.health_score < 55 ? "CRITICAL" : data.portfolio.health_score < 75 ? "ATTENTION" : "ON_TRACK")}>
                <b>▲</b> {data.portfolio.health_score < 75 ? "ATTENTION REQUIRED" : "SYSTEM NOMINAL"}
              </p>
            </div>
            <div className="core-caption"><span>PORTFOLIO OVERVIEW</span><Sparkline /><b>{data.portfolio.projects.length} PROJECTS · LIVE</b></div>
          </section>

          <Panel title="PROJECT HEALTH" kicker={`${data.portfolio.projects.length} PROJECTS`} className="project-panel" id="project-health">
            <div className="status-legend"><span className="red">● {data.portfolio.critical_projects} Critical</span><span className="amber">● {data.portfolio.at_risk_projects} At risk</span><span className="green">● {data.portfolio.healthy_projects} On track</span></div>
            <div className="project-list">
              {data.portfolio.projects.slice(0, 6).map((project) => (
                <button key={project.project_key} className={project.project_key === selectedProject ? "selected" : ""} onClick={() => setSelectedProject(project.project_key)}>
                  <span className={`project-avatar ${riskTone(project.status)}`}>{project.project_key.slice(0, 2)}</span>
                  <span className="project-name"><strong>{project.project}</strong><small>{project.project_key} · {project.blocked} blocked</small></span>
                  <b className={riskTone(project.status)}>{project.health_score}</b><Sparkline tone={riskTone(project.status)} /><em className={riskTone(project.status)}>● {project.status.replace("_", " ")}</em>
                </button>
              ))}
            </div>
          </Panel>
        </div>

        <div className="secondary-grid">
          <Panel title="PEOPLE LOAD" kicker={`${data.people.length} IN ${selectedProject} · ${data.all_people.length} TOTAL`} className="people-panel" id="people-load" action={<button className="text-action" onClick={() => setModal("people")}>View all</button>}>
            <div className="people-strip">
              {data.people.slice(0, 5).map((person) => (
                <article key={person.jira_username || person.employee}>
                  <div className={`person-avatar ${riskTone(person.status)}`}>{person.employee.split(" ").map((part) => part[0]).slice(0, 2).join("")}</div>
                  <strong>{person.employee.split(" ")[0]}</strong><small>{shortRole(person.role)}</small>
                  <span className="person-projects">{person.projects?.map((project) => project.project).join(" · ") || selectedProject}</span>
                  <b className={riskTone(person.status)}>{person.load_score}%</b><Sparkline tone={riskTone(person.status)} /><em className={riskTone(person.status)}>● {person.status}</em>
                </article>
              ))}
            </div>
          </Panel>

          <Panel title={`SPRINT HEALTH · ${selectedProject}`} kicker={data.sprint.name} className="sprint-panel">
            <div className="sprint-stats">
              <Metric value={data.sprint.total} label="Total" tone="cyan" />
              <Metric value={data.sprint.blocked} label="Blocked" tone="red" />
              <Metric value={data.sprint.in_progress} label="In progress" tone="cyan" />
              <Metric value={data.sprint.test} label="In test" tone="cyan" />
              <Metric value={data.sprint.done_dev} label="Done dev" tone="green" />
              <Metric value={data.sprint.done_prod + data.sprint.done} label="Done prod" tone="green" />
            </div>
            <div className="progress-track"><i className="red" style={{ width: `${Math.max(3, data.sprint.blocked / Math.max(1, data.sprint.total) * 100)}%` }} /><i className="amber" style={{ width: `${data.sprint.test / Math.max(1, data.sprint.total) * 100}%` }} /><i className="cyan" style={{ width: `${data.sprint.done_dev / Math.max(1, data.sprint.total) * 100}%` }} /><i className="green" style={{ width: `${data.sprint.progress_percent}%` }} /></div>
            <span className="progress-label">SPRINT HEALTH <b>{data.sprint.health_score}/100</b></span>
          </Panel>
        </div>

        <div className="module-grid" id="analytics">
          <Panel title="STUCK TASKS" kicker={`${data.stuck.length} DETECTED`} className="module stuck-card" action={<span className="alert-dot">!</span>}>
            <div className="task-list">{data.stuck.slice(0, 4).map((task) => <a key={task.key} href={jiraTaskUrl(task.key)} target="_blank" rel="noreferrer" title={`Открыть ${task.key} в Jira`}><b>{task.key}</b><span>{task.summary}</span><small>{task.assignee.split(" ")[0]}</small><em>{task.stuck_days ?? 3}d ↗</em></a>)}</div>
            <CardAction onClick={() => setModal("stuck")}>View stuck tasks</CardAction>
          </Panel>

          <Panel title="WHAT-IF SIMULATOR" kicker="SCENARIO ENGINE" className="module">
            <div className="comparison"><span>Current health<strong>{selectedHealth?.health_score ?? 0}%</strong></span><i>→</i><span>Simulated<strong className="green">+8 pts</strong></span></div>
            <ul className="fact-list"><li>Delivery predictability <b>{data.delivery.delivery_predictability}%</b></li><li>Team utilization <b>{data.people[0]?.load_score ?? 0}%</b></li><li>Scenario mode <b className="cyan">NO WRITE</b></li></ul>
            <CardAction onClick={() => setModal("simulation")}>Run new simulation</CardAction>
          </Panel>

          <Panel title="AI RESOURCE PLANNER" kicker="TOP RECOMMENDATIONS" className="module" id="resource-planner">
            <ol className="recommendation-list">{data.planner.recommendations.slice(0, 3).map((item, index) => <li key={`${item.employee}-${index}`}><b>{index + 1}</b><span>{item.employee} → {item.to_project}<small>{item.capacity_percent}% capacity · manager review</small></span></li>)}</ol>
            <CardAction onClick={() => setModal("planner")}>Open planner</CardAction>
          </Panel>

          <Panel title="WEEKLY REVIEW" kicker="GENERATOR" className="module" id="weekly">
            <div className="holo-cube"><i /><i /><i /></div>
            <p className="module-copy">{data.weekly.baseline_available ? (data.weekly.baseline_source === "JIRA_CHANGELOG" ? "Сравнение восстановлено из фактической истории переходов Jira." : "Отчёт сравнивает два фактических snapshots.") : "Историю Jira пока не удалось получить для выбранного периода."}</p>
            <div className="mini-progress"><i style={{ width: `${Math.max(0, Math.min(100, Number(data.weekly.history_coverage_percent ?? (data.weekly.baseline_available ? 100 : 0))))}%` }} /></div>
            <CardAction onClick={() => setModal("weekly")}>Generate preview</CardAction>
          </Panel>

          <Panel title="ANOMALY DETECTOR" kicker={`${data.anomalies.anomalies.length} SIGNALS`} className="module">
            <div className="anomaly-list">{data.anomalies.anomalies.length ? data.anomalies.anomalies.slice(0, 3).map((item) => <div key={item.metric}><span>△</span><p>{item.metric}<small>{item.change_percent > 0 ? "+" : ""}{item.change_percent}% за период</small></p></div>) : data.anomalies.baseline_available ? <div><span>◇</span><p>No material anomalies<small>{data.anomalies.message}</small></p></div> : <div><span>◇</span><p>History unavailable<small>{data.anomalies.message}</small></p></div>}</div>
            <CardAction onClick={() => setModal("anomalies")}>View anomalies</CardAction>
          </Panel>

          <Panel title="DELIVERY MANAGEMENT" kicker="OBJECTIVE SIGNALS" className="module">
            <div className="delivery-layout"><Gauge value={data.delivery.delivery_predictability} label="Predictability" /><ul className="delivery-list"><li>Planning <b>{data.delivery.planning_stability}</b></li><li>Scope control <b>{data.delivery.scope_control}</b></li><li>Blockers <b>{data.delivery.blocker_resolution}</b></li><li>Task hygiene <b>{data.delivery.task_hygiene}</b></li></ul></div>
            <CardAction onClick={() => setModal("delivery")}>View full analysis</CardAction>
          </Panel>

          <Panel title="RELEASE READINESS" kicker={selectedProject} className="module">
            <div className="release-score"><Gauge value={data.release.score} label={data.release.status.replaceAll("_", " ")} /><div><p><span>Blocked</span><b className="red">{data.release.blocked}</b></p><p><span>In test</span><b className="amber">{data.release.testing}</b></p><p><span>Critical bugs</span><b className="red">{data.release.critical_bugs}</b></p></div></div>
            <CardAction onClick={() => setModal("release")}>View release board</CardAction>
          </Panel>
        </div>

        <footer className="status-stream" id="system-status">
          <span><small>DATA STREAM</small><b className={connected ? "green" : "amber"}>● {connected ? "LIVE" : "PREVIEW"}</b></span>
          <span><small>PROJECTS</small><b>{data.portfolio.projects.length}</b></span>
          <span><small>PEOPLE IN PORTFOLIO</small><b>{data.all_people.length}</b></span>
          <span><small>ACTIVE TASKS</small><b>{data.people.reduce((total, person) => total + person.active_tasks, 0)}</b></span>
          <span><small>COMPLETION RATE</small><b>{data.sprint.progress_percent}%</b></span>
          <span className="stream-core">◉</span>
          <span><small>AI INSIGHTS</small><b>{data.recommendations.length}</b></span>
          <span><small>PREDICTED RISKS</small><b>{data.portfolio.critical_projects + data.portfolio.at_risk_projects}</b></span>
          <span><small>LAST SYNC</small><b>{clock.time}</b></span>
        </footer>
      </div>

      {modal && <ModuleModal name={modal} data={data} selectedProject={selectedProject} connected={connected} onProjectChange={setSelectedProject} onPositionChange={updateEmployeePosition} onRefresh={() => loadDashboard(selectedProject)} onClose={() => setModal(null)} />}
      {chatOpen && <ChatDrawer onClose={() => setChatOpen(false)} />}
    </main>
  );
}

function Topbar({ clock, connected, loading, onChat }: { clock: ClockValue; connected: boolean; loading: boolean; onChat: () => void }) {
  return (
    <header className="topbar">
      <div className="brand"><span className="brand-mark">A</span><div><strong>A.R.C.</strong><small>AI RESOURCE COMMAND</small></div></div>
      <div className="top-center"><span>{clock.date}</span><i /> <span>{clock.time}</span><i /><b className={connected ? "green" : "amber"}>SYSTEM STATUS&nbsp; ● {loading ? "SYNCING" : connected ? "NOMINAL" : "PREVIEW"}</b></div>
      <div className="top-actions"><button aria-label="Поиск">⌕</button><button aria-label="Уведомления">♢<em>3</em></button><button onClick={onChat} aria-label="Открыть Ask A.R.C.">◉<span>ASK A.R.C.</span></button><div className="user"><b>CTO</b><small>Command mode</small></div></div>
    </header>
  );
}

function Metric({ value, label, tone, delta }: { value: number | string; label: string; tone: string; delta?: string }) {
  return <div className="metric"><b className={tone}>{value}</b><span>{label}</span>{delta && <small className={tone}>▲ {delta}</small>}</div>;
}

function CardAction({ children, onClick }: { children: ReactNode; onClick: () => void }) {
  return <button className="card-action" onClick={onClick}>{children}<span>→</span></button>;
}

function ModuleModal({ name, data, selectedProject, connected, onProjectChange, onPositionChange, onRefresh, onClose }: { name: Exclude<ModalName, null>; data: DashboardData; selectedProject: string; connected: boolean; onProjectChange: (project: string) => void; onPositionChange: (employee: string, position: string) => Promise<void>; onRefresh: () => void; onClose: () => void }) {
  const [employee, setEmployee] = useState(data.people[0]?.employee || "");
  const [toProject, setToProject] = useState(data.portfolio.projects.find((project) => project.project_key !== selectedProject)?.project_key || "BEK");
  const [capacity, setCapacity] = useState(30);
  const [simulation, setSimulation] = useState<SimulationResultData | null>(null);
  const [simulationError, setSimulationError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const titles: Record<Exclude<ModalName, null>, string> = { people: "People directory", stuck: "Stuck tasks", simulation: "What-if simulator", planner: "AI Resource Planner", weekly: "Weekly review", anomalies: "Anomaly detector", delivery: "Delivery Management", release: "Release readiness", integrations: "System integrations", settings: "Command settings" };

  useEffect(() => {
    setEmployee((current) => data.people.some((person) => person.employee === current) ? current : data.people[0]?.employee || "");
  }, [data.people]);

  const runSimulation = async (event: FormEvent) => {
    event.preventDefault(); setBusy(true); setSimulationError(null);
    try {
      const result = await json<SimulationResultData>(`${API}/simulations/resource-move`, { method: "POST", body: JSON.stringify({ employee, from_project: selectedProject, to_project: toProject, capacity_percent: capacity }) });
      setSimulation(result);
    } catch {
      setSimulation(null);
      setSimulationError("Сценарий не рассчитан: Analytics Engine недоступен.");
    } finally { setBusy(false); }
  };

  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <section className={`module-modal ${name === "people" ? "people-directory-modal" : name === "simulation" ? "simulation-modal" : ""}`} role="dialog" aria-modal="true" aria-label={titles[name]}>
        <header><div><span>A.R.C. MODULE</span><h2>{titles[name]}</h2></div><button onClick={onClose} aria-label="Закрыть">×</button></header>
        {name === "people" && <PeopleDirectory people={data.all_people.length ? data.all_people : data.people} projects={data.portfolio.projects} selectedProject={selectedProject} onPositionChange={onPositionChange} />}
        {name === "stuck" && <div className="modal-table">{data.stuck.map((task) => <a key={task.key} href={jiraTaskUrl(task.key)} target="_blank" rel="noreferrer" title={`Открыть ${task.key} в Jira`}><b>{task.key}</b><div><strong>{task.summary}</strong><small>{task.assignee} · {task.status}</small></div><em>{task.stuck_days ?? 3} days ↗</em></a>)}</div>}
        {name === "simulation" && <div className={`simulation-body${simulation ? " has-result" : ""}`}>
          <form className="simulation-form" onSubmit={runSimulation}>
            <div className="simulation-form-note"><span>◇ СЦЕНАРНЫЙ РЕЖИМ</span><p>Проверка управленческого решения без изменений в Jira.</p></div>
            <label>Сотрудник<select value={employee} onChange={(event) => { setEmployee(event.target.value); setSimulation(null); }} required>{data.people.map((person) => <option key={person.employee}>{person.employee}</option>)}</select></label>
            <label>Из проекта<input value={selectedProject} readOnly /></label>
            <label>В проект<select value={toProject} onChange={(event) => { setToProject(event.target.value); setSimulation(null); }} required>{data.portfolio.projects.filter((project) => project.project_key !== selectedProject).map((project) => <option value={project.project_key} key={project.project_key}>{project.project} · {project.project_key}</option>)}</select></label>
            <label>Доля рабочего времени <b>{capacity}%</b><input type="range" min="10" max="100" step="10" value={capacity} onChange={(event) => { setCapacity(Number(event.target.value)); setSimulation(null); }} /></label>
            <button type="submit" disabled={busy || !employee || !toProject}>{busy ? "РАССЧИТЫВАЮ…" : "РАССЧИТАТЬ СЦЕНАРИЙ"}</button>
            {simulationError && <p className="form-error">{simulationError}</p>}
          </form>
          {simulation && <SimulationResult value={simulation} fromProject={selectedProject} toProject={toProject} projects={data.portfolio.projects} />}
        </div>}
        {name === "planner" && <div className="modal-recommendations">{data.planner.recommendations.map((item, index) => <article key={index}><b>0{index + 1}</b><div><strong>{item.employee} → {item.to_project}</strong><p>{item.reason}</p></div><em>{item.capacity_percent}%</em></article>)}</div>}
        {name === "weekly" && <WeeklyReviewReport value={data.weekly} />}
        {name === "anomalies" && <AnomalyReport value={data.anomalies} />}
        {name === "delivery" && <div className="modal-score-grid">{Object.entries(data.delivery).filter(([, value]) => typeof value === "number").map(([key, value]) => <article key={key}><span>{key.replaceAll("_", " ")}</span><b>{value}</b><i><em style={{ width: `${value}%` }} /></i></article>)}</div>}
        {name === "release" && <div className="release-modal"><Gauge value={data.release.score} label={data.release.status} /><JsonFacts value={data.release} /></div>}
        {name === "integrations" && <div className="integration-grid"><article><span>JIRA DATA STREAM</span><b className={connected ? "green" : "amber"}>● {connected ? "CONNECTED" : "SYNC REQUIRED"}</b><small>Проекты, задачи, статусы и исполнители</small></article><article><span>ANALYTICS ENGINE</span><b className="green">● ACTIVE</b><small>Детерминированные расчёты без LLM</small></article><article><span>POSTGRES SNAPSHOTS</span><b className="green">● ACTIVE</b><small>История метрик и weekly baseline</small></article><article><span>NITEC LLM GATEWAY</span><b className="cyan">● OPTIONAL</b><small>Используется только в Ask A.R.C.</small></article></div>}
        {name === "settings" && <div className="settings-panel"><label>PROJECT FOCUS<select value={selectedProject} onChange={(event) => onProjectChange(event.target.value)}>{data.portfolio.projects.map((project) => <option value={project.project_key} key={project.project_key}>{project.project} · {project.project_key}</option>)}</select></label><button onClick={onRefresh}>SYNC WITH JIRA NOW</button><p>Данные на экране читаются из Jira. Изменений в Jira A.R.C. не выполняет.</p></div>}
      </section>
    </div>
  );
}

function SimulationResult({ value, fromProject, toProject, projects }: { value: SimulationResultData; fromProject: string; toProject: string; projects: ProjectHealth[] }) {
  const fromBefore = Number(value.before[fromProject] ?? 0);
  const fromAfter = Number(value.after[fromProject] ?? fromBefore);
  const toBefore = Number(value.before[toProject] ?? 0);
  const toAfter = Number(value.after[toProject] ?? toBefore);
  const fromDelta = fromAfter - fromBefore;
  const toDelta = toAfter - toBefore;
  const criticalBefore = [fromBefore, toBefore].filter((score) => score < 55).length;
  const criticalAfter = [fromAfter, toAfter].filter((score) => score < 55).length;
  const sourceSafe = fromAfter >= 55;
  const targetRecovered = toBefore < 55 && toAfter >= 55;
  const recommended = targetRecovered && sourceSafe;
  const rejected = criticalAfter > criticalBefore || toAfter < 55 || toDelta <= Math.abs(fromDelta);
  const verdictTone = recommended ? "green" : rejected ? "red" : "amber";
  const verdict = recommended ? "РЕКОМЕНДУЕТСЯ" : rejected ? "НЕ РЕКОМЕНДУЕТСЯ" : "ТРЕБУЕТ РЕШЕНИЯ";
  const fromName = projects.find((project) => project.project_key === fromProject)?.project || fromProject;
  const toName = projects.find((project) => project.project_key === toProject)?.project || toProject;
  const explanation = recommended
    ? `${toName} выходит из критической зоны, а ${fromName} сохраняет допустимый уровень здоровья.`
    : rejected
      ? `${toName} не выходит из критической зоны или улучшение цели не компенсирует ухудшение ${fromName}.`
      : `${toName} улучшается, но влияние на ${fromName} требует подтверждения руководителя.`;

  const impactCard = (kind: "source" | "target", project: string, projectKey: string, before: number, after: number, delta: number) => (
    <article className={`simulation-impact ${kind}`}>
      <div className="simulation-impact-head"><span>{kind === "source" ? "ОТДАЁТ РЕСУРС" : "ПОЛУЧАЕТ РЕСУРС"}</span><b>{projectKey}</b></div>
      <h3>{project}</h3>
      <div className="simulation-score-flow"><span><small>СЕЙЧАС</small><strong>{before}</strong></span><i>→</i><span><small>ПОСЛЕ</small><strong className={healthTone(after)}>{after}</strong></span><em className={delta >= 0 ? "green" : "red"}>{delta >= 0 ? "+" : ""}{delta}</em></div>
      <div className="simulation-health-track"><i className={healthTone(after)} style={{ width: `${after}%` }} /></div>
      <p className={healthTone(after)}>● {healthLabel(after)}</p>
    </article>
  );

  return <section className="simulation-result" aria-live="polite">
    <header className={`simulation-verdict ${verdictTone}`}>
      <span>{recommended ? "✓" : rejected ? "!" : "◇"}</span>
      <div><small>ВЫВОД A.R.C.</small><strong>{verdict}</strong><p>{explanation}</p></div>
    </header>
    <div className="simulation-transfer">
      {impactCard("source", fromName, fromProject, fromBefore, fromAfter, fromDelta)}
      <div className="simulation-transfer-arrow"><b>{value.capacity_percent}%</b><span>ВРЕМЕНИ</span><i>→</i></div>
      {impactCard("target", toName, toProject, toBefore, toAfter, toDelta)}
    </div>
    <div className="simulation-summary">
      <article><span>СОТРУДНИК</span><b>{value.employee}</b></article>
      <article><span>КРИТИЧЕСКИХ ПРОЕКТОВ</span><b className={criticalAfter < criticalBefore ? "green" : criticalAfter > criticalBefore ? "red" : "amber"}>{criticalBefore} → {criticalAfter}</b></article>
      <article><span>ИЗМЕНЕНИЕ БАЛАНСА</span><b className={toDelta + fromDelta >= 0 ? "green" : "red"}>{toDelta + fromDelta >= 0 ? "+" : ""}{toDelta + fromDelta} пунктов</b></article>
    </div>
    <div className="simulation-disclaimer"><b>◇ Важно</b><p>Это модельный расчёт влияния на Health Score, а не прогноз сроков. Задачи, исполнители и проценты в Jira не изменяются.</p></div>
  </section>;
}

function WeeklyReviewReport({ value }: { value: Record<string, unknown> }) {
  if (!value.baseline_available) return <div className="history-empty"><b>◇</b><h3>История недоступна</h3><p>{String(value.message || "Jira не вернула историю за выбранный период.")}</p></div>;
  const number = (key: string) => Number(value[key] || 0);
  const comparisons = [
    { label: "Здоровье проекта", before: number("health_before"), now: number("health_now"), unit: "/100" },
    { label: "Заблокировано", before: number("blocked_before"), now: number("blocked_now"), unit: "" },
    { label: "В тестировании", before: number("test_before"), now: number("test_now"), unit: "" },
  ];
  return <div className="history-report">
    <div className="history-source"><span>ИСТОЧНИК</span><b>{value.baseline_source === "JIRA_CHANGELOG" ? "JIRA CHANGELOG" : "A.R.C. SNAPSHOT"}</b><small>Срез: {new Date(String(value.baseline_at)).toLocaleString("ru-RU")} · покрытие {number("history_coverage_percent")}%</small></div>
    <div className="history-kpis"><article><span>ЗАВЕРШЕНО</span><b>{number("completed")}</b><small>за 7 дней</small></article><article><span>НОВЫЕ ЗАДАЧИ</span><b>{number("new_tasks")}</b><small>за 7 дней</small></article></div>
    <div className="history-comparisons">{comparisons.map((item) => {
      const delta = item.now - item.before;
      const improved = item.label === "Здоровье проекта" ? delta > 0 : delta < 0;
      return <article key={item.label}><span>{item.label}</span><b>{item.before}{item.unit}</b><i>→</i><strong>{item.now}{item.unit}</strong><em className={delta === 0 ? "cyan" : improved ? "green" : "amber"}>{delta > 0 ? "+" : ""}{delta}</em></article>;
    })}</div>
  </div>;
}

function AnomalyReport({ value }: { value: DashboardData["anomalies"] }) {
  if (!value.baseline_available) return <div className="history-empty"><b>◇</b><h3>История недоступна</h3><p>{value.message}</p></div>;
  const labels: Record<string, string> = { testing: "Задачи в тестировании", blocked: "Заблокированные задачи", health_score: "Здоровье проекта" };
  return <div className="history-report">
    <div className="history-source"><span>ИСТОЧНИК</span><b>{value.baseline_source === "JIRA_CHANGELOG" ? "JIRA CHANGELOG" : "A.R.C. SNAPSHOT"}</b><small>Покрытие истории {value.history_coverage_percent ?? 100}% · период 7 дней</small></div>
    {value.anomalies.length ? <div className="anomaly-report-list">{value.anomalies.map((item) => {
      const improved = item.metric === "health_score" ? item.delta > 0 : item.delta < 0;
      return <article key={item.metric}>
      <div className={`anomaly-severity ${item.severity.toLowerCase()}`}>△</div>
      <div><strong>{labels[item.metric] || item.metric}</strong><small>{improved ? "Позитивное изменение" : item.severity === "HIGH" ? "Сильное отклонение" : "Требует внимания"}</small></div>
      <span><small>БЫЛО</small><b>{item.before}</b></span><i>→</i><span><small>СЕЙЧАС</small><b>{item.now}</b></span>
      <em className={improved ? "green" : "amber"}>{item.change_percent > 0 ? "+" : ""}{item.change_percent}%</em>
    </article>})}</div> : <div className="history-empty compact"><b>◇</b><h3>Существенных отклонений нет</h3><p>{value.message}</p></div>}
  </div>;
}

function PeopleDirectory({ people, projects, selectedProject, onPositionChange }: { people: Person[]; projects: ProjectHealth[]; selectedProject: string; onPositionChange: (employee: string, position: string) => Promise<void> }) {
  const [query, setQuery] = useState("");
  const [projectFilter, setProjectFilter] = useState(selectedProject);
  const [positionFilter, setPositionFilter] = useState("ALL");
  const [saving, setSaving] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const projectNames = useMemo(() => new Map(projects.map((project) => [project.project_key, project.project])), [projects]);
  const positions = useMemo(() => Array.from(new Set([...POSITION_OPTIONS, ...people.map((person) => person.role)])).filter(Boolean), [people]);
  const filteredPeople = useMemo(() => {
    const normalizedQuery = query.trim().toLocaleLowerCase("ru-RU");
    return people.filter((person) => {
      const matchesQuery = !normalizedQuery || person.employee.toLocaleLowerCase("ru-RU").includes(normalizedQuery);
      const matchesProject = projectFilter === "ALL" || person.projects?.some((project) => project.project === projectFilter);
      const matchesPosition = positionFilter === "ALL" || person.role === positionFilter;
      return matchesQuery && matchesProject && matchesPosition;
    });
  }, [people, positionFilter, projectFilter, query]);
  const withoutPosition = people.filter((person) => person.role === "Не указана").length;
  const multiProject = people.filter((person) => (person.projects?.length || 0) > 1).length;

  const savePosition = async (employee: string, position: string) => {
    setSaving(employee);
    setSaveError(null);
    try {
      await onPositionChange(employee, position);
    } catch {
      setSaveError(`Не удалось сохранить позицию для ${employee}. Проверьте локальную базу и повторите.`);
    } finally {
      setSaving(null);
    }
  };

  return (
    <div className="people-directory">
      <div className="people-directory-summary">
        <div><small>ВСЕГО В JIRA</small><strong>{people.length}</strong><span>активных пользователей</span></div>
        <div><small>НЕСКОЛЬКО ПРОЕКТОВ</small><strong>{multiProject}</strong><span>контекстов одновременно</span></div>
        <div className={withoutPosition ? "amber" : "green"}><small>ПОЗИЦИЯ НЕ УКАЗАНА</small><strong>{withoutPosition}</strong><span>требуют заполнения</span></div>
      </div>
      <div className="people-directory-toolbar">
        <label className="people-search"><span>ПОИСК</span><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Имя сотрудника…" /></label>
        <label><span>ПРОЕКТ</span><select value={projectFilter} onChange={(event) => setProjectFilter(event.target.value)}><option value="ALL">Все проекты</option>{projects.map((project) => <option key={project.project_key} value={project.project_key}>{project.project} · {project.project_key}</option>)}</select></label>
        <label><span>ПОЗИЦИЯ</span><select value={positionFilter} onChange={(event) => setPositionFilter(event.target.value)}><option value="ALL">Все позиции</option>{positions.map((position) => <option key={position}>{position}</option>)}</select></label>
        <div className="people-result-count"><b>{filteredPeople.length}</b><span>показано</span></div>
      </div>
      {saveError && <div className="directory-error" role="alert">△ {saveError}</div>}
      <div className="people-directory-head" aria-hidden="true"><span>СОТРУДНИК</span><span>ПОЗИЦИЯ</span><span>ЗАКРЕПЛЁН ЗА ПРОЕКТАМИ</span><span>ЗАГРУЗКА</span></div>
      <div className="people-directory-list">
        {filteredPeople.map((person) => (
          <article key={person.jira_username || person.employee} className={person.projects?.some((project) => project.project === selectedProject) ? "in-focus" : ""}>
            <div className="directory-person">
              <div className={`person-avatar ${riskTone(person.status)}`}>{person.employee.split(" ").map((part) => part[0]).slice(0, 2).join("")}</div>
              <div><strong>{person.employee}</strong><span>{person.jira_username ? `@${person.jira_username} · ` : ""}{person.active_tasks} active · {person.testing_tasks || 0} test · {person.done_dev_tasks || 0} done dev · {person.blocked_tasks} blockers</span></div>
            </div>
            <div className="directory-position">
              <select value={person.role} disabled={saving === person.employee} onChange={(event) => void savePosition(person.employee, event.target.value)} aria-label={`Позиция ${person.employee}`}>
                {positions.map((position) => <option key={position}>{position}</option>)}
              </select>
              <small className={person.role === "Не указана" ? "amber" : person.role_source === "DIRECTORY" ? "green" : "cyan"}>{saving === person.employee ? "СОХРАНЕНИЕ…" : person.role_source === "DIRECTORY" ? "СОХРАНЕНО В A.R.C." : person.role === "Не указана" ? "УКАЖИТЕ ВРУЧНУЮ" : "ИЗ JIRA"}</small>
            </div>
            <div className="directory-projects">
              {person.projects?.map((project) => <span key={project.project} className={project.project === selectedProject ? "selected" : ""}><b>{project.project}</b><i>{projectNames.get(project.project) || "Проект"}</i><em>{project.allocation}%</em></span>)}
            </div>
            <div className={`directory-load ${riskTone(person.status)}`}>
              <div><strong>{person.load_score}%</strong><span>{person.status}</span></div>
              <i><em style={{ width: `${person.load_score}%` }} /></i>
              <small>{person.free_capacity}% свободно</small>
            </div>
          </article>
        ))}
        {!filteredPeople.length && <div className="people-empty">Сотрудники по выбранным фильтрам не найдены.</div>}
      </div>
    </div>
  );
}

function JsonFacts({ value, empty }: { value: Record<string, unknown>; empty?: string }) {
  const entries = Object.entries(value).filter(([, item]) => typeof item !== "object");
  return <div className="json-facts">{entries.length ? entries.map(([key, item]) => <article key={key}><span>{key.replaceAll("_", " ")}</span><b>{String(item)}</b></article>) : <p>{empty}</p>}</div>;
}

function ChatDrawer({ onClose }: { onClose: () => void }) {
  const [messages, setMessages] = useState<Array<{ role: "user" | "assistant"; content: string }>>([{ role: "assistant", content: "Я A.R.C. Спросите о проектах, рисках, нагрузке или релизах. Все показатели запрашиваются через защищённые tools." }]);
  const [input, setInput] = useState("");
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const quick = ["Что горит прямо сейчас?", "Что происходит с Kindy?", "Кто перегружен?"];

  const send = async (prompt?: string) => {
    const message = (prompt || input).trim(); if (!message || busy) return;
    setMessages((current) => [...current, { role: "user", content: message }]); setInput(""); setBusy(true);
    try {
      const response = await json<{ conversation_id: string; message: string }>(`${AI_API}/chat`, { method: "POST", body: JSON.stringify({ message, conversation_id: conversationId }) });
      setConversationId(response.conversation_id); setMessages((current) => [...current, { role: "assistant", content: response.message }]);
    } catch {
      setMessages((current) => [...current, { role: "assistant", content: "AI Gateway пока не настроен. Добавьте новый NITEC_LLM_API_KEY в локальный .env. Dashboard и вся фактическая аналитика продолжают работать без LLM." }]);
    } finally { setBusy(false); }
  };

  return <aside className="chat-drawer" aria-label="Ask A.R.C."><header><div><span>◉</span><div><strong>ASK A.R.C.</strong><small>TOOL-GROUNDED AI</small></div></div><button onClick={onClose}>×</button></header><div className="quick-prompts">{quick.map((prompt) => <button key={prompt} onClick={() => send(prompt)}>{prompt}</button>)}</div><div className="chat-messages">{messages.map((message, index) => <article className={message.role} key={index}><span>{message.role === "assistant" ? "A" : "YOU"}</span><p>{message.content}</p></article>)}{busy && <article className="assistant"><span>A</span><p className="typing">Запрашиваю факты через tools…</p></article>}</div><form onSubmit={(event) => { event.preventDefault(); send(); }}><textarea value={input} onChange={(event) => setInput(event.target.value)} placeholder="Спросите о портфеле…" rows={2} /><button type="submit" disabled={busy}>SEND ↗</button></form></aside>;
}
