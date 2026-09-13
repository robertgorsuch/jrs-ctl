// components/runCompare.js — Side-by-side run comparison component (spec §13.1)
//
// Strictly builds DOM nodes with textContent and text nodes; zero innerHTML.

export function createRunCompareModal({ currentRun, api, h, icon, chip, outcomeChip, stepChip, modal } = {}) {
  const container = h('div', { class: 'run-compare' });
  const selectWrap = h('div', { class: 'compare-select-wrap' });
  const diffWrap = h('div', { class: 'compare-diff-grid' });

  selectWrap.append(h('span', { class: 'secondary' }, 'Compare ' + currentRun.runId + ' with: '));

  const selectEl = h('select', { class: 'input compare-run-select' }, h('option', { value: '' }, '-- Select a prior run --'));
  selectWrap.append(selectEl);

  container.append(selectWrap, diffWrap);

  async function loadRuns() {
    try {
      const allRuns = await api.runs();
      for (const r of allRuns) {
        if (r.runId !== currentRun.runId) {
          selectEl.append(
            h('option', { value: r.runId }, r.runId + ' — ' + (r.operation || 'op') + ' (' + (r.outcome || 'unknown') + ')')
          );
        }
      }
    } catch (e) {
      selectWrap.append(h('span', { class: 'tree-error' }, 'Failed to load historical runs.'));
    }
  }

  selectEl.onchange = async () => {
    const compareId = selectEl.value;
    while (diffWrap.firstChild) diffWrap.removeChild(diffWrap.firstChild);
    if (!compareId) return;

    const loader = h('div', { class: 'empty' }, 'Loading run ' + compareId + '...');
    diffWrap.append(loader);

    try {
      const runB = await api.run(compareId);
      loader.remove();
      renderComparison(currentRun, runB);
    } catch (e) {
      loader.remove();
      diffWrap.append(h('div', { class: 'tree-error' }, 'Cannot load run: ' + (e.message || e)));
    }
  };

  function renderComparison(runA, runB) {
    const grid = h('div', { class: 'compare-cols' });

    // Col A
    const colA = h(
      'div',
      { class: 'compare-col card' },
      h('h3', null, 'Current Run (' + runA.runId + ')'),
      h('div', { class: 'detail-grid' },
        h('span', { class: 'secondary' }, 'Operation'), h('span', null, runA.operation),
        h('span', { class: 'secondary' }, 'Outcome'), outcomeChip(runA.outcome),
        h('span', { class: 'secondary' }, 'Started'), h('span', { class: 'mono' }, runA.startedAt || '(none)'),
        h('span', { class: 'secondary' }, 'Ended'), h('span', { class: 'mono' }, runA.endedAt || '(none)')
      )
    );

    // Col B
    const colB = h(
      'div',
      { class: 'compare-col card' },
      h('h3', null, 'Comparison Run (' + runB.runId + ')'),
      h('div', { class: 'detail-grid' },
        h('span', { class: 'secondary' }, 'Operation'), h('span', null, runB.operation),
        h('span', { class: 'secondary' }, 'Outcome'), outcomeChip(runB.outcome),
        h('span', { class: 'secondary' }, 'Started'), h('span', { class: 'mono' }, runB.startedAt || '(none)'),
        h('span', { class: 'secondary' }, 'Ended'), h('span', { class: 'mono' }, runB.endedAt || '(none)')
      )
    );

    grid.append(colA, colB);

    // Step comparison table
    const table = h('table', { class: 'table compare-steps-table' });
    table.append(
      h('thead', null,
        h('tr', null,
          h('th', null, 'Step ID / Title'),
          h('th', null, 'Run ' + runA.runId),
          h('th', null, 'Run ' + runB.runId)
        )
      )
    );

    const tbody = h('tbody');
    const stepsA = runA.steps || [];
    const stepsB = runB.steps || [];
    const allStepIds = new Set([...stepsA.map(s => s.id), ...stepsB.map(s => s.id)]);

    for (const stepId of allStepIds) {
      const sA = stepsA.find(s => s.id === stepId);
      const sB = stepsB.find(s => s.id === stepId);
      const title = (sA && sA.title) || (sB && sB.title) || stepId;

      const row = h(
        'tr',
        null,
        h('td', { class: 'mono' }, title),
        h('td', null, sA ? stepChip(sA.state) : h('span', { class: 'muted' }, '(not in plan)')),
        h('td', null, sB ? stepChip(sB.state) : h('span', { class: 'muted' }, '(not in plan)'))
      );
      tbody.append(row);
    }

    table.append(tbody);
    diffWrap.append(grid, h('div', { class: 'card' }, h('h3', null, 'Step States Comparison'), table));
  }

  loadRuns();
  modal('Compare Runs: ' + currentRun.runId, container);
}
