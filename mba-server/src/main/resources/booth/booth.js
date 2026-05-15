const stageOrder = ["queued", "analyzing", "notion_ticket", "github_pr", "failed"];
const stageLabels = {
  queued: "Queued",
  analyzing: "Koog Analyzing",
  notion_ticket: "Notion Ticket",
  github_pr: "GitHub PR",
  failed: "Failed"
};

const statsEl = document.getElementById("stats");
const timelineEl = document.getElementById("timeline");
const terminalEl = document.getElementById("terminal");
const pipelineEl = document.getElementById("pipeline");
const tickerEl = document.getElementById("ticker");
const heroEl = document.getElementById("hero");
const operatorPanelEl = document.getElementById("operatorPanel");
const pendingDecisionsEl = document.getElementById("pendingDecisions");

let latestStage = "queued";

function renderPipeline(active) {
  pipelineEl.innerHTML = "";
  stageOrder.forEach((stage) => {
    const row = document.createElement("div");
    row.className = "pipeline-step" + (stage === active ? " active" : "");
    row.textContent = stageLabels[stage] || stage;
    pipelineEl.appendChild(row);
  });
}

function addLine(target, text, level = "info") {
  const line = document.createElement("div");
  line.className = `line ${level}`;
  line.textContent = `${new Date().toLocaleTimeString()} · ${text}`;
  target.prepend(line);
  while (target.children.length > 60) {
    target.removeChild(target.lastChild);
  }
}

function renderStats(stats) {
  const cards = [
    ["Total", stats.totalJobs],
    ["Queued", stats.queuedJobs],
    ["Completed", stats.completedJobs],
    ["Failed", stats.failedJobs]
  ];

  statsEl.innerHTML = "";
  cards.forEach(([label, value]) => {
    const card = document.createElement("div");
    card.className = "stat-card";
    card.innerHTML = `<div class="num">${value ?? 0}</div><div class="lbl">${label}</div>`;
    statsEl.appendChild(card);
  });
}

function updateTicker(msg) {
  tickerEl.textContent = msg;
}

function animateHeroForPr() {
  heroEl.classList.add("pr-opened");
  setTimeout(() => heroEl.classList.remove("pr-opened"), 2200);
}

function renderEvent(event) {
  const stage = event.stage || "queued";
  latestStage = stage;
  renderPipeline(stage);

  const text = event.message || `${event.status} · ${event.jobId.slice(0, 8)}`;
  addLine(terminalEl, text, event.level || "info");
  addLine(timelineEl, `${stageLabels[stage] || stage} · ${event.jobId.slice(0, 8)}`, event.level || "info");
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

async function sendDecision(jobId, decision) {
  const res = await fetch("/booth/force-decision", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ jobId, decision })
  });
  if (!res.ok) {
    addLine(terminalEl, `Decision failed (${decision}) for ${jobId.slice(0, 8)}`, "error");
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

    ["notify", "autofix", "fallback"].forEach((decision) => {
      const btn = document.createElement("button");
      btn.textContent = decision;
      btn.onclick = () => sendDecision(job.jobId, decision);
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
      latestStage = "queued";
      renderPipeline(latestStage);
      await loadStats();
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

  const params = new URLSearchParams(window.location.search);
  if (params.get("debug") === "1") {
    operatorPanelEl.hidden = false;
    setupResetButton();
    await loadPendingDecisions();
    setInterval(loadPendingDecisions, 5000);
  }

  const source = new EventSource("/events");
  source.onmessage = async (msg) => {
    const event = JSON.parse(msg.data);
    renderEvent(event);
    await loadStats();
  };

  source.onerror = () => {
    addLine(terminalEl, "SSE disconnected, retrying…", "warning");
  };
}

bootstrap();