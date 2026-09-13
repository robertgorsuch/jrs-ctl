// components/repoPicker.js — Interactive repository tree picker component (spec §13.1)
//
// Allows browsing repository folders dynamically with lazy child node queries.
// Strictly builds DOM nodes with textContent and text nodes; zero innerHTML.

export function createRepoPicker({ api, h, icon, onSelect, initialSelected = [], multiple = false } = {}) {
  const selectedSet = new Set(initialSelected);
  const container = h('div', { class: 'repo-picker' });
  const treeRoot = h('div', { class: 'repo-tree' });
  const selectedList = h('div', { class: 'repo-selected-chips' });

  function renderSelectedChips() {
    while (selectedList.firstChild) selectedList.removeChild(selectedList.firstChild);
    if (selectedSet.size === 0) {
      selectedList.append(h('span', { class: 'secondary sr-notice' }, 'No folders selected.'));
      return;
    }
    for (const uri of selectedSet) {
      const chip = h(
        'span',
        { class: 'pill mono repo-pill' },
        icon('check'),
        ' ' + uri,
        h(
          'button',
          {
            type: 'button',
            class: 'repo-pill-remove',
            'aria-label': 'Remove ' + uri,
            onclick: (e) => {
              e.stopPropagation();
              selectedSet.delete(uri);
              renderSelectedChips();
              updateTreeCheckboxes();
              if (onSelect) onSelect(Array.from(selectedSet));
            }
          },
          '×'
        )
      );
      selectedList.append(chip);
    }
  }

  function updateTreeCheckboxes() {
    const inputs = treeRoot.querySelectorAll('input[type="checkbox"]');
    for (const input of inputs) {
      input.checked = selectedSet.has(input.dataset.uri);
    }
  }

  async function loadNode(path, parentEl) {
    const loader = h('div', { class: 'tree-loading secondary' }, 'Loading ' + path + '...');
    parentEl.append(loader);

    try {
      const res = await api.repositoryTree(path);
      loader.remove();
      const children = (res && res.children) || [];
      if (children.length === 0) {
        parentEl.append(h('div', { class: 'tree-empty secondary' }, '(empty folder)'));
        return;
      }

      const list = h('ul', { class: 'tree-children' });
      for (const node of children) {
        const item = createTreeNode(node);
        list.append(item);
      }
      parentEl.append(list);
    } catch (err) {
      loader.remove();
      parentEl.append(h('div', { class: 'tree-error' }, 'Cannot load ' + path + ': ' + (err.message || err)));
    }
  }

  function createTreeNode(node) {
    const li = h('li', { class: 'tree-node' });
    let expanded = false;
    const childrenContainer = h('div', { class: 'tree-sub' });

    const toggleBtn = h(
      'button',
      {
        type: 'button',
        class: 'tree-toggle-btn',
        'aria-label': 'Toggle ' + node.uri,
        onclick: async (e) => {
          e.stopPropagation();
          expanded = !expanded;
          toggleBtn.classList.toggle('expanded', expanded);
          if (expanded) {
            childrenContainer.hidden = false;
            if (!childrenContainer.hasChildNodes()) {
              await loadNode(node.uri, childrenContainer);
            }
          } else {
            childrenContainer.hidden = true;
          }
        }
      },
      icon('arrowDown')
    );

    const checkbox = h('input', {
      type: 'checkbox',
      class: 'tree-checkbox',
      'data-uri': node.uri,
      checked: selectedSet.has(node.uri),
      onchange: (e) => {
        if (e.target.checked) {
          if (!multiple) selectedSet.clear();
          selectedSet.add(node.uri);
        } else {
          selectedSet.delete(node.uri);
        }
        renderSelectedChips();
        updateTreeCheckboxes();
        if (onSelect) onSelect(Array.from(selectedSet));
      }
    });

    const label = h(
      'span',
      {
        class: 'tree-label mono',
        onclick: () => {
          checkbox.checked = !checkbox.checked;
          checkbox.dispatchEvent(new Event('change'));
        }
      },
      node.label || node.uri
    );

    const row = h('div', { class: 'tree-row' }, toggleBtn, checkbox, label);
    childrenContainer.hidden = true;

    li.append(row, childrenContainer);
    return li;
  }

  // Initial root load
  const rootNode = {
    uri: '/',
    label: '/',
    isFolder: true,
    hasChildren: true
  };
  const rootLi = createTreeNode(rootNode);
  treeRoot.append(rootLi);
  renderSelectedChips();

  // Auto-expand root
  setTimeout(() => {
    const rootToggle = rootLi.querySelector('.tree-toggle-btn');
    if (rootToggle) rootToggle.click();
  }, 10);

  container.append(
    h('div', { class: 'repo-picker-header' }, h('strong', null, 'Repository Folder Browser')),
    treeRoot,
    h('div', { class: 'repo-selected-wrap' }, h('span', { class: 'secondary' }, 'Selected:'), selectedList)
  );

  return {
    element: container,
    getSelected: () => Array.from(selectedSet),
    setSelected: (uris) => {
      selectedSet.clear();
      for (const u of uris) selectedSet.add(u);
      renderSelectedChips();
      updateTreeCheckboxes();
    }
  };
}
