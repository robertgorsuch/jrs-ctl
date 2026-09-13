// components/logFilter.js — Real-time live log search & filter component (spec §13.1)
//
// Strictly builds DOM nodes with textContent and text nodes; zero innerHTML.

export function createLogFilter({ h, icon, onFilterChange, steps = [] } = {}) {
  let currentText = '';
  let currentSeverity = 'ALL';
  let currentStep = 'ALL';

  function notify() {
    if (onFilterChange) {
      onFilterChange({
        text: currentText.trim().toLowerCase(),
        severity: currentSeverity,
        stepId: currentStep
      });
    }
  }

  const searchInput = h('input', {
    type: 'search',
    class: 'input mono log-search-input',
    placeholder: 'Filter logs... (/ to focus)',
    'aria-label': 'Filter logs',
    oninput: (e) => {
      currentText = e.target.value;
      notify();
    }
  });

  const severitySelect = h(
    'select',
    {
      class: 'input log-filter-select',
      'aria-label': 'Filter by severity level',
      onchange: (e) => {
        currentSeverity = e.target.value;
        notify();
      }
    },
    h('option', { value: 'ALL' }, 'All Levels'),
    h('option', { value: 'INFO' }, 'INFO+'),
    h('option', { value: 'WARN' }, 'WARN+'),
    h('option', { value: 'ERROR' }, 'ERROR only')
  );

  const stepSelect = h(
    'select',
    {
      class: 'input log-filter-select',
      'aria-label': 'Filter by execution step',
      onchange: (e) => {
        currentStep = e.target.value;
        notify();
      }
    },
    h('option', { value: 'ALL' }, 'All Steps')
  );

  for (const s of steps) {
    stepSelect.append(h('option', { value: s.id }, s.title || s.id));
  }

  const clearBtn = h(
    'button',
    {
      type: 'button',
      class: 'btn-secondary btn-sm',
      onclick: () => {
        searchInput.value = '';
        severitySelect.value = 'ALL';
        stepSelect.value = 'ALL';
        currentText = '';
        currentSeverity = 'ALL';
        currentStep = 'ALL';
        notify();
      }
    },
    'Reset'
  );

  const bar = h(
    'div',
    { class: 'log-toolbar' },
    h('div', { class: 'log-toolbar-left' }, searchInput),
    h('div', { class: 'log-toolbar-right' }, severitySelect, stepSelect, clearBtn)
  );

  return {
    element: bar,
    updateSteps: (newSteps) => {
      while (stepSelect.childNodes.length > 1) {
        stepSelect.removeChild(stepSelect.lastChild);
      }
      for (const s of newSteps) {
        stepSelect.append(h('option', { value: s.id }, s.title || s.id));
      }
    },
    matches: (logEntry) => {
      if (currentSeverity !== 'ALL') {
        const lvl = (logEntry.level || 'INFO').toUpperCase();
        if (currentSeverity === 'ERROR' && lvl !== 'ERROR') return false;
        if (currentSeverity === 'WARN' && (lvl !== 'WARN' && lvl !== 'ERROR')) return false;
      }
      if (currentStep !== 'ALL' && logEntry.stepId && logEntry.stepId !== currentStep) {
        return false;
      }
      if (currentText && (!logEntry.message || !logEntry.message.toLowerCase().includes(currentText))) {
        return false;
      }
      return true;
    }
  };
}
