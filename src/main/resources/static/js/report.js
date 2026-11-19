/*
 * Builds the downloadable PDF report: totals, summary, charts, insights and every transaction.
 * Depends on jsPDF and jsPDF-AutoTable (see vendor/). Exposes window.SpendSenseReport.
 *
 * The built-in PDF fonts cannot print the rupee sign or non-Latin letters, so amounts are written as "INR"
 * and unsupported characters are dropped from payee names.
 */
(function (root) {
  const BRAND = [11, 107, 99], INK = [18, 38, 43], MUTED = [94, 113, 118], LINE = [220, 229, 227],
        SOFT = [228, 243, 240], BAD = [180, 56, 31], GOOD = [31, 122, 77];
  const M = 14, PW = 210, PH = 297, CW = PW - 2 * M, FOOT = 18;

  const clean = s => String(s == null ? '' : s)
    .replace(/\u20B9/g, 'INR ').replace(/[\u2013\u2014]/g, '-').replace(/[\u2018\u2019]/g, "'").replace(/[\u201C\u201D]/g, '"')
    .replace(/[\u2022\u00B7]/g, '-').replace(/\u2026/g, '...').replace(/[^\x20-\x7E\u00A0-\u00FF]/g, '').replace(/\s+/g, ' ').trim();
  const inr = n => 'INR ' + Number(n || 0).toLocaleString('en-IN', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
  const rgb = hex => { const m = /^#?([0-9a-f]{6})/i.exec(hex || ''); return m ? [0, 2, 4].map(i => parseInt(m[1].substr(i, 2), 16)) : [148, 163, 184]; };

  function build(jsPDF, d, opts) {
    opts = opts || {};
    const cleanName = opts.cleanName || (s => s);
    const isSpend = opts.isSpend || (t => t.type === 'DEBIT');
    const cats = opts.categories || {};
    const now = opts.now || new Date();
    const txns = d.transactions || [];
    const breakdown = d.categoryBreakdown || [];

    const doc = new jsPDF({ unit: 'mm', format: 'a4' });
    doc.setProperties({ title: 'SpendSense AI - Statement report', author: 'SpendSense AI', subject: clean(opts.fileName || '') });
    let y = M;

    const text = (s, x, yy, o) => doc.text(clean(s), x, yy, o);
    const color = (c, kind) => (kind === 'fill' ? doc.setFillColor : kind === 'draw' ? doc.setDrawColor : doc.setTextColor).call(doc, c[0], c[1], c[2]);
    const font = (size, bold, c) => { doc.setFont('helvetica', bold ? 'bold' : 'normal'); doc.setFontSize(size); color(c || INK); };
    const newPage = () => { doc.addPage(); y = M + 2; };
    const ensure = h => { if (y + h > PH - FOOT) newPage(); };
    /** Shrinks the font (down to minSize) and then truncates so the text fits maxW. */
    const fit = (s, maxW, size, bold, minSize, c) => {
      s = clean(s); let sz = size; font(sz, bold, c);
      while (doc.getTextWidth(s) > maxW && sz > (minSize || 7)) { sz -= 0.5; doc.setFontSize(sz); }
      if (doc.getTextWidth(s) > maxW) {                       // still too wide at the smallest size: truncate once
        let base = s;
        while (base.length > 1 && doc.getTextWidth(base + '...') > maxW) base = base.slice(0, -1);
        s = base.trimEnd() + '...';
      }
      return s;
    };
    const h2 = s => {
      ensure(14); y += 3;
      color(BRAND, 'fill'); doc.rect(M, y - 3.6, 1.2, 5, 'F');
      font(11.5, true); text(s, M + 3.5, y); y += 5;
    };
    const para = (s, size, c, gap) => {
      font(size || 9.5, false, c); const lines = doc.splitTextToSize(clean(s), CW);
      lines.forEach(l => { ensure(5); text(l, M, y); y += 4.7; }); y += gap == null ? 2 : gap;
    };
    const bullets = (items, emptyText) => {
      const list = (items || []).map(clean).filter(Boolean);
      if (!list.length) { para(emptyText || 'Nothing to report.', 9.5, MUTED); return; }
      list.forEach(it => {
        font(9.5); const lines = doc.splitTextToSize(it, CW - 6);
        ensure(lines.length * 4.7 + 1.5);
        color(BRAND, 'fill'); doc.circle(M + 1.4, y - 1.2, 0.7, 'F');
        lines.forEach(l => { font(9.5); text(l, M + 5, y); y += 4.7; });
        y += 1.4;
      });
    };

    // ---- header -------------------------------------------------------------------------------------
    color(BRAND, 'fill'); doc.roundedRect(M, y, 12, 12, 2.5, 2.5, 'F');
    font(9.5, true, [255, 255, 255]); text('SA', M + 6, y + 7.6, { align: 'center' });
    font(16, true); text('SpendSense AI', M + 16, y + 5.4);
    font(9, false, MUTED); text('Statement report', M + 16, y + 10.6);
    const when = now.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
    font(8.5, false, MUTED);
    if (opts.fileName) text(fit(opts.fileName, 90, 8.5, false, 7, MUTED), PW - M, y + 5, { align: 'right' });
    font(8.5, false, MUTED); text('Generated ' + when, PW - M, y + 10, { align: 'right' });
    y += 16; color(LINE, 'draw'); doc.setLineWidth(0.3); doc.line(M, y, PW - M, y); y += 5;

    // ---- KPI boxes ----------------------------------------------------------------------------------
    const top = breakdown[0], bw = (CW - 9) / 4;
    const kpis = [
      ['Total spent', inr(d.totalExpense), ''],
      ['Total credits', inr(d.totalIncome), 'Net flow ' + (d.netFlow < 0 ? '-' : '') + inr(Math.abs(d.netFlow))],
      ['Top category', top ? top.displayLabel : 'None', top ? inr(top.totalAmount) + ' (' + top.percentage + '%)' : ''],
      ['Uncategorized', String(d.uncategorizedCount || 0), 'payments to review']
    ];
    kpis.forEach((k, i) => {
      const x = M + i * (bw + 3);
      color(LINE, 'draw'); color([255, 255, 255], 'fill'); doc.roundedRect(x, y, bw, 21, 2, 2, 'FD');
      font(7.8, false, MUTED); text(k[0], x + 3, y + 5.5);
      text(fit(k[1], bw - 6, 12.5, true, 8, INK), x + 3, y + 12.5);
      text(fit(k[2], bw - 6, 7.4, false, 6, MUTED), x + 3, y + 17.8);
    });
    y += 26;

    // ---- warnings -----------------------------------------------------------------------------------
    const warnings = (d.warnings || []).map(clean).filter(Boolean);
    if (warnings.length) {
      font(8.8); const wl = warnings.map(w => doc.splitTextToSize(w, CW - 10));
      const h = 8 + wl.reduce((a, l) => a + l.length * 4.2 + 1, 0);
      ensure(h + 3);
      color([255, 246, 232], 'fill'); color([240, 204, 143], 'draw'); doc.roundedRect(M, y, CW, h, 2, 2, 'FD');
      font(9, true, [122, 74, 0]); text('Heads up', M + 4, y + 5.5);
      let yy = y + 10; font(8.8, false, [122, 74, 0]);
      wl.forEach(lines => { text('-', M + 4, yy); lines.forEach(l => { text(l, M + 8, yy); yy += 4.2; }); yy += 1; });
      y += h + 4;
    }

    // ---- summary ------------------------------------------------------------------------------------
    h2('Summary'); para(d.summary || 'No summary available.');

    // ---- category donut + legend --------------------------------------------------------------------
    h2('Where the money went');
    if (!breakdown.length) { para('No spending found.', 9.5, MUTED); }
    else {
      const rowH = breakdown.length > 9 ? 5.2 : 6.2, blockH = Math.max(62, breakdown.length * rowH + 4);
      ensure(blockH);
      const cx = M + 30, cy = y + 30, R = 28, r = 16, total = breakdown.reduce((a, c) => a + c.totalAmount, 0) || 1;
      let a0 = -Math.PI / 2;
      breakdown.forEach(c => {
        const sweep = Math.min(2 * Math.PI, (c.totalAmount / total) * 2 * Math.PI);
        if (sweep <= 0) return;
        const steps = Math.max(2, Math.ceil(sweep / (Math.PI / 60)));
        const pts = [];
        for (let i = 0; i <= steps; i++) { const a = a0 + sweep * i / steps; pts.push([cx + R * Math.cos(a), cy + R * Math.sin(a)]); }
        for (let i = steps; i >= 0; i--) { const a = a0 + sweep * i / steps; pts.push([cx + r * Math.cos(a), cy + r * Math.sin(a)]); }
        const vec = pts.slice(1).map((p, i) => [p[0] - pts[i][0], p[1] - pts[i][1]]);
        color(rgb(c.color), 'fill'); color([255, 255, 255], 'draw'); doc.setLineWidth(0.4);
        doc.lines(vec, pts[0][0], pts[0][1], [1, 1], 'FD', true);
        a0 += sweep;
      });
      font(7, false, MUTED); text('Total spent', cx, cy - 1.2, { align: 'center' });
      text(fit(inr(d.totalExpense), 2 * r - 4, 8.5, true, 6, INK), cx, cy + 3.2, { align: 'center' });
      // legend
      let ly = y + 5; const lx = M + 68, right = PW - M;
      font(7.5, true, MUTED); text('Category', lx + 5, ly - 1.5); text('Amount', right - 14, ly - 1.5, { align: 'right' }); text('Share', right, ly - 1.5, { align: 'right' });
      ly += 3;
      breakdown.forEach(c => {
        color(rgb(c.color), 'fill'); doc.roundedRect(lx, ly - 2.7, 3, 3, 0.6, 0.6, 'F');
        text(fit(c.displayLabel + ' (' + c.transactionCount + ')', right - lx - 5 - 40, 8.6, false, 7, INK), lx + 5, ly);
        font(8.6); text(inr(c.totalAmount), right - 14, ly, { align: 'right' });
        font(8.6, false, MUTED); text(c.percentage + '%', right, ly, { align: 'right' });
        ly += rowH;
      });
      y += blockH + 2;
    }

    // ---- top payees bar chart -----------------------------------------------------------------------
    const spend = txns.filter(isSpend), by = new Map();
    spend.forEach(t => {
      const n = cleanName(t.details), k = n.toLowerCase().replace(/[^a-z0-9]/g, '');
      const e = by.get(k) || { name: n, total: 0, n: 0 }; e.total += t.amount; e.n++; by.set(k, e);
    });
    const payees = [...by.values()].sort((a, b) => b.total - a.total).slice(0, 6);
    if (payees.length) {
      h2('Top payees'); ensure(payees.length * 7 + 2);
      const max = payees[0].total || 1, bx = M + 58, bmax = CW - 58 - 36;
      payees.forEach(p => {
        text(fit(p.name + ' (' + p.n + ')', 55, 8.8, false, 7, INK), M, y + 3.4);
        color(SOFT, 'fill'); doc.roundedRect(bx, y, bmax, 4.6, 1, 1, 'F');
        color(BRAND, 'fill'); doc.roundedRect(bx, y, Math.max(1.5, bmax * p.total / max), 4.6, 1, 1, 'F');
        font(8.8); text(inr(p.total), PW - M, y + 3.4, { align: 'right' });
        y += 7;
      });
    }

    // ---- largest payments ---------------------------------------------------------------------------
    const largest = [...spend].sort((a, b) => b.amount - a.amount).slice(0, 5);
    if (largest.length) {
      h2('Largest payments'); ensure(40);
      doc.autoTable({
        startY: y, margin: { left: M, right: M, top: M, bottom: FOOT }, theme: 'plain',
        styles: { fontSize: 8.5, cellPadding: 1.8, textColor: INK, lineColor: LINE, lineWidth: { bottom: 0.2 } },
        head: [['Date', 'Payee', 'Amount']], headStyles: { fontStyle: 'bold', textColor: MUTED },
        body: largest.map(t => [clean(t.date), clean(cleanName(t.details)), inr(t.amount)]),
        columnStyles: { 0: { cellWidth: 32 }, 2: { halign: 'right', cellWidth: 36 } },
        didParseCell: data => { if (data.section === 'head' && data.column.index === 2) data.cell.styles.halign = 'right'; }
      });
      y = doc.lastAutoTable.finalY + 4;
    }

    // ---- insights ----------------------------------------------------------------------------------
    h2('Insights'); bullets(d.insights);
    h2('Feedback'); bullets(d.feedback);
    h2('Ways to save'); bullets(d.savingsTips);

    // ---- all transactions ---------------------------------------------------------------------------
    ensure(50); h2('All transactions (' + txns.length + ')');
    if (!txns.length) { para('No transactions were found.', 9.5, MUTED); }
    else {
      const label = t => (cats[t.category] && cats[t.category].label) || clean(String(t.category || 'Other').replace(/_/g, ' '));
      const catColor = t => rgb((cats[t.category] && cats[t.category].color) || '#64748b').map(v => Math.round(v * 0.78));
      doc.autoTable({
        startY: y, margin: { left: M, right: M, top: M, bottom: FOOT }, showHead: 'everyPage', theme: 'grid',
        styles: { fontSize: 7.4, cellPadding: 1.6, textColor: INK, lineColor: LINE, lineWidth: 0.15, overflow: 'linebreak' },
        headStyles: { fillColor: BRAND, textColor: [255, 255, 255], fontStyle: 'bold' },
        alternateRowStyles: { fillColor: [247, 250, 249] },
        head: [['Date', 'Payee / details', 'UTR', 'Type', 'Amount', 'Category']],
        body: txns.map(t => [clean(t.date), clean(cleanName(t.details)) + (t.selfTransfer ? ' (self transfer)' : ''), clean(t.utr) || '-',
          t.type === 'DEBIT' ? 'Debit' : 'Credit', (t.type === 'DEBIT' ? '-' : '+') + inr(t.amount), label(t)]),
        columnStyles: { 0: { cellWidth: 24 }, 1: { cellWidth: 55 }, 2: { cellWidth: 28 }, 3: { cellWidth: 14 }, 4: { cellWidth: 26, halign: 'right' }, 5: { cellWidth: 35 } },
        didParseCell: data => {
          if (data.section === 'head' && data.column.index === 4) data.cell.styles.halign = 'right';
          if (data.section !== 'body') return;
          const t = txns[data.row.index];
          if (data.column.index === 4) { data.cell.styles.textColor = t.type === 'DEBIT' ? BAD : GOOD; data.cell.styles.fontStyle = 'bold'; }
          if (data.column.index === 5) { data.cell.styles.textColor = catColor(t); data.cell.styles.fontStyle = 'bold'; }
        }
      });
    }

    // ---- footer on every page -----------------------------------------------------------------------
    const pages = doc.getNumberOfPages();
    for (let i = 1; i <= pages; i++) {
      doc.setPage(i); color(LINE, 'draw'); doc.setLineWidth(0.2); doc.line(M, PH - 12, PW - M, PH - 12);
      font(7.5, false, MUTED); text('SpendSense AI - Statement report', M, PH - 7.5);
      text('Page ' + i + ' of ' + pages, PW - M, PH - 7.5, { align: 'right' });
    }
    return doc;
  }

  /** SpendSense_Report_<statement name>_<yyyy-mm-dd>.pdf */
  function fileName(statementName, now) {
    const base = String(statementName || 'statement').replace(/\.[^.]+$/, '').replace(/[^A-Za-z0-9._-]+/g, '_').replace(/^_+|_+$/g, '') || 'statement';
    const d = now || new Date(), p = n => String(n).padStart(2, '0');
    return 'SpendSense_Report_' + base.slice(0, 60) + '_' + d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + '.pdf';
  }

  root.SpendSenseReport = { build, fileName, clean };
})(typeof window !== 'undefined' ? window : globalThis);
