// components/keyShortcuts.js — Keyboard accessibility & navigation shortcuts (spec §13.1)
//
// Strictly builds DOM nodes with textContent and text nodes; zero innerHTML.

export function initKeyShortcuts({ h, modal } = {}) {
  function isEditing(e) {
    const target = e.target;
    if (!target) return false;
    const tag = target.tagName;
    return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable;
  }

  function showShortcutsModal() {
    const shortcuts = [
      { key: '?', desc: 'Open this keyboard shortcuts cheat sheet' },
      { key: '/', desc: 'Focus live log filter / search input' },
      { key: 'r', desc: 'Reload / refresh current view data' },
      { key: 'd', desc: 'Navigate to Dashboard (#/dashboard)' },
      { key: 'n', desc: 'Navigate to New Operation (#/new)' },
      { key: 'Escape', desc: 'Dismiss active dialog or drawer' }
    ];

    const list = h('table', { class: 'table shortcuts-table' });
    const tbody = h('tbody');
    for (const s of shortcuts) {
      tbody.append(
        h(
          'tr',
          null,
          h('td', { class: 'nowrap' }, h('kbd', { class: 'shortcut-kbd mono' }, s.key)),
          h('td', { class: 'secondary' }, s.desc)
        )
      );
    }
    list.append(tbody);

    const body = h('div', { class: 'shortcuts-modal-body' }, list);
    modal('Keyboard Shortcuts', body);
  }

  window.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      const topModal = document.querySelector('.modal-backdrop');
      if (topModal) {
        topModal.remove();
        e.preventDefault();
      }
      return;
    }

    if (isEditing(e) || e.ctrlKey || e.altKey || e.metaKey) {
      return;
    }

    if (e.key === '?' || (e.key === '/' && e.shiftKey)) {
      e.preventDefault();
      showShortcutsModal();
    } else if (e.key === '/') {
      const searchInput = document.querySelector('input[type="search"], input[type="text"].search-input, .log-search-input');
      if (searchInput) {
        e.preventDefault();
        searchInput.focus();
        if (searchInput.select) searchInput.select();
      }
    } else if (e.key === 'd') {
      e.preventDefault();
      window.location.hash = '#/dashboard';
    } else if (e.key === 'n') {
      e.preventDefault();
      window.location.hash = '#/new';
    } else if (e.key === 'r') {
      e.preventDefault();
      window.dispatchEvent(new CustomEvent('console:refresh'));
    }
  });

  return {
    showHelp: showShortcutsModal
  };
}
