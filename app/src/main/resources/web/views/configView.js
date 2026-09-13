// views/configView.js — Server Configuration & Environment Inspector (spec §13.1)
//
// Strictly builds DOM nodes with textContent and text nodes; zero innerHTML.

export function renderConfigView({ api, h, icon, chip, toast } = {}) {
  const container = h('div', { class: 'config-view' });

  const headerCard = h('div', { class: 'card' });
  const serverCard = h('div', { class: 'card' });
  const platformCard = h('div', { class: 'card' });
  const keysCard = h('div', { class: 'card' });
  const yamlCard = h('div', { class: 'card' });

  container.append(headerCard, serverCard, platformCard, keysCard, yamlCard);

  async function loadConfig() {
    while (headerCard.firstChild) headerCard.removeChild(headerCard.firstChild);
    while (serverCard.firstChild) serverCard.removeChild(serverCard.firstChild);
    while (platformCard.firstChild) platformCard.removeChild(platformCard.firstChild);
    while (keysCard.firstChild) keysCard.removeChild(keysCard.firstChild);
    while (yamlCard.firstChild) yamlCard.removeChild(yamlCard.firstChild);

    headerCard.append(
      h('h2', null, 'Configuration & Environment Inspector'),
      h(
        'p',
        { class: 'secondary' },
        'Read-only runtime environment diagnostics, platform integrity, trusted cryptographic key ring, and redacted server configuration.'
      )
    );

    serverCard.append(h('div', { class: 'empty' }, icon('running', 'spin'), ' Loading configuration...'));

    try {
      const doc = await api.config();
      while (serverCard.firstChild) serverCard.removeChild(serverCard.firstChild);

      // Server configuration card
      const s = doc.server || {};
      serverCard.append(
        h('h3', null, 'JasperReports Server Targets'),
        h(
          'div',
          { class: 'detail-grid' },
          h('span', { class: 'secondary' }, 'Base URL'), h('span', { class: 'mono' }, s.baseUrl || '(unconfigured)'),
          h('span', { class: 'secondary' }, 'Webapp Name'), h('span', { class: 'mono' }, s.webappName || 'jasperserver-pro'),
          h('span', { class: 'secondary' }, 'Install Directory'), h('span', { class: 'mono' }, s.installDir || '(auto-detect)'),
          h('span', { class: 'secondary' }, 'Tomcat Directory'), h('span', { class: 'mono' }, s.tomcatDir || '(auto-detect)'),
          h('span', { class: 'secondary' }, 'Service Kind'), h('span', null, s.serviceKind || 'systemd'),
          h('span', { class: 'secondary' }, 'Auth Mode'), h('span', null, s.authMode || 'basic'),
          h('span', { class: 'secondary' }, 'Username'), h('span', { class: 'mono' }, s.username || 'superuser'),
          h('span', { class: 'secondary' }, 'Credentials'), s.secretsConfigured ? chip('pass', 'Configured (Redacted)', 'check') : chip('warn', 'Missing Password', 'warn')
        )
      );

      // Platform health card
      const p = doc.platform || {};
      platformCard.append(
        h('h3', null, 'Platform & Runtime Health'),
        h(
          'div',
          { class: 'detail-grid' },
          h('span', { class: 'secondary' }, 'Operating System'), h('span', null, p.os + ' (' + p.arch + ')'),
          h('span', { class: 'secondary' }, 'JVM Version'), h('span', { class: 'mono' }, p.jvmVersion),
          h('span', { class: 'secondary' }, 'SQLite State Store'), p.sqliteHealthy ? chip('pass', 'Healthy', 'check') : chip('fail', 'Unhealthy / Read-only', 'fail'),
          h('span', { class: 'secondary' }, 'Home Directory Write Access'), p.writeAccess ? chip('pass', 'Writable', 'check') : chip('fail', 'Read-only', 'fail')
        )
      );

      // Trusted keys card
      const keys = doc.keys || [];
      keysCard.append(h('h3', null, 'Trusted Ed25519 Signing Keys (' + keys.length + ')'));
      if (keys.length === 0) {
        keysCard.append(h('div', { class: 'empty' }, 'No trusted keys loaded in keyring.'));
      } else {
        const table = h('table', { class: 'table' });
        table.append(
          h('thead', null, h('tr', null, h('th', null, 'Key Name'), h('th', null, 'Fingerprint'), h('th', null, 'Bundled'), h('th', null, 'Algorithm'))),
          h(
            'tbody',
            null,
            ...keys.map(k =>
              h(
                'tr',
                null,
                h('td', { class: 'mono' }, k.name),
                h('td', { class: 'mono secondary' }, k.fingerprint),
                h('td', null, k.bundled ? chip('info', 'bundled') : chip('secondary', 'user-added')),
                h('td', { class: 'mono secondary' }, k.algorithm || 'Ed25519')
              )
            )
          )
        );
        keysCard.append(table);
      }

      // Redacted YAML card
      yamlCard.append(
        h('h3', null, 'Active config.yaml (Redacted)'),
        h(
          'pre',
          { class: 'log-wrap mono' },
          doc.redactedYaml || '# (empty or missing config.yaml)'
        )
      );
    } catch (err) {
      while (serverCard.firstChild) serverCard.removeChild(serverCard.firstChild);
      serverCard.append(
        h('div', { class: 'callout fail' }, h('strong', null, 'Failed to load configuration'), h('div', null, err.message || String(err)))
      );
    }
  }

  return {
    element: container,
    load: loadConfig
  };
}
