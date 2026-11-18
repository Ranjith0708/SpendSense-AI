(() => {
  const $ = id => document.getElementById(id);
  const money = n => '₹' + Number(n || 0).toLocaleString('en-IN', { maximumFractionDigits: 2 });
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  // Progress bar: eases to 5% in the first 5 seconds, follows chunk progress up to 95%,
  // and the last stretch belongs to the summary step. Only the finished result shows 100%.
  const START_PCT = 5, CHUNKS_END = 95, RAMP_MS = 5000, MSG_MS = 3500;
  const MESSAGES = {
    chunks: ['Reading your statement', 'Sorting your transactions', 'Matching payees to categories', 'Spotting repeat payments', 'Double-checking the amounts'],
    summary: ['Writing your summary', 'Finding ways to save', 'Putting it all together']
  };

  let file = null, chart = null, txns = [], cats = {}, busy = false, es = null, jobId = null, runId = 0;
  let shown = 0, phase = 'chunks', msgIdx = 0, rampTimer = null, msgTimer = null, creepTick = 0;

  fetch('/api/finance/categories').then(r => r.json()).then(list => {
    list.forEach(c => { cats[c.name] = c; $('catFilter').insertAdjacentHTML('beforeend', `<option value="${esc(c.name)}">${esc(c.label)}</option>`); });
  }).catch(() => {});

  // ---- upload ----------------------------------------------------------------------------------------
  const drop = $('dropZone');
  const setFile = f => { if (busy) return; file = f; $('fileName').textContent = f ? f.name : ''; $('analyzeBtn').disabled = !f; };
  $('fileInput').addEventListener('change', e => setFile(e.target.files[0]));
  drop.addEventListener('keydown', e => { if (busy) return; if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); $('fileInput').click(); } });
  ['dragenter', 'dragover'].forEach(ev => drop.addEventListener(ev, e => { e.preventDefault(); if (!busy) drop.classList.add('over'); }));
  ['dragleave', 'drop'].forEach(ev => drop.addEventListener(ev, e => { e.preventDefault(); drop.classList.remove('over'); }));
  drop.addEventListener('drop', e => { if (!busy && e.dataTransfer.files[0]) setFile(e.dataTransfer.files[0]); });

  // Lock the upload section while an analysis runs so a second upload cannot start.
  function setBusy(on) {
    busy = on;
    $('uploadCard').classList.toggle('busy', on);
    $('fileInput').disabled = on; $('pdfPassword').disabled = on; $('newBtn').disabled = on;
    $('analyzeBtn').disabled = on || !file;
    drop.setAttribute('aria-disabled', on); drop.tabIndex = on ? -1 : 0;
    $('overlay').hidden = !on;
  }

  // ---- progress pop-up -------------------------------------------------------------------------------
  function show(pct, text) {
    shown = Math.max(shown, Math.min(100, Math.round(pct)));
    $('barFill').style.width = shown + '%';
    $('progressPct').textContent = shown + '%';
    document.querySelector('.bar').setAttribute('aria-valuenow', shown);
    if (text) $('progressText').textContent = text;
  }
  const setMessage = () => { const l = MESSAGES[phase]; $('progressText').textContent = l[msgIdx % l.length]; };
  function startProgress() {
    stopProgress();
    shown = 0; phase = 'chunks'; msgIdx = 0; creepTick = 0;
    show(0); setMessage();
    const t0 = Date.now();
    rampTimer = setInterval(() => {
      const f = Math.min(1, (Date.now() - t0) / RAMP_MS);
      show(START_PCT * f);
      if (f >= 1) { clearInterval(rampTimer); rampTimer = null; }
    }, 250);
    msgTimer = setInterval(() => {
      msgIdx++; setMessage();
      // While the summary is written the bar creeps toward 99% so it never looks frozen.
      if (phase === 'summary' && shown < 99 && ++creepTick % 2 === 0) show(shown + 1);
    }, MSG_MS);
  }
  function stopProgress() { clearInterval(rampTimer); clearInterval(msgTimer); rampTimer = msgTimer = null; }
  function onProgress(e) {
    if (e.stage === 'SUMMARIZING') {
      if (phase !== 'summary') { phase = 'summary'; msgIdx = 0; creepTick = 0; setMessage(); }
      show(CHUNKS_END);
    } else if (e.stage === 'COMPLETED') {
      show(99);
    } else {
      show(START_PCT + e.percent * (CHUNKS_END - START_PCT) / 100);
    }
  }

  function follow(url) {
    return new Promise((resolve, reject) => {
      es = new EventSource(url);
      let finished = false;
      const end = fn => v => { finished = true; es.close(); es = null; fn(v); };
      es.addEventListener('progress', ev => onProgress(JSON.parse(ev.data)));
      es.addEventListener('result', ev => end(resolve)(JSON.parse(ev.data)));
      es.addEventListener('failure', ev => end(reject)(new Error(JSON.parse(ev.data).message || 'Analysis failed. Try again.')));
      es.addEventListener('cancelled', () => end(reject)(Object.assign(new Error('Cancelled'), { cancelled: true })));
      // EventSource reconnects by itself and the server replays history; only give up once the browser has.
      es.onerror = () => { if (!finished && es && es.readyState === EventSource.CLOSED) end(reject)(new Error('Lost connection to the server. Try again.')); };
    });
  }

  $('cancelBtn').addEventListener('click', () => {
    if (jobId) fetch('/api/finance/jobs/' + jobId, { method: 'DELETE' }).catch(() => {});
    if (es) { es.close(); es = null; }
    runId++;                                   // makes the in-flight click handler abandon its run
    stopProgress(); setBusy(false);
  });

  $('analyzeBtn').addEventListener('click', async () => {
    if (busy || !file) return;
    const body = new FormData();
    body.append('file', file);
    if ($('pdfPassword').value) body.append('password', $('pdfPassword').value);
    $('error').hidden = true; $('results').hidden = true; $('fileChip').hidden = true;
    const my = ++runId; jobId = null;
    const stale = () => my !== runId;
    setBusy(true); startProgress();
    try {
      const res = await fetch('/api/finance/analyze/async', { method: 'POST', body });
      const job = await res.json().catch(() => ({}));
      if (stale()) { if (job.jobId) fetch('/api/finance/jobs/' + job.jobId, { method: 'DELETE' }).catch(() => {}); return; }
      if (!res.ok) throw new Error(job.message || 'Analysis failed. Try again.');
      jobId = job.jobId;
      const data = await follow(job.eventsUrl);
      if (stale()) return;
      show(100, 'Done');
      render(data, file ? file.name : '');
    } catch (e) {
      if (!stale() && !e.cancelled) { $('error').textContent = e.message; $('error').hidden = false; }
    } finally {
      if (!stale()) { stopProgress(); jobId = null; setBusy(false); }
    }
  });

  $('newBtn').addEventListener('click', () => {
    if (busy) return;
    $('results').hidden = true; $('fileChip').hidden = true; $('uploadCard').hidden = false;
    $('pdfPassword').value = ''; $('fileInput').value = ''; setFile(null);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  });

  // ---- tabs ------------------------------------------------------------------------------------------
  const tabs = [...document.querySelectorAll('[role="tab"]')];
  function selectTab(name, focus) {
    tabs.forEach(t => {
      const on = t.dataset.tab === name;
      t.setAttribute('aria-selected', on); t.tabIndex = on ? 0 : -1;
      $('panel-' + t.dataset.tab).hidden = !on;
      if (on && focus) t.focus();
    });
    if (name === 'overview' && chart) chart.resize();
  }
  tabs.forEach((t, i) => {
    t.addEventListener('click', () => selectTab(t.dataset.tab));
    t.addEventListener('keydown', e => {
      const move = { ArrowRight: i + 1, ArrowLeft: i - 1, Home: 0, End: tabs.length - 1 }[e.key];
      if (move === undefined) return;
      e.preventDefault(); selectTab(tabs[(move + tabs.length) % tabs.length].dataset.tab, true);
    });
  });

  // ---- results ---------------------------------------------------------------------------------------
  // "Paid to X Debit INR 130.00" -> "X" (small models sometimes copy the whole line into the payee).
  const cleanName = s => String(s || '').replace(/\s*\b(?:Debit|Credit)\s+INR\s*[\d.,]*\s*$/i, '').replace(/^(?:Paid to|Received from)\s+/i, '').trim() || 'Unknown';
  const isSpend = t => t.type === 'DEBIT' && !t.selfTransfer && t.category !== 'INCOME_AND_TRANSFERS';
  const fill = (id, items) => { $(id).innerHTML = (items || []).map(i => `<li>${esc(i)}</li>`).join('') || '<li class="empty">Nothing to report.</li>'; };

  function render(d, name) {
    txns = d.transactions || [];
    $('uploadCard').hidden = true;
    $('chipName').textContent = name || 'Statement'; $('fileChip').hidden = false;
    $('results').hidden = false;
    selectTab('overview');

    $('kpiSpent').textContent = money(d.totalExpense);
    $('kpiCredits').textContent = money(d.totalIncome);
    $('kpiNet').textContent = 'Net flow ' + (d.netFlow < 0 ? '-' : '') + money(Math.abs(d.netFlow));
    const breakdown = d.categoryBreakdown || [];
    const top = breakdown[0];
    $('kpiTop').textContent = top ? top.displayLabel : 'None';
    $('kpiTopAmt').textContent = top ? `${money(top.totalAmount)} (${top.percentage}%)` : '';
    $('kpiOther').textContent = d.uncategorizedCount;
    $('txnBadge').textContent = txns.length;

    const warnings = d.warnings || [];
    $('warnings').hidden = !warnings.length;
    $('warningList').innerHTML = warnings.map(w => `<li>${esc(w)}</li>`).join('');
    $('autoTag').hidden = !warnings.some(w => /generated automatically/i.test(w));
    $('summary').textContent = d.summary || 'No summary available.';
    fill('insights', d.insights); fill('feedback', d.feedback); fill('tips', d.savingsTips);

    $('breakdown').innerHTML = breakdown.map(c =>
      `<li tabindex="0" role="button" data-cat="${esc(c.category)}" title="Show these transactions"><span class="dot" style="background:${esc(c.color)}"></span><span>${esc(c.displayLabel)} <small>(${c.transactionCount})</small></span><b class="num">${money(c.totalAmount)}</b><span class="pct">${c.percentage}%</span></li>`).join('')
      || '<li class="empty">No spending found.</li>';

    const spend = txns.filter(isSpend);
    const byPayee = new Map();
    spend.forEach(t => {
      const n = cleanName(t.details), k = n.toLowerCase().replace(/[^a-z0-9]/g, '');
      const e = byPayee.get(k) || { name: n, total: 0, n: 0 }; e.total += t.amount; e.n++; byPayee.set(k, e);
    });
    const li = (a, b, c) => `<li><span>${esc(a)}${b ? ` <small>${esc(b)}</small>` : ''}</span><b class="num">${money(c)}</b></li>`;
    $('topPayees').innerHTML = [...byPayee.values()].sort((a, b) => b.total - a.total).slice(0, 5)
      .map(e => li(e.name, `${e.n} ${e.n === 1 ? 'payment' : 'payments'}`, e.total)).join('') || '<li class="empty"><span>Nothing to show.</span></li>';
    $('topPayments').innerHTML = [...spend].sort((a, b) => b.amount - a.amount).slice(0, 5)
      .map(t => li(cleanName(t.details), t.date, t.amount)).join('') || '<li class="empty"><span>Nothing to show.</span></li>';

    drawChart(breakdown);
    resetFilters();
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function drawChart(breakdown) {
    if (chart) { chart.destroy(); chart = null; }
    if (typeof Chart === 'undefined' || !breakdown.length) return;     // chart library blocked or nothing to plot
    chart = new Chart($('chart'), {
      type: 'doughnut',
      data: { labels: breakdown.map(c => c.displayLabel),
              datasets: [{ data: breakdown.map(c => c.totalAmount), backgroundColor: breakdown.map(c => c.color), borderColor: '#FFFFFF', borderWidth: 2 }] },
      options: { maintainAspectRatio: false, cutout: '60%',
        plugins: { legend: { display: false },
                   tooltip: { callbacks: { label: ctx => ` ${ctx.label}: ${money(ctx.parsed)}` } } } }
    });
  }

  // ---- transactions table ----------------------------------------------------------------------------
  function renderTable() {
    const q = $('search').value.trim().toLowerCase(), cat = $('catFilter').value, type = $('typeFilter').value;
    const rows = txns.filter(t => (!cat || t.category === cat) && (!type || t.type === type) &&
      (!q || (t.details || '').toLowerCase().includes(q) || (t.utr || '').includes(q)));
    $('count').textContent = `(${rows.length} of ${txns.length})`;
    $('txnBody').innerHTML = rows.map(t => {
      const c = cats[t.category] || { label: t.category || 'Other', color: '#94A3B8' };
      const tag = t.selfTransfer ? ' <small>(self transfer)</small>' : '';
      return `<tr><td>${esc(t.date)}</td><td>${esc(cleanName(t.details))}${tag}</td><td class="utr">${esc(t.utr) || '-'}</td>
        <td class="num ${t.type === 'DEBIT' ? 'debit' : 'credit'}">${t.type === 'DEBIT' ? '-' : '+'}${money(t.amount)}</td>
        <td>${t.type === 'DEBIT' ? 'Debit' : 'Credit'}</td>
        <td><span class="pill" style="color:${esc(c.color)};border-color:${esc(c.color)}55;background:${esc(c.color)}14">${esc(c.label)}</span></td></tr>`;
    }).join('') || '<tr><td colspan="6">No transactions match these filters.</td></tr>';
  }
  function resetFilters() { $('search').value = ''; $('catFilter').value = ''; $('typeFilter').value = ''; renderTable(); }
  ['search', 'catFilter', 'typeFilter'].forEach(id => $(id).addEventListener('input', renderTable));
  $('clearFilters').addEventListener('click', resetFilters);

  // Selecting a category in the breakdown jumps to its transactions.
  function openCategory(li) {
    if (!li || !li.dataset.cat) return;
    $('search').value = ''; $('typeFilter').value = ''; $('catFilter').value = li.dataset.cat;
    renderTable(); selectTab('transactions', true);
  }
  $('breakdown').addEventListener('click', e => openCategory(e.target.closest('li')));
  $('breakdown').addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openCategory(e.target.closest('li')); } });
})();
