const stageOrder = ["queued", "analyzing", "notion_ticket", "github_pr", "failed"];
const stageLabels = {
  queued: "Queued",
  analyzing: "Koog Analyzing",
  notion_ticket: "Notion Ticket",
  github_pr: "GitHub PR",
  failed: "Failed"
};

const stageWork = {
  queued: {
    tone: "working",
    kicker: "Junie // working",
    headline: "Intaking crash report and preparing the agent workspace",
    detail: "Queue watcher is collecting context, fingerprinting inputs, and waiting for the next orchestration signal.",
    commands: ["queue.receive(report)", "fingerprint.prepare()", "route.next_step()"]
  },
  analyzing: {
    tone: "debugging",
    kicker: "Junie // debugging",
    headline: "Debugging the stack trace with Koog analysis tools",
    detail: "PII scrub, dedup checks, severity routing, and fix-plan reasoning are running in the background.",
    commands: ["pii.scrub(stacktrace)", "koog.analyze(rootCause)", "guardrails.evaluate()"]
  },
  notion_ticket: {
    tone: "creating",
    kicker: "Junie // creating",
    headline: "Creating a Notion ticket with the crash summary",
    detail: "The agent is packaging severity, reproduction notes, and links into a booth-safe artifact.",
    commands: ["notion.compose(ticket)", "artifact.attach(context)", "booth.publish(link)"]
  },
  github_pr: {
    tone: "creating",
    kicker: "Junie // creating",
    headline: "Creating GitHub artifacts and preparing the fix path",
    detail: "GitHub issue/branch/PR status is being synchronized back into the live booth timeline.",
    commands: ["github.issue.create()", "branch.prepare(fix)", "draft_pr.sync()"]
  },
  failed: {
    tone: "failed",
    kicker: "Junie // stopped",
    headline: "Agent run stopped and needs operator attention",
    detail: "Failure details are kept in the terminal so the operator can choose notify, fallback, or retry.",
    commands: ["error.capture()", "operator.alert()", "fallback.ready()"]
  }
};

const statsEl = document.getElementById("stats");
const timelineEl = document.getElementById("timeline");
const terminalEl = document.getElementById("terminal");
const pipelineEl = document.getElementById("pipeline");
const tickerEl = document.getElementById("ticker");
const heroEl = document.getElementById("hero");
const operatorPanelEl = document.getElementById("operatorPanel");
const pendingDecisionsEl = document.getElementById("pendingDecisions");
const bugGroupsEl = document.getElementById("bugGroups");
const crashOccurrencesEl = document.getElementById("crashOccurrences");
const spotlightTitleEl = document.getElementById("spotlightTitle");
const spotlightMessageEl = document.getElementById("spotlightMessage");

let latestStage = "idle";
let sseWasOpen = false;
let terminalActivityEl = null;

const operatorDecisions = [
  { value: "notion", label: "Notion Ticket" },
  { value: "github", label: "GitHub Issue" },
  { value: "both", label: "Both" },
  { value: "autofix", label: "Autofix" },
  { value: "notify", label: "Notify" },
  { value: "fallback", label: "Fallback" }
];

function renderPipeline(active) {
  pipelineEl.innerHTML = "";
  const activeIndex = stageOrder.indexOf(active);
  stageOrder.forEach((stage, index) => {
    const row = document.createElement("div");
    const state = activeIndex === -1 ? "upcoming" : stage === active ? "active" : index < activeIndex ? "complete" : "upcoming";
    row.className = `pipeline-step ${state}`;
    row.dataset.index = String(index + 1).padStart(2, "0");
    row.dataset.state = state;

    const copy = document.createElement("span");
    copy.className = "pipeline-copy";

    const title = document.createElement("strong");
    title.textContent = stageLabels[stage] || stage;

    const hint = document.createElement("small");
    hint.textContent = stageWork[stage]?.headline || "Waiting for agent signal";

    copy.append(title, hint);

    const badge = document.createElement("span");
    badge.className = "pipeline-badge";
    badge.textContent = state === "complete" ? "done" : state === "active" ? "live" : "next";

    row.append(copy, badge);
    pipelineEl.appendChild(row);
  });
}

function addLine(target, text, level = "info") {
  const line = document.createElement("div");
  line.className = `line ${level}`;
  line.dataset.time = new Date().toLocaleTimeString();
  line.textContent = text;
  target.prepend(line);
  while (target.children.length > 60) {
    target.removeChild(target.lastChild);
  }
}

function shouldShowTerminalActivity(event) {
  if (event.type === "connection" || event.jobId === "booth") return false;
  if (event.level === "error" || event.stage === "failed" || event.status === "FAILED") return false;
  return event.status === "QUEUED" || event.status === "ANALYZING" || event.status === "IN_PROGRESS" || event.type === "progress";
}

function updateTerminalActivity(event) {
  if (!shouldShowTerminalActivity(event)) {
    if (terminalActivityEl) {
      terminalActivityEl.remove();
      terminalActivityEl = null;
    }
    return;
  }

  const work = stageWork[event.stage] || stageWork.queued;
  if (!terminalActivityEl) {
    terminalActivityEl = document.createElement("div");
  }
  terminalActivityEl.className = `terminal-activity is-${work.tone}`;
  terminalEl.prepend(terminalActivityEl);

  const label = stageLabels[event.stage] || event.stage || "Working";
  const message = event.message || work.headline;
  terminalActivityEl.replaceChildren();

  const orb = document.createElement("div");
  orb.className = "activity-orb";
  orb.setAttribute("aria-hidden", "true");

  const copy = document.createElement("div");
  copy.className = "activity-copy";

  const kicker = document.createElement("span");
  kicker.className = "activity-kicker";
  kicker.textContent = work.kicker;

  const strong = document.createElement("strong");
  strong.textContent = `${label}: ${message}`;

  const small = document.createElement("small");
  small.textContent = work.detail;

  const consoleEl = document.createElement("div");
  consoleEl.className = "activity-console";
  work.commands.forEach((command, index) => {
    const chip = document.createElement("code");
    chip.textContent = `junie:${index + 1} $ ${command}`;
    consoleEl.appendChild(chip);
  });

  copy.append(kicker, strong, small, consoleEl);

  const loader = document.createElement("div");
  loader.className = "activity-loader";
  loader.setAttribute("aria-label", "Background work in progress");
  for (let index = 0; index < 4; index += 1) {
    loader.appendChild(document.createElement("span"));
  }

  terminalActivityEl.append(orb, copy, loader);
}

function renderStats(stats) {
  const cards = [
    ["Total", stats.totalJobs],
    ["Queued", stats.queuedJobs],
    ["Completed", stats.completedJobs],
    ["Failed", stats.failedJobs]
  ];

  statsEl.innerHTML = "";
  cards.forEach(([label, value], index) => {
    const card = document.createElement("div");
    card.className = "stat-card";
    card.style.animationDelay = `${index * 70}ms`;
    card.innerHTML = `<div class="num">${value ?? 0}</div><div class="lbl">${label}</div>`;
    statsEl.appendChild(card);
  });
}

function updateTicker(msg) {
  tickerEl.textContent = msg;
}

function updateSpotlight(title, message) {
  if (spotlightTitleEl) spotlightTitleEl.textContent = title;
  if (spotlightMessageEl) spotlightMessageEl.textContent = message;
}

function animateHeroForPr() {
  heroEl.classList.add("pr-opened");
  setTimeout(() => heroEl.classList.remove("pr-opened"), 2200);
}

function renderEvent(event) {
  if (event.type === "connection" || event.jobId === "booth") {
    const message = event.message || "SSE connected — waiting for crash reports";
    updateTerminalActivity(event);
    updateSpotlight("Listening", message);
    updateTicker(message);
    return;
  }

  const stage = event.stage || "queued";
  latestStage = stage;
  renderPipeline(stage);

  const text = event.message || `${event.status} · ${event.jobId.slice(0, 8)}`;
  addLine(terminalEl, text, event.level || "info");
  updateTerminalActivity(event);
  addLine(timelineEl, `${stageLabels[stage] || stage} · ${event.jobId.slice(0, 8)}`, event.level || "info");
  updateSpotlight(stageLabels[stage] || stage, text);
  updateTicker(text);

  if (stage === "github_pr") {
    animateHeroForPr();
  }
}

async function loadStats() {
  const res = await fetch("/stats");
  if (res.ok) {
    renderStats(await res.json());
  }
}

function renderBugGroups(groups) {
  if (!bugGroupsEl) return;
  bugGroupsEl.innerHTML = "";
  if (!groups.length) {
    bugGroupsEl.innerHTML = `<div class="empty-state">Waiting for grouped crashes</div>`;
    return;
  }
  groups.forEach((group) => {
    const row = document.createElement("article");
    row.className = "aggregation-row";
    const notionLabel = group.notionUrl ? "Open Notion Ticket" : "Create Notion Ticket";
    const githubLabel = group.githubIssueUrl ? "Open GitHub Issue" : "Create GitHub Issue";
    row.innerHTML = `
      <div>
        <strong>${group.title}</strong>
        <small>${group.environment} · ${group.fingerprint.slice(0, 10)} · ${group.uniqueDeviceCount} devices</small>
      </div>
      <span>${group.occurrenceCount}x</span>
      <div class="artifact-actions">
        <button data-url="${group.notionUrl || ""}" data-decision="notion">${notionLabel}</button>
        <button data-url="${group.githubIssueUrl || ""}" data-decision="github">${githubLabel}</button>
      </div>
    `;
    row.querySelectorAll("button[data-url]").forEach((button) => {
      button.addEventListener("click", () => {
        const url = button.dataset.url;
        if (url) window.open(url, "_blank", "noopener");
        else sendDecision(group.lastJobId, button.dataset.decision);
      });
    });
    bugGroupsEl.appendChild(row);
  });
}

function renderOccurrences(occurrences) {
  if (!crashOccurrencesEl) return;
  crashOccurrencesEl.innerHTML = "";
  if (!occurrences.length) {
    crashOccurrencesEl.innerHTML = `<div class="empty-state">No raw occurrences yet</div>`;
    return;
  }
  occurrences.forEach((occurrence) => {
    const row = document.createElement("article");
    row.className = "aggregation-row occurrence";
    row.innerHTML = `
      <div>
        <strong>${occurrence.exceptionType.substring(occurrence.exceptionType.lastIndexOf(".") + 1)}</strong>
        <small>${occurrence.deviceDisplayName} · ${occurrence.screen || "unknown screen"}</small>
      </div>
      <span>${new Date(occurrence.timestamp).toLocaleTimeString()}</span>
    `;
    crashOccurrencesEl.appendChild(row);
  });
}

async function loadAggregation() {
  const [groupsRes, occurrencesRes] = await Promise.all([
    fetch("/booth/bug-groups"),
    fetch("/booth/crash-occurrences")
  ]);
  if (groupsRes.ok) renderBugGroups(await groupsRes.json());
  if (occurrencesRes.ok) renderOccurrences(await occurrencesRes.json());
}

async function sendDecision(jobId, decision) {
  const res = await fetch("/booth/force-decision", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ jobId, decision })
  });
  if (!res.ok) {
    addLine(terminalEl, `Decision failed (${decision}) for ${jobId.slice(0, 8)}`, "error");
  } else {
    addLine(terminalEl, `Decision accepted (${decision}) for ${jobId.slice(0, 8)}`, "info");
    await loadPendingDecisions();
  }
}

async function loadPendingDecisions() {
  if (operatorPanelEl.hidden) return;
  const res = await fetch("/booth/pending-decisions");
  if (!res.ok) return;
  const jobs = await res.json();

  pendingDecisionsEl.innerHTML = "";
  jobs.forEach((job) => {
    const row = document.createElement("div");
    row.className = "decision-row";
    const title = document.createElement("span");
    title.textContent = `${job.jobId.slice(0, 8)} · ${job.status}`;
    row.appendChild(title);

    operatorDecisions.forEach(({ value, label }) => {
      const btn = document.createElement("button");
      btn.textContent = label;
      btn.onclick = () => sendDecision(job.jobId, value);
      row.appendChild(btn);
    });

    pendingDecisionsEl.appendChild(row);
  });
}

function setupResetButton() {
  const button = document.getElementById("holdReset");
  if (!button) return;

  let timer = null;
  const trigger = async () => {
    const res = await fetch("/booth/reset", { method: "POST" });
    if (res.ok) {
      terminalEl.innerHTML = "";
      timelineEl.innerHTML = "";
      terminalActivityEl = null;
      latestStage = "idle";
      renderPipeline(latestStage);
      await loadStats();
      await loadAggregation();
      updateTicker("Dashboard reset by operator");
    } else {
      addLine(terminalEl, "Dashboard reset rejected", "error");
    }
  };

  const startHold = () => {
    button.textContent = "Holding...";
    timer = setTimeout(trigger, 2000);
  };

  const endHold = () => {
    button.textContent = "Hold 2s to reset dashboard";
    if (timer) clearTimeout(timer);
    timer = null;
  };

  button.addEventListener("mousedown", startHold);
  button.addEventListener("mouseup", endHold);
  button.addEventListener("mouseleave", endHold);
  button.addEventListener("touchstart", startHold, { passive: true });
  button.addEventListener("touchend", endHold);
}

async function bootstrap() {
  renderPipeline(latestStage);
  await loadStats();
  await loadAggregation();

  const params = new URLSearchParams(window.location.search);
  if (params.get("debug") === "1") {
    operatorPanelEl.hidden = false;
    setupResetButton();
    await loadPendingDecisions();
    setInterval(loadPendingDecisions, 5000);
  }

  connectEvents();
}

function connectEvents() {
  const source = new EventSource("/events");
  source.onopen = () => {
    if (!sseWasOpen) {
      addLine(terminalEl, "SSE connected — waiting for crash reports", "info");
    } else {
      addLine(terminalEl, "SSE reconnected", "info");
    }
    sseWasOpen = true;
  };
  source.onmessage = async (msg) => {
    const event = JSON.parse(msg.data);
    renderEvent(event);
    await loadStats();
    await loadAggregation();
  };

  source.onerror = () => {
    addLine(terminalEl, "SSE disconnected, retrying… Check server still running and /events reachable.", "warning");
  };
}

bootstrap();
