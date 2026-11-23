const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const babel = require('@babel/core');
const {parse, compileTemplate} = require('@vue/compiler-sfc');

const copy = value => JSON.parse(JSON.stringify(value));
function load(relative, mocks = {}) {
  const filename = path.join(__dirname, '../src', relative);
  const text = fs.readFileSync(filename, 'utf8');
  const descriptor = relative.endsWith('.vue') ? parse(text).descriptor : null;
  if (descriptor) assert.deepEqual(compileTemplate({source: descriptor.template.content, filename, id: 'export-format-test'}).errors, []);
  const {code} = babel.transformSync(descriptor ? descriptor.script.content : text, {configFile: false, babelrc: false,
    plugins: [require('@babel/plugin-transform-modules-commonjs')]});
  const module = {exports: {}};
  vm.runInNewContext(code, {module, exports: module.exports, require: name => mocks[name] ||
    (name.startsWith('@/') || name.startsWith('./') || name.endsWith('.vue') ? {} : require(name))});
  return module.exports.default;
}

test('format selection defaults to CSV, returns Excel or cancellation, and prevents duplicate selections', async () => {
  const service = load('common/services/ExportFormat.js');
  let selection = service.choose();
  assert.equal(service.state.visible, true); assert.equal(service.state.format, 'CSV');
  assert.equal(await service.choose(), null);
  service.state.format = 'XLSX'; service.finish(service.state.format);
  assert.equal(await selection, 'XLSX'); assert.equal(service.state.visible, false);
  selection = service.choose(); assert.equal(service.state.format, 'CSV');
  service.finish(); assert.equal(await selection, null);
  service.finish(); assert.equal(service.state.visible, false);
});

test('common exports pass the selected format and preserve record IDs, filters and attachment options', async () => {
  for (const format of ['CSV', 'XLSX']) {
    const requests = [], downloads = [];
    const service = load('common/services/ExportService.js', {
      '@/common/services/ExportFormat.js': {choose: async () => format},
      '@/common/services/Alerts.js': {info() {}, remove() {}},
      '@/common/services/HttpClient.js': {post: async (url, data) => {
        requests.push({url, data: copy(data)}); return {id: 12, status: 'COMPLETED'};
      }, getUrl: path => path, downloadFile: url => downloads.push(url)}
    });
    const input = {objectType: 'extensions', recordIds: [3, 5], params: {cpId: '1', ppids: '00123', entityType: 'SpecimenEvent'}};
    const original = copy(input);
    const result = await service.exportRecords(input);
    assert.equal(result.id, 12);
    assert.deepEqual(requests, [{url: 'export-jobs', data: {...original, fileFormat: format}}]);
    assert.deepEqual(input, original); assert.deepEqual(downloads, ['export-jobs/12/output']);
  }
});

test('cancelled exports neither contact the server nor show a started-export alert', async () => {
  const unexpected = () => { throw Error('Cancelled export continued'); };
  const service = load('common/services/ExportService.js', {
    '@/common/services/ExportFormat.js': {choose: async () => null},
    '@/common/services/Alerts.js': {info: unexpected},
    '@/common/services/HttpClient.js': {post: unexpected}
  });
  assert.equal(await service.exportRecords({objectType: 'specimen'}), null);
  const query = load('queries/services/Query.js', {
    '@/common/services/ExportFormat.js': {choose: async () => null},
    '@/queries/services/Util.js': {getDataAql: unexpected},
    '@/common/services/HttpClient.js': {post: unexpected}
  });
  assert.equal(await query.exportData({}, [], {}), null);
});

test('an explicit format skips the dialog and long-running or failed jobs do not trigger downloads', async () => {
  for (const status of ['IN_PROGRESS', 'FAILED']) {
    const service = load('common/services/ExportService.js', {
      '@/common/services/ExportFormat.js': {choose: () => { throw Error('Unexpected format dialog'); }},
      '@/common/services/Alerts.js': {info() {}, remove() {}, error() {}},
      '@/common/services/HttpClient.js': {post: async (url, data) => {
        assert.equal(data.fileFormat, 'XLSX'); return {id: 12, status};
      }, downloadFile: () => { throw Error('Job not ready'); }}
    });
    assert.equal((await service.exportRecords({objectType: 'cpr', fileFormat: 'XLSX'})).status, status);
  }
});

test('query exports preserve AQL facets sorting and column options in CSV and Excel', async () => {
  for (const format of ['CSV', 'XLSX']) {
    const calls = [], downloads = [];
    const service = load('queries/services/Query.js', {
      '@/common/services/ExportFormat.js': {choose: async () => format},
      '@/common/services/Alerts.js': {info() {}},
      '@/queries/services/Util.js': {getDataAql: async (...args) => {calls.push(copy(args));return 'select Participant.ppid order by Participant.ppid';}},
      '@/common/services/HttpClient.js': {post: async (url, data) => {
        assert.equal(url, 'query/export'); assert.equal(data.fileFormat, format);
        assert.equal(data.cpId, 1); assert.equal(data.cpGroupId, 9); assert.equal(data.savedQueryId, 20);
        assert.equal(data.outputColumnExprs, true); assert.equal(data.wideRowMode, 'SHALLOW');
        assert.equal(data.caseSensitive, false);
        return {completed: true, dataFile: 'authorized-result' + (format === 'XLSX' ? '.xlsx' : '')};
      }, getUrl: (url, options) => {assert.equal(url, 'query/export'); return options.query.fileId;},
      downloadFile: url => downloads.push(url)}
    });
    const query = {id: 20, cpId: 1, cpGroupId: 9, outputColumnExprs: true, wideRowMode: 'SHALLOW', caseSensitive: false};
    const facets = [{name: 'type', values: ['Blood']}], sort = {field: 'ppid', direction: 'DESC'};
    await service.exportData(query, facets, sort);
    assert.deepEqual(calls, [[query, facets, false, {orderBy: sort}]]);
    assert.deepEqual(downloads, ['authorized-result' + (format === 'XLSX' ? '.xlsx' : '')]);
  }
});

test('cancelling the shared format dialog keeps the participant and user export pages open', async () => {
  for (const file of ['biospecimen/participants/ExportRecords.vue', 'administrative/users/ExportFormRecords.vue']) {
    const routes = [];
    const component = load(file, {
      '@/common/services/ExportService.js': {exportRecords: async () => null},
      '@/common/services/Router.js': {goto: (...args) => routes.push(args)}
    });
    const ctx = {cpId: 1, ctx: {recordType: {type: 'extensions'}, ppids: '00123', emailIds: 'user@example.org'},
      $refs: {exportForm: {validate: () => true}}};
    const action = component.methods._exportRecords || component.methods.exportRecords;
    await action.call(ctx); await Promise.resolve();
    assert.deepEqual(routes, []);
  }
});

test('format dialog compiles with localized labels and forwards download and cancel choices', () => {
  const choices = [];
  const service = {state: {visible: true, format: 'XLSX'}, finish: format => choices.push(format)};
  const dialog = load('common/components/ExportFormatDialog.vue', {'@/common/services/ExportFormat.js': service});
  const ctx = dialog.data();
  assert.deepEqual(copy(ctx.options.options.map(option => option.value)), ['CSV', 'XLSX']);
  dialog.methods.download.call(ctx); dialog.methods.cancel.call(ctx);
  assert.deepEqual(choices, ['XLSX', undefined]);
  for (const locale of ['en', 'es']) {
    const messages = JSON.parse(fs.readFileSync(path.join(__dirname, '../src/i18n', locale + '.json')));
    assert.ok(messages.export.download_format); assert.ok(messages.common.buttons.download);
  }
});
