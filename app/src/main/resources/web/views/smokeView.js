// views/smokeView.js — Smoke Testing Station view (spec §12.2, §13.1)
//
// Strictly builds DOM nodes with textContent and text nodes; zero innerHTML.

export function renderSmokeView({ api, h, icon, chip, toast } = {}) {
  const container = h('div', { class: 'smoke-view' });

  let mutating = false;
  let currentReport = null;

  const header = h(
    'div',
    { class: 'card' },
    h('h2', null, 'Smoke Testing Station'),
    h(
      'p',
      { class: 'secondary' },
      'Validate end-to-end JasperReports Server health by probing login authentication, repository listings, PDF report execution, scheduler reachability, and catalog export.'
    ),
    h(
      'div',
      { class: 'btn-row' },
      h(
        'button',
        {
          type: 'button',
          id: 'btn-run-smoke',
          class: 'btn primary',
          onclick: () => runSmoke()
        },
        icon('play'),
        ' Run Smoke Tests'
      ),
      h(
        'label',
        { class: 'checkbox-label' },
        h('input', {
          type: 'checkbox',
          id: 'chk-mutating',
          onchange: (e) => {
            mutating = e.target.checked;
          }
        }),
        ' Include mutating test (--mutating)'
      ),
      h('div', { class: 'spacer' }),
      h(
        'button',
        {
          type: 'button',
          id: 'btn-download-smoke-json',
          class: 'btn-secondary btn-sm',
          disabled: true,
          onclick: () => {
            if (!currentReport) return;
            const blob = new Blob([JSON.stringify(currentReport, null, 2)], { type: 'application/json' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = 'smoke-report-' + new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-') + '.json';
            document.body.append(a);
            a.click();
            a.remove();
            setTimeout(() => URL.revokeObjectURL(url), 10000);
          }
        },
        icon('download'),
        ' Export Report JSON'
      )
    )
  );

  const resultsCard = h('div', { class: 'card', id: 'smoke-results-card' });
  resultsCard.hidden = true;

  container.append(header, resultsCard);

  async function runSmoke() {
    const runBtn = header.querySelector('#btn-run-smoke');
    if (runBtn) {
      runBtn.disabled = true;
      runBtn.textContent = 'Running tests...';
    }

    resultsCard.hidden = false;
    while (resultsCard.firstChild) resultsCard.removeChild(resultsCard.firstChild);
    resultsCard.append(h('div', { class: 'empty' }, icon('running', 'spin'), ' Running smoke checks against server...'));

    try {
      const report = await api.smoke(mutating);
      currentReport = report;
      const downloadBtn = header.querySelector('#btn-download-smoke-json');
      if (downloadBtn) downloadBtn.disabled = false;
      renderReport(report);
    } catch (err) {
      while (resultsCard.firstChild) resultsCard.removeChild(resultsCard.firstChild);
      resultsCard.append(
        h(
          'div',
          { class: 'callout fail' },
          h('strong', null, 'Smoke test execution failed'),
          h('div', null, err.message || String(err))
        )
      );
    } finally {
      if (runBtn) {
        runBtn.disabled = false;
        while (runBtn.firstChild) runBtn.removeChild(runBtn.firstChild);
        runBtn.append(icon('play'), ' Run Smoke Tests');
      }
    }
  }

  function renderReport(report) {
    while (resultsCard.firstChild) resultsCard.removeChild(resultsCard.firstChild);

    const counts = report.counts || { pass: 0, warn: 0, fail: 0, skip: 0 };
    const statGrid = h(
      'div',
      { class: 'stat-grid' },
      h('div', { class: 'stat-card' }, h('span', { class: 'stat-num ok' }, String(counts.pass)), h('span', { class: 'stat-label' }, 'PASSED')),
      h('div', { class: 'stat-card' }, h('span', { class: 'stat-num warn' }, String(counts.warn)), h('span', { class: 'stat-label' }, 'WARNINGS')),
      h('div', { class: 'stat-card' }, h('span', { class: 'stat-num fail' }, String(counts.fail)), h('span', { class: 'stat-label' }, 'FAILED')),
      h('div', { class: 'stat-card' }, h('span', { class: 'stat-num muted' }, String(counts.skip)), h('span', { class: 'stat-label' }, 'SKIPPED'))
    );

    const itemsList = h('div', { class: 'doctor-list' });
    const items = report.items || [];

    for (const item of items) {
      const statusKey = (item.status || 'PASS').toLowerCase();
      const statusChip = chip(
        statusKey === 'pass' ? 'pass' : statusKey === 'warn' ? 'warn' : statusKey === 'fail' ? 'fail' : 'skipped',
        item.status || 'PASS'
      );

      const row = h(
        'div',
        { class: 'doctor-item' },
        h('div', { class: 'row' }, statusChip, h('span', { class: 'title' }, item.title || item.name)),
        h('div', { class: 'detail secondary' }, item.detail)
      );

      if (item.remediation) {
        row.append(h('div', { class: 'remedy' }, item.remediation));
      }

      itemsList.append(row);
    }

    resultsCard.append(
      h('h3', null, 'Results Summary (' + (report.ranAt ? new Date(report.ranAt).toLocaleTimeString() : 'just now') + ')'),
      statGrid,
      h('h3', { style: 'margin-top: 16px;' }, 'Diagnostic Probes'),
      itemsList
    );
  }

  return {
    element: container,
    load: async () => {
      // Automatic first run or ready state
    }
  };
}
