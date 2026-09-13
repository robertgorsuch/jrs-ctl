// views/snapshotsView.js — Snapshots & Storage Lifecycle view (spec §11.1, §13.1)
//
// Strictly builds DOM nodes with textContent and text nodes; zero innerHTML.

export function renderSnapshotsView({ api, h, icon, chip, modal, toast } = {}) {
  const container = h('div', { class: 'snapshots-view' });

  const summaryCard = h('div', { class: 'card' });
  const listCard = h('div', { class: 'card' });

  container.append(summaryCard, listCard);

  function formatBytes(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  }

  async function loadSnapshots() {
    while (summaryCard.firstChild) summaryCard.removeChild(summaryCard.firstChild);
    while (listCard.firstChild) listCard.removeChild(listCard.firstChild);
    summaryCard.append(h('div', { class: 'empty' }, icon('running', 'spin'), ' Loading snapshot storage metrics...'));

    try {
      const doc = await api.snapshots();
      while (summaryCard.firstChild) summaryCard.removeChild(summaryCard.firstChild);

      summaryCard.append(
        h('h2', null, 'Snapshots & Retention Management'),
        h(
          'p',
          { class: 'secondary' },
          'Pre-mutation rollback snapshots created automatically before hotfix application, database updates, and server upgrades.'
        ),
        h(
          'div',
          { class: 'stat-grid' },
          h('div', { class: 'stat-card' }, h('span', { class: 'stat-num' }, String(doc.totalCount || 0)), h('span', { class: 'stat-label' }, 'TOTAL SNAPSHOTS')),
          h('div', { class: 'stat-card' }, h('span', { class: 'stat-num' }, formatBytes(doc.totalBytes || 0)), h('span', { class: 'stat-label' }, 'DISK CONSUMPTION')),
          h('div', { class: 'stat-card' }, h('span', { class: 'stat-num' }, String(doc.retentionDays || 30) + 'd'), h('span', { class: 'stat-label' }, 'RETENTION WINDOW')),
          h('div', { class: 'stat-card' }, h('span', { class: 'stat-num' }, String(doc.maxSnapshots || 50)), h('span', { class: 'stat-label' }, 'MAX STORAGE LIMIT'))
        ),
        h(
          'div',
          { class: 'btn-row', style: 'margin-top: 14px;' },
          h(
            'button',
            {
              type: 'button',
              class: 'btn-secondary',
              onclick: async () => {
                if (!confirm('Run retention pruning now? Snapshots outside the retention window without active protection tags will be removed.')) return;
                try {
                  const res = await api.pruneSnapshots(false);
                  if (toast) toast('Pruning complete: ' + res.prunedCount + ' snapshots removed (' + res.remainingCount + ' remaining)');
                  await loadSnapshots();
                } catch (err) {
                  if (toast) toast('Pruning failed: ' + (err.message || err), 'fail');
                }
              }
            },
            icon('trash'),
            ' Prune Expired Snapshots'
          )
        )
      );

      const items = doc.snapshots || [];
      if (items.length === 0) {
        listCard.append(
          h('h3', null, 'Snapshot Archives (0)'),
          h('div', { class: 'empty' }, 'No historical snapshots stored in repository.')
        );
        return;
      }

      listCard.append(h('h3', null, 'Snapshot Archives (' + items.length + ')'));

      const table = h('table', { class: 'table' });
      table.append(
        h(
          'thead',
          null,
          h(
            'tr',
            null,
            h('th', null, 'Snapshot ID / Step'),
            h('th', null, 'Created At'),
            h('th', null, 'Size'),
            h('th', null, 'Files'),
            h('th', null, 'Protection'),
            h('th', { class: 'actions' }, 'Actions')
          )
        )
      );

      const tbody = h('tbody');
      for (const item of items) {
        const protectChips = (item.protectionReasons || []).map(r => chip('info', r));

        const inspectBtn = h(
          'button',
          {
            type: 'button',
            class: 'btn-secondary btn-sm',
            onclick: () => {
              const fileList = h('table', { class: 'table' });
              fileList.append(
                h('thead', null, h('tr', null, h('th', null, 'Relative Path'), h('th', null, 'SHA-256'), h('th', null, 'Size'))),
                h(
                  'tbody',
                  null,
                  ...(item.files || []).map(f =>
                    h(
                      'tr',
                      null,
                      h('td', { class: 'mono' }, f.path),
                      h('td', { class: 'mono secondary' }, (f.sha256 || '').slice(0, 16) + '...'),
                      h('td', null, formatBytes(f.bytes || 0))
                    )
                  )
                )
              );
              modal('Manifest for ' + item.id, fileList);
            }
          },
          'Inspect Files'
        );

        const row = h(
          'tr',
          null,
          h('td', { class: 'mono' }, item.id),
          h('td', { class: 'secondary' }, item.createdAt ? new Date(item.createdAt).toLocaleString() : '(unknown)'),
          h('td', null, formatBytes(item.bytes || 0)),
          h('td', null, String(item.fileCount || 0)),
          h('td', null, protectChips.length > 0 ? h('div', { class: 'btn-row' }, ...protectChips) : h('span', { class: 'secondary' }, '(unprotected)')),
          h('td', null, inspectBtn)
        );

        tbody.append(row);
      }

      table.append(tbody);
      listCard.append(table);
    } catch (err) {
      while (summaryCard.firstChild) summaryCard.removeChild(summaryCard.firstChild);
      summaryCard.append(
        h('div', { class: 'callout fail' }, h('strong', null, 'Failed to load snapshots'), h('div', null, err.message || String(err)))
      );
    }
  }

  return {
    element: container,
    load: loadSnapshots
  };
}
