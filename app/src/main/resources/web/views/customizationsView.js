// views/customizationsView.js — Customizations Registry & 3-Way Diff view (spec §10.3, §13.1)
//
// Strictly builds DOM nodes with textContent and text nodes; zero innerHTML.

import { renderDiffViewer } from '../components/diffViewer.js';

export function renderCustomizationsView({ api, h, icon, chip, modal, toast } = {}) {
  const container = h('div', { class: 'customizations-view' });

  const registerCard = h(
    'div',
    { class: 'card' },
    h('h2', null, 'Customizations Registry'),
    h(
      'p',
      { class: 'secondary' },
      'Track server modifications (JSP files, property overrides, XML configurations) to guarantee preservation across hotfixes and upgrades.'
    ),
    h(
      'form',
      {
        class: 'form-grid',
        onsubmit: async (e) => {
          e.preventDefault();
          const pathInput = registerCard.querySelector('#reg-path');
          const pristineInput = registerCard.querySelector('#reg-pristine');
          const path = pathInput.value.trim();
          const pristine = pristineInput.value.trim() || undefined;
          if (!path) return;

          try {
            await api.registerCustomization(path, pristine);
            if (toast) toast('Customization registered: ' + path);
            pathInput.value = '';
            pristineInput.value = '';
            await loadCustomizations();
          } catch (err) {
            if (toast) toast('Failed to register: ' + (err.message || err), 'fail');
          }
        }
      },
      h(
        'div',
        { class: 'form-row' },
        h('label', { for: 'reg-path' }, 'File Path (relative to installDir or tomcatDir)'),
        h('input', {
          type: 'text',
          id: 'reg-path',
          class: 'input mono',
          placeholder: 'WEB-INF/classes/jasperreports.properties',
          required: true
        })
      ),
      h(
        'div',
        { class: 'form-row' },
        h('label', { for: 'reg-pristine' }, 'Pristine Reference File (optional baseline copy)'),
        h('input', {
          type: 'text',
          id: 'reg-pristine',
          class: 'input mono',
          placeholder: '/opt/jasperreports/baseline/jasperreports.properties'
        })
      ),
      h(
        'div',
        { class: 'btn-row' },
        h('button', { type: 'submit', class: 'btn primary' }, icon('plus'), ' Register Customization')
      )
    )
  );

  const listCard = h('div', { class: 'card' });
  container.append(registerCard, listCard);

  async function loadCustomizations() {
    while (listCard.firstChild) listCard.removeChild(listCard.firstChild);
    listCard.append(h('div', { class: 'empty' }, icon('running', 'spin'), ' Loading registered customizations...'));

    try {
      const doc = await api.customizations();
      const list = doc.customizations || [];
      while (listCard.firstChild) listCard.removeChild(listCard.firstChild);

      if (list.length === 0) {
        listCard.append(
          h('h3', null, 'Registered Files (0)'),
          h('div', { class: 'empty' }, 'No customizations currently registered.')
        );
        return;
      }

      listCard.append(h('h3', null, 'Registered Files (' + list.length + ')'));

      const table = h('table', { class: 'table' });
      table.append(
        h(
          'thead',
          null,
          h(
            'tr',
            null,
            h('th', null, 'Status'),
            h('th', null, 'Path'),
            h('th', null, 'Original SHA-256'),
            h('th', null, 'Current SHA-256'),
            h('th', null, 'Registered At'),
            h('th', { class: 'actions' }, 'Actions')
          )
        )
      );

      const tbody = h('tbody');
      for (const item of list) {
        const isIdentical = item.identical;
        const statusChip = isIdentical
          ? chip('pass', 'identical', 'check')
          : chip('warn', 'modified', 'warn');

        const diffBtn = h(
          'button',
          {
            type: 'button',
            class: 'btn-secondary btn-sm',
            onclick: async () => {
              try {
                const diffDoc = await api.customizationDiff(item.path);
                const viewer = renderDiffViewer(diffDoc, { h, icon, chip, toast });
                modal('Customization Diff: ' + item.path, viewer);
              } catch (err) {
                if (toast) toast('Cannot load diff: ' + (err.message || err), 'fail');
              }
            }
          },
          'View Diff'
        );

        const unregisterBtn = h(
          'button',
          {
            type: 'button',
            class: 'btn-secondary btn-sm danger',
            onclick: async () => {
              if (!confirm('Unregister customization for ' + item.path + '?')) return;
              try {
                await api.unregisterCustomization(item.path);
                if (toast) toast('Unregistered: ' + item.path);
                await loadCustomizations();
              } catch (err) {
                if (toast) toast('Failed to unregister: ' + (err.message || err), 'fail');
              }
            }
          },
          'Unregister'
        );

        const row = h(
          'tr',
          null,
          h('td', null, statusChip),
          h('td', { class: 'mono' }, item.path),
          h('td', { class: 'mono secondary' }, (item.originalSha256 || '').slice(0, 12) + '...'),
          h('td', { class: 'mono secondary' }, item.currentSha256 ? item.currentSha256.slice(0, 12) + '...' : '(none)'),
          h('td', { class: 'secondary' }, item.registeredAt ? new Date(item.registeredAt).toLocaleDateString() : '(unknown)'),
          h('td', { class: 'btn-row' }, diffBtn, unregisterBtn)
        );

        tbody.append(row);
      }

      table.append(tbody);
      listCard.append(table);
    } catch (err) {
      while (listCard.firstChild) listCard.removeChild(listCard.firstChild);
      listCard.append(
        h('div', { class: 'callout fail' }, h('strong', null, 'Failed to load customizations'), h('div', null, err.message || String(err)))
      );
    }
  }

  return {
    element: container,
    load: loadCustomizations
  };
}
