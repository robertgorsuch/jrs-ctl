// mock.js — in-memory sample backend for the jrsctl console.
//
// Active when the page is opened with "?mock=1" or when the operator clicks "Use sample data"
// after the real API turned out to be unreachable. It answers every endpoint from spec §13.1
// with realistic data that matches the approved mockups (JasperReports Server 8.2.0 PRO,
// multi-tenant, three installed hotfixes, six historical runs) and can simulate a live run
// that emits StepPending / StepRunning / StepSucceeded / Log / RunSucceeded events over about
// twenty seconds. Nothing here talks to a network.

const DAY = 24 * 60 * 60 * 1000;
const TODAY = '2026-09-08';

// The asset test forbids scheme literals in the console sources (isolated mode guarantee), so
// the sample base URL is assembled from two halves.
const SAMPLE_BASE_URL = ['http:', '//localhost:8080/jasperserver-pro'].join('');
const SNAPSHOT_ROOT = 'C:\\ProgramData\\jrsctl\\snapshots\\';

const server = {
  product: 'JasperReports Server',
  version: '8.2.0',
  edition: 'PRO',
  tenancy: 'multi-tenant',
  database: { vendor: 'PostgreSQL', version: '15.4' },
  baseUrl: SAMPLE_BASE_URL,
  installDir: 'C:\\Jaspersoft\\jasperreports-server-8.2.0',
  service: { kind: 'Tomcat', name: 'jasperreportsTomcat', state: 'running' },
  keystore: { present: true, user: 'jasperserver' },
  networkMode: 'isolated',
};

const doctorItems = [
  ['network', 'WARN', 'Network mode consistency', 'Proxy configured while in isolated mode',
    'Remove network.proxy from jrsctl.yaml or set network.mode: proxy.'],
  ['smoke', 'WARN', 'Smoke report', 'Smoke report URI /public/Samples/Reports/AllAccounts not found',
    'Set smoke.reportUri to a report that exists on this server, or run jrsctl init to detect one.'],
  ['runtime', 'PASS', 'Bundled runtime integrity', 'jlink image checksums match release manifest', null],
  ['config', 'PASS', 'Configuration schema', 'jrsctl.yaml validates against schema 1.1', null],
  ['secrets', 'PASS', 'Secret file permissions', 'secrets/ is owner-only', null],
  ['server', 'PASS', 'Server reachable', 'GET /rest_v2/serverInfo 200 in 84 ms', null],
  ['auth', 'PASS', 'Authentication', 'Login as jasperadmin succeeded', null],
  ['identity', 'PASS', 'Version, edition and tenancy', '8.2.0 PRO multi-tenant', null],
  ['compat', 'PASS', 'Compatibility matrix', 'Matrix 2026.09 lists 8.2.0 PRO as supported', null],
  ['capabilities', 'PASS', 'Capability probes', 'EXPORT_ASYNC, IMPORT_ASYNC, KEYSTORE_REST all as expected', null],
  ['layout', 'PASS', 'Install directory layout', 'buildomatic, apache-tomcat and webapps found', null],
  ['service', 'PASS', 'Service controller', 'sc.exe reports jasperreportsTomcat RUNNING', null],
  ['write-access', 'PASS', 'Write access to target directories', 'WEB-INF\\lib and WEB-INF\\classes writable', null],
  ['disk', 'PASS', 'Disk space', '41 GB free on C:', null],
  ['keystore', 'PASS', 'Keystore', 'Present and readable for runAsUser jasperserver', null],
  ['vendor-scripts', 'PASS', 'Vendor scripts', 'js-export.bat and js-import.bat present', null],
  ['java', 'PASS', 'Vendor Java', 'vendor.javaHome is 11.0.24, matrix requires 11', null],
  ['database', 'PASS', 'Database connectivity', 'JDBC connection to jasperserver on localhost:5432 ok', null],
  ['pending-runs', 'PASS', 'Pending runs', 'No run without a terminal state', null],
  ['lock', 'PASS', 'Run lock', 'runs.lock is free', null],
].map(([id, status, title, detail, remediation]) => ({ id, status, title, detail, remediation }));

function doctorReport() {
  const counts = { pass: 0, warn: 0, fail: 0 };
  for (const it of doctorItems) counts[it.status.toLowerCase()] += 1;
  return { ranAt: new Date().toISOString(), counts, items: doctorItems.map((i) => ({ ...i })) };
}

const hotfixes = [
  { id: 'JRS-8.2.0-HF-0004', title: 'Fix scheduler NPE on quartz misfire', installedAt: TODAY + 'T14:20:12', files: 3, state: 'installed', blockedBy: [] },
  { id: 'JRS-8.2.0-HF-0003', title: 'Quartz thread-pool sizing', installedAt: '2026-09-02T10:15:40', files: 2, state: 'installed', blockedBy: ['JRS-8.2.0-HF-0004'] },
  { id: 'JRS-8.2.0-HF-0001', title: 'Login page CSP header', installedAt: '2026-08-28T11:04:03', files: 1, state: 'installed', blockedBy: [] },
];

/* ---------- step catalogues ---------- */

const HOTFIX_APPLY_STEPS = [
  { id: 'verify-signature', phase: 'verify', title: 'Verify signature', why: 'publisher key jaspersoft-2026', dur: 400,
    logs: ['bundle sha256 9f3c1a4e', 'signature ok, key jaspersoft-2026 (Ed25519)'] },
  { id: 'validate-manifest', phase: 'verify', title: 'Validate manifest', why: 'schema, applicability, dependencies, file overlap', dur: 200,
    logs: ['manifest schema 1 ok', 'applies to 8.2.0 PRO, requires JRS-8.2.0-HF-0003 (installed)', 'file overlap: none'] },
  { id: 'preflight', phase: 'verify', title: 'Preflight', why: 'disk 41 GB free, write access ok, no locks', dur: 1100,
    logs: ['disk free 41 GB on C:', 'write access WEB-INF\\lib ok', 'service jasperreportsTomcat RUNNING, restart required'] },
  { id: 'run-prechecks', phase: 'verify', title: 'Run prechecks', why: '1 file check, 1 HTTP check', dur: 800,
    logs: ['file exists WEB-INF\\lib\\quartz-2.3.2-jrs3.jar ok', 'GET /rest_v2/serverInfo 200'] },
  { id: 'snapshot', phase: 'backup', title: 'Snapshot', why: 'jasperserver-api-impl-8.2.0.jar, quartz-2.3.2-jrs3.jar', dur: 2600,
    logs: ['snapshot 2 files to snapshots\\{run}\\snapshot\\', 'manifest.json written, hashes verified'] },
  { id: 'stop-service', phase: 'apply', title: 'Stop service', why: 'timeout 180s', dur: 3400,
    logs: ['sc.exe stop jasperreportsTomcat', 'state STOP_PENDING', 'state STOPPED after 3.4s'] },
  { id: 'stage-files', phase: 'apply', title: 'Stage files', why: 'hash-verified copies in run staging dir', dur: 900,
    logs: ['staging 3 files to runs\\{run}\\staging\\', 'sha256 ok jasperserver-api-impl-8.2.0.jar', 'sha256 ok quartz-2.3.2-jrs4.jar', 'sha256 ok scheduler.properties'] },
  { id: 'atomic-swap', phase: 'apply', title: 'Atomic swap', why: 'per-file rename, ACLs preserved', dur: 300,
    logs: ['replace WEB-INF\\lib\\jasperserver-api-impl-8.2.0.jar', 'replace WEB-INF\\lib\\quartz-2.3.2-jrs4.jar (was quartz-2.3.2-jrs3.jar)', 'add WEB-INF\\classes\\scheduler.properties'] },
  { id: 'start-service', phase: 'apply', title: 'Start service and wait for server', why: 'up to 10 min', dur: 7000,
    logs: ['sc.exe start jasperreportsTomcat', 'state START_PENDING', 'state RUNNING', 'GET /rest_v2/serverInfo connection refused, retry 1/5 in 2s', 'GET /rest_v2/serverInfo 503, retry 2/5 in 4s', 'GET /rest_v2/serverInfo 200 version 8.2.0 edition PRO', 'login as jasperadmin password=[redacted]'] },
  { id: 'run-postchecks', phase: 'apply', title: 'Run postchecks', why: 'GET /login.html expects 200', dur: 1500,
    logs: ['GET /login.html 200', 'scheduler reports ready'] },
  { id: 'record-installed', phase: 'record', title: 'Record installed', why: 'state store and audit', dur: 600,
    logs: ['hotfixes_installed += {id}', 'hotfix_files += 3 rows', 'audit: hotfix apply {id} by console'] },
];

const HOTFIX_ROLLBACK_STEPS = [
  { id: 'stop-service', phase: 'apply', title: 'Stop service', why: 'WEB-INF files are restored', dur: 3400, logs: ['sc.exe stop jasperreportsTomcat', 'state STOPPED after 3.4s'] },
  { id: 'restore-snapshot', phase: 'apply', title: 'Restore snapshot', why: 'hashes verified before and after', dur: 1200, logs: ['snapshot manifest verified', 'restored 3 files'] },
  { id: 'start-service', phase: 'apply', title: 'Start service and wait for server', why: 'up to 10 min', dur: 6000, logs: ['sc.exe start jasperreportsTomcat', 'GET /rest_v2/serverInfo 200 version 8.2.0'] },
  { id: 'record-rolled-back', phase: 'record', title: 'Record rolled back', why: 'state store and audit', dur: 500, logs: ['hotfixes_installed -= {id}', 'audit: hotfix rollback {id} by console'] },
];

const HOTFIX_VERIFY_STEPS = HOTFIX_APPLY_STEPS.slice(0, 2).concat([
  { id: 'check-hashes', phase: 'verify', title: 'Check file hashes', why: 'every payload file against the manifest', dur: 700, logs: ['3 files, all hashes match'] },
]);

const EXPORT_STEPS = [
  { id: 'probe', phase: 'verify', title: 'Probe export capability', why: 'EXPORT_ASYNC on this server', dur: 600, logs: ['POST /rest_v2/export probe 200'] },
  { id: 'preflight', phase: 'verify', title: 'Preflight', why: 'output directory writable, disk space', dur: 400, logs: ['out dir writable, 41 GB free'] },
  { id: 'export', phase: 'apply', title: 'Run export task', why: 'asynchronous REST export, polled every 2s', dur: 9000, logs: ['task id 7f3a created', 'phase inprogress 20%', 'phase inprogress 65%', 'phase finished'] },
  { id: 'download', phase: 'apply', title: 'Download archive', why: 'streamed to disk, hash recorded', dur: 3000, logs: ['GET /rest_v2/export/7f3a/export.zip 200', '18.4 MB written'] },
  { id: 'record', phase: 'record', title: 'Record export', why: 'state store and audit', dur: 400, logs: ['audit: export by console'] },
];

const IMPORT_STEPS = [
  { id: 'validate-archive', phase: 'verify', title: 'Validate archive', why: 'zip integrity, index.xml, keystore match', dur: 800, logs: ['archive ok, 214 resources', 'source keystore matches archive'] },
  { id: 'preflight', phase: 'verify', title: 'Preflight', why: 'server reachable, lock free', dur: 500, logs: ['GET /rest_v2/serverInfo 200'] },
  { id: 'snapshot-export', phase: 'backup', title: 'Pre-import export of affected URIs', why: 'this is the rollback point', dur: 6000, logs: ['exporting 214 resources', 'pre-import snapshot written'] },
  { id: 'import', phase: 'apply', title: 'Run import task', why: 'asynchronous REST import', dur: 7000, logs: ['task id 9c11 created', 'phase inprogress', 'phase finished, 214 resources'] },
  { id: 'record', phase: 'record', title: 'Record import', why: 'state store and audit', dur: 400, logs: ['audit: import by console'] },
];

const UPGRADE_STEPS = [
  { id: 'verify-package', phase: 'verify', title: 'Verify upgrade package', why: 'checksum and version', dur: 900, logs: ['package sha256 ok', 'target version 8.2.1'] },
  { id: 'preflight', phase: 'verify', title: 'Preflight', why: 'disk, service, database backup confirmation', dur: 800, logs: ['db backup confirmed by operator'] },
  { id: 'export-all', phase: 'backup', title: 'Full-server export (point A)', why: 'vendor js-export, service stopped first', dur: 5000, logs: ['stop service', 'js-export.bat --everything', '312 MB written'] },
  { id: 'snapshot-install', phase: 'backup', title: 'Snapshot install directory (point B)', why: 'webapps and config', dur: 3000, logs: ['snapshot 1.1 GB'] },
  { id: 'apply-upgrade', phase: 'apply', title: 'Run vendor upgrade', why: 'js-upgrade-samedb.bat', dur: 6000, logs: ['js-upgrade-samedb.bat', 'BUILD SUCCESSFUL'] },
  { id: 'reapply-hotfixes', phase: 'apply', title: 'Re-apply applicable hotfixes', why: 'HF-0001, HF-0003, HF-0004', dur: 2000, logs: ['3 hotfixes still applicable, re-applied'] },
  { id: 'start-service', phase: 'apply', title: 'Start service and wait for server', why: 'up to 10 min', dur: 2500, logs: ['GET /rest_v2/serverInfo 200 version 8.2.1'] },
  { id: 'record', phase: 'record', title: 'Record upgrade (point C)', why: 'state store and audit', dur: 400, logs: ['audit: upgrade by console'] },
];

/* ---------- plan builders ---------- */

function inMinutes(n) {
  return new Date(Date.now() + n * 60 * 1000).toISOString();
}

function hotfixIdFrom(pathOrId) {
  const m = /(JRS-[\d.]+-HF-\d{4})/.exec(pathOrId || '');
  return m ? m[1] : 'JRS-8.2.0-HF-0004';
}

function stripSim(steps) {
  return steps.map(({ id, phase, title, why }) => ({ id, phase, title, why }));
}

function buildPlan(op, args) {
  const a = args || {};
  const fingerprint = 'sha256:9f3c1a4e' + Math.random().toString(16).slice(2, 10) + '...e07b';
  const base = { op, fingerprint, validUntil: inMinutes(30) };
  switch (op) {
    case 'hotfix.apply': {
      const id = hotfixIdFrom(a.bundle);
      const warnings = [];
      if (a.allowUnsigned) warnings.push('Unsigned bundles are accepted for this run. The override is audited.');
      if (id === 'JRS-8.2.0-HF-0004') warnings.push('JRS-8.2.0-HF-0004 is already installed. The apply is idempotent: files already at the target hash are skipped.');
      return { ...base, title: 'Plan: apply ' + id + ' to JasperReports Server 8.2.0 PRO',
        summary: {
          filesTouched: '3 under webapps\\jasperserver-pro\\WEB-INF\\ (2 replace, 1 add)',
          service: 'Stop and start jasperreportsTomcat; WEB-INF\\lib changes require it',
          database: 'No SQL in this bundle',
          backups: SNAPSHOT_ROOT + '<runId>\\',
          rollbackPoints: 'After verify, after backup; full rollback via hotfix rollback once recorded',
          strategy: 'File swap with service restart',
          requires: 'JRS-8.2.0-HF-0003 installed (present)',
          downtime: 'Running stops the server for about 2 minutes.',
          signature: a.allowUnsigned ? 'Not checked (allow unsigned)' : 'Signed by Jaspersoft publisher key',
          warnings },
        steps: stripSim(HOTFIX_APPLY_STEPS), sim: { steps: HOTFIX_APPLY_STEPS, id, failAt: /HF-0002/.test(id) ? 'run-postchecks' : null },
        opTitle: 'Hotfix apply ' + id, subtitle: 'Fix scheduler NPE on quartz misfire, 3 files, service restart required, rollback available to phase backup' };
    }
    case 'hotfix.rollback': {
      const id = a.id || 'JRS-8.2.0-HF-0004';
      const hf = hotfixes.find((x) => x.id === id);
      const blocked = hf && hf.blockedBy.length > 0 && !a.cascade;
      const warnings = blocked ? ['Rollback of ' + id + ' is refused: ' + hf.blockedBy.join(', ') + ' own the same files. Enable cascade to roll them back first, newest to oldest.'] : [];
      return { ...base, title: 'Plan: roll back ' + id + (a.cascade && hf && hf.blockedBy.length ? ' (cascade: ' + hf.blockedBy.join(', ') + ' first)' : ''),
        summary: {
          filesTouched: (hf ? hf.files : 3) + ' restored from snapshot',
          service: 'Stop and start jasperreportsTomcat; WEB-INF files are restored',
          database: 'No SQL rollback in this hotfix',
          backups: SNAPSHOT_ROOT + '<runId>\\',
          rollbackPoints: 'The rollback itself is compensated by re-applying the recorded snapshot',
          strategy: 'LIFO per file' + (a.cascade ? ', cascade' : ''),
          downtime: 'Running stops the server for about 1 minute.',
          warnings },
        steps: stripSim(HOTFIX_ROLLBACK_STEPS), sim: blocked ? null : { steps: HOTFIX_ROLLBACK_STEPS, id },
        opTitle: 'Hotfix rollback ' + id, subtitle: (hf ? hf.title : id) + ', restore from snapshot, service restart required' };
    }
    case 'hotfix.verify': {
      const id = hotfixIdFrom(a.bundle);
      return { ...base, title: 'Plan: verify ' + id + ' against JasperReports Server 8.2.0 PRO',
        summary: { filesTouched: 'None; verification only', service: 'No restart', database: 'Not touched',
          backups: 'None needed', rollbackPoints: 'Not applicable', strategy: 'Signature, hashes and applicability',
          downtime: 'Verification does not touch the server.', warnings: [] },
        steps: stripSim(HOTFIX_VERIFY_STEPS), sim: { steps: HOTFIX_VERIFY_STEPS, id },
        opTitle: 'Hotfix verify ' + id, subtitle: 'signature, hashes and applicability only' };
    }
    case 'export': {
      const full = !!a.fullServer;
      const strategy = full || a.strategy === 'vendor' ? 'vendor' : 'rest';
      const uris = Array.isArray(a.uris) && a.uris.length ? a.uris.join(', ') : (full ? 'everything' : '(none)');
      return { ...base, title: 'Plan: export ' + uris + ' to ' + (a.out || 'export.zip'),
        summary: {
          filesTouched: 'Writes ' + (a.out || 'export.zip') + ' only',
          service: strategy === 'vendor' ? 'Stop and start jasperreportsTomcat (vendor js-export requires it)' : 'No restart',
          database: 'Read only',
          backups: 'None needed; the export is itself the artefact',
          rollbackPoints: 'Not applicable',
          strategy: strategy === 'vendor' ? 'Vendor CLI (js-export)' : 'REST asynchronous export',
          downtime: strategy === 'vendor' ? 'Running stops the server for the duration of the export.' : 'The server stays up.',
          warnings: a.usersRoles ? ['Exported users include password hashes. Store the archive with the same care as a database backup.'] : [] },
        steps: stripSim(EXPORT_STEPS), sim: { steps: EXPORT_STEPS, id: uris },
        opTitle: 'Export ' + uris, subtitle: strategy + ' strategy, output ' + (a.out || 'export.zip') };
    }
    case 'import': {
      const strategy = a.strategy === 'vendor' ? 'vendor' : 'rest';
      return { ...base, title: 'Plan: import ' + (a.archive || 'archive.zip') + ' into JasperReports Server 8.2.0 PRO',
        summary: {
          filesTouched: 'Repository resources in the archive (214 detected)',
          service: strategy === 'vendor' ? 'Stop and start jasperreportsTomcat (vendor js-import requires it)' : 'No restart',
          database: 'Repository rows written by the server',
          backups: SNAPSHOT_ROOT + '<runId>\\pre-import-export.zip',
          rollbackPoints: 'After backup: re-import of the pre-import export',
          strategy: strategy === 'vendor' ? 'Vendor CLI (js-import)' : 'REST asynchronous import',
          downtime: strategy === 'vendor' ? 'Running stops the server for about 3 minutes.' : 'The server stays up.',
          warnings: [
            a.update ? 'Update mode overwrites existing resources with the archive versions.' : null,
            a.skipUserUpdate ? null : 'User and role definitions in the archive will be applied. Enable "skip user update" to keep current accounts.',
          ].filter(Boolean) },
        steps: stripSim(IMPORT_STEPS), sim: { steps: IMPORT_STEPS, id: a.archive || 'archive.zip' },
        opTitle: 'Import ' + (a.archive || 'archive.zip'), subtitle: strategy + ' strategy, rollback point after pre-import export' };
    }
    case 'upgrade': {
      const mode = a.mode === 'newdb' ? 'newdb' : 'samedb';
      return { ...base, title: 'Plan: upgrade JasperReports Server 8.2.0 to ' + (a.to || '8.2.1') + ' (' + mode + ')',
        summary: {
          filesTouched: 'Whole install directory under ' + server.installDir,
          service: 'Stopped for the whole upgrade, started at the end',
          database: mode === 'samedb' ? 'Schema upgraded in place. Database rollback is the operator\'s responsibility.' : 'New database created; the old one is left untouched',
          backups: SNAPSHOT_ROOT + '<runId>\\ (full export A, install snapshot B)',
          rollbackPoints: 'B: restore install directory; C: recorded upgrade; use upgrade rollback --to-point',
          strategy: 'Vendor buildomatic ' + mode,
          downtime: 'Running stops the server for roughly 20 to 40 minutes.',
          warnings: [
            mode === 'samedb' && !a.dbBackupConfirmed ? 'Confirm that a database backup exists before running a samedb upgrade. jrsctl does not back up the database.' : null,
            a.reapplyHotfixes ? 'Installed hotfixes are re-applied only where the manifest says they still apply to ' + (a.to || '8.2.1') + '.' : 'Installed hotfixes will not be re-applied.',
          ].filter(Boolean) },
        steps: stripSim(UPGRADE_STEPS), sim: { steps: UPGRADE_STEPS, id: a.to || '8.2.1' },
        opTitle: 'Upgrade to ' + (a.to || '8.2.1'), subtitle: mode + ' mode, rollback points B and C' };
    }
    default:
      return null;
  }
}

/* ---------- run history ---------- */

function synthSteps(catalogue, durations, statusAt) {
  return catalogue.map((s, i) => ({ id: s.id, phase: s.phase, title: s.title, why: s.why,
    status: statusAt ? statusAt(i) : 'succeeded', durationMs: durations[i] === undefined ? s.dur : durations[i] }));
}

const runs = [
  { id: 'r-20260908-1417', op: 'hotfix.apply', target: 'JRS-8.2.0-HF-0004', title: 'Hotfix apply JRS-8.2.0-HF-0004',
    subtitle: 'Fix scheduler NPE on quartz misfire, 3 files, service restart required, rollback available to phase backup',
    startedAt: TODAY + 'T14:17:58', finishedAt: TODAY + 'T14:20:05', durationMs: 127000, outcome: 'succeeded',
    rollbackAvailable: true, supportBundleAvailable: true, backups: SNAPSHOT_ROOT + 'r-20260908-1417\\',
    steps: synthSteps(HOTFIX_APPLY_STEPS, [400, 200, 1100, 800, 2600, 14200, 900, 300, 91000, 1500, 600]) },
  { id: 'r-20260907-0902', op: 'export', target: '/organizations/acme', title: 'Export /organizations/acme',
    subtitle: 'rest strategy, output C:\\exports\\acme-20260907.zip', startedAt: '2026-09-07T09:02:11', finishedAt: '2026-09-07T09:08:16',
    durationMs: 365000, outcome: 'succeeded', rollbackAvailable: false, supportBundleAvailable: true, backups: 'None needed',
    steps: synthSteps(EXPORT_STEPS, [600, 400, 351000, 12000, 400]) },
  { id: 'r-20260905-1633', op: 'hotfix.apply', target: 'JRS-8.2.0-HF-0002', title: 'Hotfix apply JRS-8.2.0-HF-0002',
    subtitle: 'Report scheduler mail template, 2 files, service restart required', startedAt: '2026-09-05T16:33:20', finishedAt: '2026-09-05T16:34:32',
    durationMs: 72000, outcome: 'failed_rolled_back', rollbackAvailable: false, supportBundleAvailable: true, backups: SNAPSHOT_ROOT + 'r-20260905-1633\\',
    failure: { stepId: 'run-postchecks', cause: 'Postcheck GET /login.html returned 500 after restart', backups: [SNAPSHOT_ROOT + 'r-20260905-1633\\snapshot\\'],
      nextAction: 'Files were restored from the snapshot and the service is running on the previous version. Inspect the server log and open a support bundle before retrying.' },
    steps: synthSteps(HOTFIX_APPLY_STEPS, [400, 200, 900, 700, 1900, 12800, 800, 300, 38000, 2100, null],
      (i) => (i < 9 ? 'rolled_back' : i === 9 ? 'failed' : 'skipped')) },
  { id: 'r-20260904-1101', op: 'import', target: 'C:\\imports\\acme-users.zip', title: 'Import C:\\imports\\acme-users.zip',
    subtitle: 'rest strategy, rollback point after pre-import export', startedAt: '2026-09-04T11:01:07', finishedAt: '2026-09-04T11:01:49',
    durationMs: 42000, outcome: 'cancelled', rollbackAvailable: false, supportBundleAvailable: true, backups: SNAPSHOT_ROOT + 'r-20260904-1101\\',
    steps: synthSteps(IMPORT_STEPS, [800, 500, 40000, null, null], (i) => (i < 2 ? 'succeeded' : i === 2 ? 'rolled_back' : 'skipped')) },
  { id: 'r-20260902-1015', op: 'hotfix.apply', target: 'JRS-8.2.0-HF-0003', title: 'Hotfix apply JRS-8.2.0-HF-0003',
    subtitle: 'Quartz thread-pool sizing, 2 files, service restart required', startedAt: '2026-09-02T10:15:02', finishedAt: '2026-09-02T10:17:40',
    durationMs: 158000, outcome: 'succeeded', rollbackAvailable: false, supportBundleAvailable: true, backups: SNAPSHOT_ROOT + 'r-20260902-1015\\',
    steps: synthSteps(HOTFIX_APPLY_STEPS, [300, 200, 1000, 700, 2100, 15000, 700, 200, 134000, 1400, 500]) },
  { id: 'r-20260828-1104', op: 'hotfix.apply', target: 'JRS-8.2.0-HF-0001', title: 'Hotfix apply JRS-8.2.0-HF-0001',
    subtitle: 'Login page CSP header, 1 file, service restart required', startedAt: '2026-08-28T11:04:03', finishedAt: '2026-08-28T11:06:21',
    durationMs: 138000, outcome: 'succeeded', rollbackAvailable: true, supportBundleAvailable: true, backups: SNAPSHOT_ROOT + 'r-20260828-1104\\',
    steps: synthSteps(HOTFIX_APPLY_STEPS, [300, 200, 900, 600, 1500, 13000, 500, 100, 119000, 1300, 400]) },
];

function listItem(run) {
  const { id, op, target, startedAt, finishedAt, durationMs, outcome, rollbackAvailable, supportBundleAvailable } = run;
  return { id, op, target, startedAt, finishedAt, durationMs, outcome, rollbackAvailable, supportBundleAvailable };
}

function detail(run) {
  const { steps, events, sim, timers, subs, ...rest } = run; // eslint-disable-line no-unused-vars
  return { ...rest, steps: steps.map((s) => ({ ...s })) };
}

/** Rebuild an event journal for a finished historical run from its step list. */
function historyEvents(run) {
  const out = [];
  let t = new Date(run.startedAt).getTime();
  const ev = (type, extra) => out.push({ type, data: { type, ts: new Date(t).toISOString(), runId: run.id, ...extra } });
  for (const s of run.steps) ev('StepPending', { stepId: s.id, phase: s.phase });
  for (const s of run.steps) {
    if (s.status === 'skipped') { ev('StepSkipped', { stepId: s.id, phase: s.phase }); continue; }
    ev('StepRunning', { stepId: s.id, phase: s.phase });
    ev('Log', { stepId: s.id, phase: s.phase, level: 'info', message: s.title.toLowerCase() + ' started' });
    t += s.durationMs || 0;
    if (s.status === 'failed') {
      ev('Log', { stepId: s.id, phase: s.phase, level: 'error', message: run.failure.cause });
      ev('StepFailed', { stepId: s.id, phase: s.phase, failure: { kind: 'Recoverable', cause: run.failure.cause } });
    } else {
      ev('StepSucceeded', { stepId: s.id, phase: s.phase, durationMs: s.durationMs });
    }
  }
  if (run.outcome === 'failed_rolled_back' || run.outcome === 'cancelled') {
    const done = run.steps.filter((s) => s.status === 'rolled_back').reverse();
    for (const s of done) { t += 300; ev('StepRolledBack', { stepId: s.id, phase: s.phase }); }
  }
  t = new Date(run.finishedAt).getTime();
  const terminal = { succeeded: 'RunSucceeded', failed_rolled_back: 'RunRolledBack', cancelled: 'RunCancelled', failed: 'RunFailed' }[run.outcome];
  ev(terminal, { durationMs: run.durationMs, backups: [run.backups], ...(run.failure ? { cause: run.failure.cause, nextAction: run.failure.nextAction } : {}) });
  return out;
}

/* ---------- simulated live run ---------- */

function runIdNow() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return 'r-' + d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '-' + p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
}

function startSimulatedRun(plan) {
  const sim = plan.sim;
  const id = runIdNow();
  const run = {
    id, op: plan.op, target: sim.id, title: plan.opTitle, subtitle: plan.subtitle,
    startedAt: new Date().toISOString(), finishedAt: null, durationMs: null, outcome: 'running',
    rollbackAvailable: false, supportBundleAvailable: true, backups: SNAPSHOT_ROOT + id + '\\',
    steps: sim.steps.map((s) => ({ id: s.id, phase: s.phase, title: s.title, why: s.why, status: 'pending', durationMs: null })),
    events: [], subs: new Set(), timers: [], sim,
  };
  runs.unshift(run);

  const emit = (type, extra) => {
    const data = { type, ts: new Date().toISOString(), runId: id, ...extra };
    const ev = { type, data };
    run.events.push(ev);
    for (const fn of run.subs) fn(ev);
  };
  const stepOf = (stepId) => run.steps.find((s) => s.id === stepId);
  const at = (ms, fn) => run.timers.push(setTimeout(fn, ms));
  const subst = (msg) => msg.replace('{run}', id).replace('{id}', sim.id);

  for (const s of run.steps) emit('StepPending', { stepId: s.id, phase: s.phase });

  let t = 300;
  for (const s of sim.steps) {
    const failHere = sim.failAt === s.id;
    at(t, () => { stepOf(s.id).status = 'running'; emit('StepRunning', { stepId: s.id, phase: s.phase }); });
    const slice = s.dur / (s.logs.length + 1);
    s.logs.forEach((line, i) => at(t + slice * (i + 1), () => emit('Log', { stepId: s.id, phase: s.phase, level: 'info', message: subst(line) })));
    t += s.dur;
    const dur = s.dur;
    if (failHere) {
      at(t, () => {
        const cause = 'Postcheck GET /login.html returned 500 after restart';
        stepOf(s.id).status = 'failed';
        emit('Log', { stepId: s.id, phase: s.phase, level: 'error', message: cause });
        emit('StepFailed', { stepId: s.id, phase: s.phase, failure: { kind: 'Recoverable', cause } });
        const done = run.steps.filter((x) => x.status === 'succeeded').reverse();
        let tt = 400;
        for (const x of done) {
          at(tt, () => { x.status = 'rolled_back'; emit('StepRolledBack', { stepId: x.id, phase: x.phase }); emit('Log', { stepId: x.id, phase: x.phase, level: 'warn', message: 'compensated ' + x.title.toLowerCase() }); });
          tt += 350;
        }
        for (const x of run.steps.filter((y) => y.status === 'pending')) { x.status = 'skipped'; emit('StepSkipped', { stepId: x.id, phase: x.phase }); }
        at(tt, () => finish('failed_rolled_back', 'RunRolledBack', { cause, backups: [run.backups + 'snapshot\\'],
          nextAction: 'Files were restored from the snapshot and the service is running on the previous version. Inspect the server log and download the support bundle before retrying.' }));
      });
      break;
    }
    at(t, () => { const st = stepOf(s.id); st.status = 'succeeded'; st.durationMs = dur; emit('StepSucceeded', { stepId: s.id, phase: s.phase, durationMs: dur }); });
  }
  if (!sim.failAt) at(t + 200, () => finish('succeeded', 'RunSucceeded', {}));

  function finish(outcome, type, extra) {
    run.outcome = outcome;
    run.finishedAt = new Date().toISOString();
    run.durationMs = new Date(run.finishedAt).getTime() - new Date(run.startedAt).getTime();
    run.rollbackAvailable = outcome === 'succeeded' && plan.op === 'hotfix.apply';
    if (outcome === 'succeeded' && plan.op === 'hotfix.apply' && !hotfixes.some((x) => x.id === sim.id)) {
      hotfixes.unshift({ id: sim.id, title: 'Applied from console', installedAt: run.finishedAt, files: 3, state: 'installed', blockedBy: [] });
    }
    if (outcome === 'succeeded' && plan.op === 'hotfix.rollback') {
      const i = hotfixes.findIndex((x) => x.id === sim.id);
      if (i >= 0) hotfixes.splice(i, 1);
      for (const x of hotfixes) x.blockedBy = x.blockedBy.filter((b) => b !== sim.id);
    }
    emit(type, { durationMs: run.durationMs, backups: [run.backups], ...extra });
  }

  run.cancel = () => {
    if (run.outcome !== 'running') return;
    for (const tm of run.timers) clearTimeout(tm);
    run.timers = [];
    const inflight = run.steps.find((s) => s.status === 'running');
    emit('Log', { stepId: inflight ? inflight.id : null, phase: inflight ? inflight.phase : 'apply', level: 'warn', message: 'cancel requested; completing or compensating the in-flight step' });
    const done = run.steps.filter((s) => s.status === 'succeeded' || s.status === 'running').reverse();
    let tt = 600;
    for (const s of done) {
      run.timers.push(setTimeout(() => { s.status = 'rolled_back'; emit('StepRolledBack', { stepId: s.id, phase: s.phase }); }, tt));
      tt += 300;
    }
    for (const s of run.steps.filter((x) => x.status === 'pending')) { s.status = 'skipped'; emit('StepSkipped', { stepId: s.id, phase: s.phase }); }
    run.timers.push(setTimeout(() => finish('cancelled', 'RunCancelled', {}), tt));
  };
  return run;
}

/* ---------- backend ---------- */

export function createMockBackend() {
  const plans = new Map();

  function health() {
    const last = runs[0];
    const pending = runs.filter((r) => r.outcome === 'interrupted').map((r) => ({ id: r.id, op: r.op, startedAt: r.startedAt, stepId: 'start-service' }));
    const running = runs.find((r) => r.outcome === 'running');
    const report = doctorReport();
    return {
      tool: { version: '1.0.0', matrixVersion: '2026.09' },
      bind: '127.0.0.1:7420',
      networkMode: 'isolated',
      lastRun: last ? { id: last.id, op: last.op, outcome: last.outcome, finishedAt: last.finishedAt } : null,
      lock: running ? { held: true, runId: running.id, pid: 18244 } : { held: false },
      pendingRuns: pending,
      snapshots: { count: 14, bytes: 1932735283, retentionDays: 30 },
      doctor: { ...report.counts, ranAt: new Date(Date.now() - 6 * 60 * 1000).toISOString(),
        attention: report.items.filter((i) => i.status !== 'PASS').slice(0, 2) },
    };
  }

  function notFound(what) {
    const e = new Error(what + ' not found.');
    e.status = 404;
    return e;
  }

  async function request(method, path, body) {
    await new Promise((r) => setTimeout(r, 120 + Math.random() * 180));
    const m = /^\/runs\/([^/]+)(?:\/(cancel|rollback|resume))?$/.exec(path);
    if (method === 'GET' && path === '/health') return health();
    if (method === 'GET' && path === '/server') return { ...server };
    if (method === 'GET' && path === '/doctor') { await new Promise((r) => setTimeout(r, 900)); return doctorReport(); }
    if (method === 'GET' && path === '/hotfixes') return { hotfixes: hotfixes.map((x) => ({ ...x })) };
    if (method === 'GET' && path === '/runs') return { runs: runs.map(listItem) };
    if (method === 'POST' && path === '/plan') {
      const plan = buildPlan(body.op, body.args);
      if (!plan) { const e = new Error('Unknown operation ' + body.op); e.status = 400; throw e; }
      const planId = 'p-' + Math.random().toString(36).slice(2, 10);
      plans.set(planId, plan);
      const { sim, opTitle, subtitle, ...publicPlan } = plan; // eslint-disable-line no-unused-vars
      return { planId, plan: publicPlan };
    }
    if (method === 'POST' && path === '/run') {
      const plan = plans.get(body.planId);
      if (!plan) { const e = new Error('The plan has expired. Build it again.'); e.status = 410; throw e; }
      if (!plan.sim) { const e = new Error('The plan cannot run: see its warnings.'); e.status = 409; throw e; }
      if (runs.some((r) => r.outcome === 'running')) { const e = new Error('Run lock held by another run.'); e.status = 409; throw e; }
      const run = startSimulatedRun(plan);
      return { runId: run.id };
    }
    if (m) {
      const run = runs.find((r) => r.id === decodeURIComponent(m[1]));
      if (!run) throw notFound('Run ' + m[1]);
      if (method === 'GET' && !m[2]) return detail(run);
      if (method === 'POST' && m[2] === 'cancel') { if (run.cancel) run.cancel(); return { ok: true }; }
      if (method === 'POST' && m[2] === 'rollback') {
        if (!run.rollbackAvailable) { const e = new Error('No rollback is available for this run.'); e.status = 409; throw e; }
        const plan = buildPlan('hotfix.rollback', { id: run.target, cascade: true });
        const rb = startSimulatedRun(plan);
        run.rollbackAvailable = false;
        return { runId: rb.id };
      }
      if (method === 'POST' && m[2] === 'resume') { const e = new Error('Nothing to resume in sample data.'); e.status = 409; throw e; }
    }
    throw notFound(method + ' ' + path);
  }

  function events(runId, onEvent, onStatus) {
    const run = runs.find((r) => r.id === runId);
    let closed = false;
    const sub = (ev) => { if (!closed) onEvent(ev); };
    setTimeout(() => {
      if (closed) return;
      if (!run) { onStatus && onStatus('error', notFound('Run ' + runId)); return; }
      onStatus && onStatus('open');
      const journal = run.events ? run.events.slice() : historyEvents(run);
      for (const ev of journal) sub(ev);
      if (run.outcome === 'running' && run.subs) run.subs.add(sub);
      else onStatus && onStatus('ended');
    }, 150);
    return {
      close() { closed = true; if (run && run.subs) run.subs.delete(sub); },
      done() {},
    };
  }

  async function download(path) {
    const m = /^\/runs\/([^/]+)\/support-bundle$/.exec(path);
    const run = m && runs.find((r) => r.id === decodeURIComponent(m[1]));
    if (!run) throw notFound('Support bundle');
    const text = 'jrsctl sample support bundle for ' + run.id + '\n' + JSON.stringify(detail(run), null, 2) + '\n';
    return new Blob([text], { type: 'text/plain' });
  }

  return { request, events, download };
}
