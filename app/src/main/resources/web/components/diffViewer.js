// components/diffViewer.js — Syntax-highlighted in-browser diff component (spec §10.3, §13.1)
//
// Strictly builds DOM nodes with textContent and text nodes; zero innerHTML.

export function renderDiffViewer(diffDoc, { h, icon, chip, toast } = {}) {
  if (!diffDoc) {
    return h('div', { class: 'empty' }, 'No diff data available.');
  }

  const lines = diffDoc.lines || [];
  const identical = diffDoc.identical || lines.length === 0;

  const header = h(
    'div',
    { class: 'diff-header' },
    h(
      'div',
      { class: 'diff-meta' },
      h('span', { class: 'diff-path mono' }, diffDoc.path),
      identical
        ? chip('pass', 'Identical to original', 'check')
        : chip('warn', 'Modified (' + lines.length + ' lines diff)', 'warn')
    ),
    h(
      'div',
      { class: 'diff-actions' },
      h(
        'button',
        {
          type: 'button',
          class: 'btn-secondary btn-sm',
          onclick: () => {
            const fullText = lines.join('\n');
            navigator.clipboard.writeText(fullText).then(() => {
              if (toast) toast('Diff copied to clipboard');
            }).catch(() => {});
          }
        },
        icon('download'),
        ' Copy Diff'
      )
    )
  );

  const hashRow = h(
    'div',
    { class: 'diff-hashes mono secondary' },
    h('div', null, h('strong', null, 'Original SHA-256: '), diffDoc.originalSha256 || '(none)'),
    h('div', null, h('strong', null, 'Current SHA-256: '), diffDoc.currentSha256 || '(none)')
  );

  if (identical) {
    return h(
      'div',
      { class: 'diff-viewer card' },
      header,
      hashRow,
      h('div', { class: 'diff-empty-notice' }, 'The disk file is identical to the pristine snapshot.')
    );
  }

  const diffLinesContainer = h('div', { class: 'diff-lines-container mono' });

  let oldLineNum = 0;
  let newLineNum = 0;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    let type = 'ctx';
    let lineClass = 'diff-line diff-line-ctx';
    let oldNumDisplay = '';
    let newNumDisplay = '';

    if (line.startsWith('@@')) {
      type = 'hunk';
      lineClass = 'diff-line diff-line-hunk';
      // Parse hunk header: @@ -old,count +new,count @@
      const m = /^@@\s+-(\d+)(?:,\d+)?\s+\+(\d+)(?:,\d+)?\s+@@/.exec(line);
      if (m) {
        oldLineNum = parseInt(m[1], 10) - 1;
        newLineNum = parseInt(m[2], 10) - 1;
      }
    } else if (line.startsWith('+')) {
      type = 'add';
      lineClass = 'diff-line diff-line-add';
      newLineNum++;
      newNumDisplay = String(newLineNum);
    } else if (line.startsWith('-')) {
      type = 'del';
      lineClass = 'diff-line diff-line-del';
      oldLineNum++;
      oldNumDisplay = String(oldLineNum);
    } else {
      oldLineNum++;
      newLineNum++;
      oldNumDisplay = String(oldLineNum);
      newNumDisplay = String(newLineNum);
    }

    const row = h(
      'div',
      { class: lineClass },
      h('span', { class: 'diff-num diff-num-old' }, oldNumDisplay),
      h('span', { class: 'diff-num diff-num-new' }, newNumDisplay),
      h('span', { class: 'diff-sign' }, type === 'add' ? '+' : type === 'del' ? '-' : type === 'hunk' ? '@' : ' '),
      h('span', { class: 'diff-text' }, line.length > 1 && (line.startsWith('+') || line.startsWith('-')) ? line.slice(1) : line)
    );
    diffLinesContainer.append(row);
  }

  return h('div', { class: 'diff-viewer card' }, header, hashRow, diffLinesContainer);
}
