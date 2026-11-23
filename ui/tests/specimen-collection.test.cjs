const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const babel = require('@babel/core');
const {parse, compileTemplate} = require('@vue/compiler-sfc');
const contentPolicy = require('../src/common/services/protocol-content-policy.mjs');
const contentAccess = {...contentPolicy, getAccess: async () => ({unrestricted: true})};
const matrixNavigation = require('../src/biospecimen/services/specimen-matrix-navigation.mjs');

const copy = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
function load(relativePath, mocks = {}, globals = {}) {
  const filename = path.join(__dirname, '../src', relativePath);
  const source = fs.readFileSync(filename, 'utf8');
  const script = filename.endsWith('.vue') ? parse(source).descriptor.script.content : source;
  const {code} = babel.transformSync(script, {filename, configFile: false, babelrc: false,
    plugins: [require('@babel/plugin-transform-modules-commonjs')]});
  const module = {exports: {}};
  vm.runInNewContext(code, {module, exports: module.exports, Date, Number, Set, Map,
    require: name => mocks[name] || (name === '@/biospecimen/services/specimen-matrix-navigation.mjs' ? matrixNavigation :
      name.startsWith('@/') || name.endsWith('.vue') ? {} : require(name)), console, ...globals}, {filename});
  return module.exports.default;
}

const cp = {id: 1, shortTitle: 'CP', specimenLabelFmt: 'PRIMARY', derivativeLabelFmt: 'DERIVED',
  aliquotLabelFmt: 'ALIQUOT', aliquotQtyReq: true};
const cpr = {id: 31, cpId: 1, ppid: 'P-31', hasConsented: true};
const visit = {eventId: 2, status: 'Pending'};
function tree() {
  return [{reqId: 1, lineage: 'New', type: 'Blood', initialQty: 10, children: [
    {reqId: 2, lineage: 'Derived', type: 'Plasma', initialQty: 4, parentConsumedQty: 6, children: [
      {reqId: 3, lineage: 'Aliquot', type: 'Plasma', initialQty: 2},
      {reqId: 4, lineage: 'Aliquot', type: 'Plasma', initialQty: 2}
    ]}
  ]}, {reqId: 5, lineage: 'New', type: 'Blood', initialQty: 3}];
}
function service() {
  const requests = [];
  const svc = load('biospecimen/services/SpecimenCollection.js', {
    '@/common/services/Util.js': {clone: copy},
    '@/common/services/ProtocolContent.js': contentAccess,
    '@/biospecimen/services/SpecimenReceipt.js': load('biospecimen/services/SpecimenReceipt.js'),
    '@/biospecimen/services/SpecimenProcessing.js': load('biospecimen/services/SpecimenProcessing.js', {'@/common/services/Util.js': {clone: copy}}),
    '@/common/services/HttpClient.js': {post: async (url, data) => {
      requests.push({url, data: copy(data)});
      return {visit: {id: 42, cpId: 1, cprId: 31, eventId: 2}};
    }}
  });
  return {svc, requests};
}
const select = reqIds => ({reqIds, specimenIds: [], explicit: true});

test('selecting one aliquot includes its pending ancestors, excludes siblings and keeps requirement formats', () => {
  const {svc} = service();
  const source = tree();
  source[0].children[0].children[0].labelFmt = 'REQ-SPECIFIC';
  const rows = svc.prepareRows(source, select([3]), cp, cpr, visit);
  assert.deepEqual(copy(rows.map(row => row.specimen.reqId)), [1, 2, 3]);
  assert.deepEqual(copy(rows.map(row => row.depth)), [0, 1, 2]);
  assert.equal(rows[2].parentUid, rows[1].uid);
  assert.equal(rows[2].specimen.labelFmt, 'REQ-SPECIFIC');
  assert.equal(rows[1].specimen.labelFmt, 'DERIVED');
  assert.equal(rows[0].specimen.labelFmt, 'PRIMARY');
  assert.equal(source[0].status, undefined);
  assert.equal(svc.validate(rows), null);
  const payload = copy(svc.buildSpecimens(rows));
  assert.equal(payload.length, 1);
  assert.equal(payload[0].children[0].children.length, 1);
  assert.equal(payload[0].children[0].children[0].reqId, 3);
  assert.equal(payload[0].children[0].children[0].labelFmt, undefined);
  assert.equal(payload[0].children[0].children[0].label, undefined);
  assert.equal(payload[0].eventId, 2);
  assert.equal(payload[0].id, undefined);
});

test('automatic labels preserve preprinted values and manual formats require actual labels', () => {
  const {svc} = service();
  const rows = svc.prepareRows(tree(), select([1]), {...cp, manualSpecLabelEnabled: true}, cpr, visit);
  assert.equal(svc.validate(rows), 'specimens.label_req');
  rows[0].specimen.label = '  TUBE-001  ';
  assert.equal(svc.validate(rows), null);
  assert.equal(svc.buildSpecimens(rows)[0].label, 'TUBE-001');
  rows[0].cp.manualSpecLabelEnabled = false;
  assert.equal(svc.buildSpecimens(rows)[0].label, 'TUBE-001');
});

test('partial collection leaves pending requirements unsaved and collected ancestors are references only', () => {
  const {svc} = service();
  const source = tree();
  Object.assign(source[0], {id: 100, primaryReceived: true, label: 'B-100', status: 'Collected', availableQty: 6});
  Object.assign(source[0].children[0], {id: 101, label: 'P-101', status: 'Collected', availableQty: 4});
  Object.assign(source[0].children[0].children[0], {id: 102, status: 'Pending'});
  const rows = svc.prepareRows(source, select([3, 4]), cp, cpr, {id: 42, status: 'Complete'});
  rows[3].specimen.status = 'Pending';
  assert.equal(svc.validate(rows), null);
  const result = copy(svc.buildSpecimens(rows));
  assert.deepEqual(Object.keys(result[0]).sort(), ['children', 'id']);
  assert.deepEqual(Object.keys(result[0].children[0]).sort(), ['children', 'id']);
  assert.equal(result[0].children[0].children.length, 1);
  assert.equal(result[0].children[0].children[0].id, 102);
  assert.equal(result[0].children[0].children[0].reqId, 3);
});

test('reloading after collection does not recreate an already collected aliquot', () => {
  const {svc} = service();
  const source = tree();
  Object.assign(source[0].children[0].children[0], {id: 102, status: 'Collected', label: 'A-102'});
  const rows = svc.prepareRows(source, select([3, 4]), cp, cpr, visit);
  const children = svc.buildSpecimens(rows)[0].children[0].children;
  assert.deepEqual(copy(children.map(specimen => specimen.reqId)), [4]);
});

test('a collected child cannot be saved underneath a pending or missed parent', () => {
  const {svc} = service();
  for (const status of ['Pending', 'Missed Collection', 'Not Collected']) {
    const rows = svc.prepareRows(tree(), select([3]), cp, cpr, visit);
    rows[1].specimen.status = status;
    assert.equal(svc.validate(rows), 'specimens.collection_matrix.parent_not_collected');
  }
  const source = tree();
  Object.assign(source[0], {id: 100, status: 'Missed Collection'});
  assert.equal(svc.validate(svc.prepareRows(source, select([3]), cp, cpr, visit)),
    'specimens.collection_matrix.parent_not_collected');
});

test('validates combined consumption, missing parent quantities, negative values and duplicate labels', () => {
  const {svc} = service();
  const rows = svc.prepareRows(tree(), select([3, 4]), cp, cpr, visit);
  rows[2].specimen.initialQty = 3;
  assert.equal(svc.validate(rows), 'specimens.parent_quantity_insufficient');
  rows[2].specimen.initialQty = 2;
  rows[1].specimen.initialQty = null;
  assert.equal(svc.validate(rows), 'specimens.parent_quantity_required');
  rows[1].specimen.initialQty = 4;
  rows[0].specimen.initialQty = -1;
  assert.equal(svc.validate(rows), 'specimens.collection_matrix.invalid_quantity');
  rows[0].specimen.initialQty = 10;
  rows[2].specimen.label = rows[3].specimen.label = 'DUPLICATE';
  assert.equal(svc.validate(rows), 'specimens.collection_matrix.duplicate_label');
});

test('collection events belong only to collected primaries and custom fields survive serialization', () => {
  const {svc} = service();
  const rows = svc.prepareRows(tree(), select([3]), cp, cpr, visit);
  for (const row of rows) {
    row.specimen.collectionEvent = {time: 123};
    row.specimen.receivedEvent = {receivedQuality: 'Acceptable'};
    row.specimen.extensionDetail = {attrsMap: {value: 'custom'}};
  }
  const result = svc.buildSpecimens(rows)[0];
  assert.equal(result.collectionEvent.time, 123);
  assert.equal(result.children[0].collectionEvent, undefined);
  assert.equal(result.children[0].children[0].receivedEvent, undefined);
  assert.equal(result.children[0].children[0].extensionDetail.attrsMap.value, 'custom');
});

test('explicit empty and unrelated selections cannot silently collect all specimens', () => {
  const {svc} = service();
  for (const ids of [[], [999]]) {
    const rows = svc.prepareRows(tree(), select(ids), cp, cpr, visit);
    assert.equal(rows.length, 0);
    assert.equal(svc.validate(rows), 'specimens.collection_matrix.nothing_to_collect');
  }
});

test('entry without explicit selection follows the protocol collection mode', () => {
  const {svc} = service();
  assert.equal(svc.prepareRows(tree(), {}, cp, cpr, visit).length, 5);
  const rows = svc.prepareRows(tree(), {}, {...cp, visitCollectionMode: 'PRIMARY_SPMNS'}, cpr, visit);
  assert.deepEqual(copy(rows.map(row => row.specimen.reqId)), [1, 5]);
});

test('pending visits and new visits use the combined transactional API; complete visits use specimen collection', async () => {
  const {svc, requests} = service();
  const specimens = svc.buildSpecimens(svc.prepareRows(tree(), select([3]), cp, cpr, visit));
  await svc.collect({...visit, id: -1, cprId: 31}, specimens);
  assert.equal(requests[0].url, 'visits/collect');
  assert.equal(requests[0].data.visit.id, undefined);
  assert.equal(requests[0].data.visit.status, 'Complete');
  assert.equal(requests[0].data.visit.eventId, 2);
  await svc.collect({...visit, id: 42}, specimens);
  assert.equal(requests[1].url, 'visits/collect');
  assert.equal(requests[1].data.visit.id, 42);
  await svc.collect({...visit, id: 42, status: 'Complete'}, specimens);
  assert.equal(requests[2].url, 'specimens/collect');
});

test('tree collection reloads the plan, excludes blocked selections and returns to its origin', async () => {
  const routes = [], errors = [], {svc} = service(), source = tree();
  Object.assign(source[0], {id: 100, primaryReceived: true, status: 'Collected'});
  Object.assign(source[1], {id: 105, primaryReceived: true, status: 'Collected'});
  source[1].children = [{reqId: 6, lineage: 'Derived'}];
  const component = load('biospecimen/components/SpecimenTree.vue', {
    '@/common/services/Router.js': {goto: (...args) => routes.push(copy(args))},
    '@/common/services/Alerts.js': {error: value => errors.push(value)},
    '@/common/services/ProtocolContent.js': contentAccess,
    '@/biospecimen/services/SpecimenCollection.js': svc,
    '@/biospecimen/services/Visit.js': {getSpecimens: async () => source}
  });
  const ctx = {cp, cpr, visit: {...visit, id: 42}, ctx: {selectedSpecimens: []}, selectedSpecimens: []};
  await component.methods.collectSpecimens.call(ctx);
  assert.equal(routes[0][2].reqIds, '2,6');
  ctx.ctx.selectedSpecimens = ctx.selectedSpecimens = [source[0].children[0], source[0].children[0].children[0]];
  await component.methods.collectSpecimens.call(ctx);
  assert.equal(routes[1][2].reqIds, '2', 'do not collect a child even when its pending parent is in the same selection');
  ctx.ctx.selectedSpecimens = ctx.selectedSpecimens = [source[0].children[0].children[0]];
  await component.methods.collectSpecimens.call(ctx);
  assert.equal(routes.length, 2); assert.equal(errors.length, 1);
  component.methods.onItemsSelection.call(ctx, [source[0].children[0]]);
  assert.equal(ctx.ctx.selectedSpecimens.length, 1, 'selecting a collapsed row must not select descendants implicitly');
});

test('tree collection button distinguishes primary collection from processing', () => {
  const {svc} = service();
  const component = load('biospecimen/components/SpecimenTree.vue', {
    '@/biospecimen/services/ProtocolContent.js': contentAccess,
    '@/biospecimen/services/SpecimenCollection.js': svc
  });
  const source = tree();
  const ctx = {
    cpr, cpViewCtx: {isCreateSpecimenAllowed: () => true}, contentAccess: {unrestricted: true},
    specimens: source, ctx: {selectedSpecimens: []}
  };
  ctx._collectionAccess = component.methods._collectionAccess.bind(ctx);
  ctx._isPrimaryCollection = component.methods._isPrimaryCollection.bind(ctx);

  assert.equal(component.computed.collectActionLabel.call(ctx), 'participants.collect');

  Object.assign(source[0], {id: 100, status: 'Collected', primaryReceived: true});
  Object.assign(source[1], {id: 101, status: 'Collected', primaryReceived: true});
  assert.equal(component.computed.collectActionLabel.call(ctx), 'participants.process');

  const pendingPrimary = {lineage: 'New', status: 'Pending'};
  const pendingDerived = {lineage: 'Derived', status: 'Pending'};
  ctx.ctx.selectedSpecimens = [{specimen: pendingDerived}];
  assert.equal(component.computed.collectActionLabel.call(ctx), 'participants.process');
  ctx.ctx.selectedSpecimens = [{specimen: pendingPrimary}, {specimen: pendingDerived}];
  assert.equal(component.computed.collectActionLabel.call(ctx), 'participants.collect');

  ctx.ctx.selectedSpecimens = [{specimen: {lineage: 'New', status: 'Pending', specimensPool: [{id: 1}]}}];
  assert.equal(component.computed.collectActionLabel.call(ctx), 'participants.process');
});

function editor(collect) {
  const {svc} = service();
  const routes = [], errors = [];
  const component = load('biospecimen/participants/visits/CollectSpecimens.vue', {
    '@/biospecimen/services/SpecimenProcessing.js': load('biospecimen/services/SpecimenProcessing.js', {'@/common/services/Util.js': {clone: copy}}),
    '@/common/services/ProtocolContent.js': contentAccess,
    '@/common/services/Util.js': {clone: copy},
    '@/common/services/FormUtil.js': {createCustomFieldsMap: () => {}},
    '@/biospecimen/services/SpecimenCollection.js': {...svc,
      validate: svc.validate.bind(svc), buildSpecimens: svc.buildSpecimens.bind(svc), buildReceipts: svc.buildReceipts.bind(svc),
      saveMatrix: svc.saveMatrix.bind(svc), collect},
    '@/biospecimen/services/Visit.js': {clearVisits: () => {}},
    '@/common/services/Router.js': {replace: (...args) => routes.push(copy(args))},
    '@/common/services/Alerts.js': {success: () => {}, error: error => errors.push(error)}
  });
  const ctx = {cpr: copy(cpr), visit: {...visit}, cpViewCtx: {getCp: () => cp, getRole: () => ({})}};
  Object.assign(ctx, component.data.call(ctx));
  ctx.contentAccess = {unrestricted: true};
  for (const [name, method] of Object.entries(component.methods)) ctx[name] = method.bind(ctx);
  ctx.rows = svc.prepareRows(tree(), select([3]), cp, cpr, visit);
  ctx.rows.forEach(row => { row.matrixFields = []; });
  ctx.needsVisitSave = true;
  ctx.$refs = {visitForm: {validate: () => true}};
  ctx.$nextTick = async () => {};
  return {ctx, routes, errors};
}

test('matrix blocks duplicate submissions, returns to the saved visit and can retry server failures', async () => {
  let finish, calls = 0;
  const {ctx, routes} = editor(() => { calls++; return new Promise(resolve => { finish = resolve; }); });
  const saving = ctx.save();
  await Promise.resolve();
  await ctx.save();
  assert.equal(calls, 1);
  finish({id: 42, eventId: 2});
  await saving;
  assert.equal(ctx.saving, false);
  assert.deepEqual(routes[0], ['ParticipantsListItemVisitDetail.Overview',
    {cpId: 1, cprId: 31, visitId: 42}, {eventId: 2}]);
  const failed = editor(async () => { throw new Error('server rejected'); });
  await assert.rejects(failed.ctx.save(), /server rejected/);
  assert.equal(failed.ctx.saving, false);
  assert.equal(failed.routes.length, 0);
});

test('complete-information entry points carry their own page as the matrix return destination', () => {
  for (const [file, method, name] of [
    ['visits/Overview.vue', 'openMatrix', 'ParticipantsListItemVisitDetail.Overview'],
    ['specimens/Overview.vue', 'openMatrix', 'ParticipantsListItemSpecimenDetail.Overview'],
    ['List.vue', 'openSelectedMatrix', 'ParticipantsList']
  ]) {
    const routes = [];
    const component = load('biospecimen/participants/' + file, {
      './EventsSummaryList.vue': {},
      '@/common/services/Router.js': {goto: (...args) => routes.push(copy(args))}
    });
    const origin = {name, params: {cpId: '1', cprId: '31', visitId: '42', specimenId: '100'}, query: {eventId: '2'}};
    component.methods[method].call({canOpenMatrix: true, ctx: {cp}, cpr, visit: {id: 42, eventId: 2},
      specimen: {id: 100}, selectedSpecimens: [{id: 100}], $route: origin});
    assert.deepEqual(matrixNavigation.matrixReturnRoute(1, routes[0][2].returnTo), origin);
  }
});

test('saving and cancelling return to the originating visit or specimen, including query context after a refresh', async () => {
  for (const name of ['ParticipantsListItemVisitDetail.Overview', 'ParticipantsListItemSpecimenDetail.Overview']) {
    const origin = {name, params: {cpId: '1', cprId: '31', visitId: '42', ...(name.includes('Specimen') ? {specimenId: '100'} : {})},
      query: {eventId: '2', view: 'participants_list', filters: 'saved-filter'}};
    const {ctx, routes} = editor();
    ctx.needsVisitSave = false;
    ctx.cpr.contextOnly = true;
    ctx.visit = {id: 42, eventId: 2, contextOnly: true, $specimens: ['stale']};
    ctx.$route = {query: {returnTo: matrixNavigation.matrixOrigin(origin)}};
    await ctx.save();
    assert.deepEqual(routes[0], [origin.name, origin.params, origin.query]);
    assert.equal(ctx.visit.$specimens, undefined);
    ctx.cancel();
    assert.deepEqual(routes[1], routes[0]);
  }
});

test('an older matrix link still returns a restricted user to the visit without a global specimen list', async () => {
  const {ctx, routes} = editor();
  ctx.needsVisitSave = false; ctx.cpr.contextOnly = true;
  ctx.visit = {id: 42, eventId: 2, contextOnly: true};
  await ctx.save();
  assert.deepEqual(routes[0], ['ParticipantsListItemVisitDetail.Overview', {cpId: 1, cprId: 31, visitId: 42}, {eventId: 2}]);
});

test('matrix origin accepts only overview and list routes belonging to the current protocol', () => {
  const origin = {name: 'ParticipantsListItemVisitDetail.Overview', params: {cpId: 2, cprId: 31, visitId: 42}};
  assert.equal(matrixNavigation.matrixReturnRoute(1, matrixNavigation.matrixOrigin(origin)), null);
  assert.equal(matrixNavigation.matrixReturnRoute(1, 'broken JSON'), null);
  assert.equal(matrixNavigation.matrixReturnRoute(1, JSON.stringify({name: 'VisitCollectSpecimens', params: {cpId: 1}})), null);
  assert.equal(matrixNavigation.matrixOrigin({name: 'VisitCollectSpecimens'}), undefined);
});

test('configured required fields and expanded custom forms prevent saving invalid data', async () => {
  let calls = 0;
  const {ctx} = editor(async () => { calls++; return {id: 42}; });
  ctx.$refs['details-r-3'] = {validate: () => false};
  await ctx.save();
  assert.equal(calls, 0);
  ctx.$refs['details-r-3'] = {validate: () => true};
  ctx.$refs.visitForm.validate = () => false;
  await ctx.save();
  assert.equal(calls, 0);
});

test('changing a parent to pending also leaves its selected descendants pending', () => {
  const {ctx} = editor(async () => ({id: 42}));
  ctx.rows[0].specimen.status = 'Pending';
  ctx.rowInput({item: ctx.rows[0], field: {name: 'specimen.status'}});
  assert.ok(ctx.rows.every(row => row.specimen.status == 'Pending'));
});

for (const lineage of ['Derived', 'Aliquot']) test('mounted ' + lineage + ' collection uses one native table and shared or individual required event fields', async () => {
  // A Vue host renderer exercises component setup, refs, expansion and Vuelidate
  // without a browser or any access to a deployed database.
  const vue = require('vue');
  const getValue = (object, name) => name.split('.').reduce((value, key) => value && value[key], object);
  const util = {clone: copy, fd: getValue, sanitizeHtml: value => value};
  const messages = JSON.parse(fs.readFileSync(path.join(__dirname, '../src/i18n/es.json')));
  const translate = key => getValue(messages, key) || key;
  const errors = [], requests = [], routes = [];
  const quiet = {log() {}, warn() {}, error() {}};
  const globals = {console: quiet, setTimeout, clearTimeout,
    window: {osSvc: {i18nSvc: {msg: translate}}, osUi: {currentUser: {id: 1}}}};
  const mocks = {
    '@/forms/services/Form.js': {getDefinition: async id => ({id, rows: [[
      {name: 'notes', udn: 'notes', type: 'stringTextField', caption: 'Processing notes', mandatory: true}
    ]]})},
    '@/common/services/ProtocolContent.js': contentAccess,
    '@/common/services/Util.js': util,
    '@/common/services/HttpClient.js': {
      addListener() {}, removeListener() {}, get: async () => tree(),
      post: async (url, data) => { requests.push({url, data: copy(data)}); return {visit: {id: 42, eventId: 2}}; }
    },
    '@/common/services/I18n.js': {msg: translate},
    '@/common/services/Alerts.js': {error: error => errors.push(error), success() {}},
    '@/common/services/Router.js': {getUrl: () => '#', replace: (...args) => routes.push(copy(args))},
    '@/biospecimen/services/CollectionProtocol.js': {getEventDescription: () => 'Visita planificada'}
  };
  const actualUtil = load('common/services/Util.js', {
    ...mocks, 'vue-clipboard3': () => ({toClipboard() {}})
  }, globals);
  util.fd = actualUtil.fd.bind(actualUtil);
  mocks['@/common/services/ExpressionUtil.js'] = load('common/services/ExpressionUtil.js', mocks, globals);
  mocks['@/common/services/FieldFactory.js'] = load('common/services/FieldFactory.js', mocks, globals);
  mocks['@/common/services/FormUtil.js'] = load('common/services/FormUtil.js', mocks, globals);
  mocks['@/biospecimen/services/SpecimenReceipt.js'] = load('biospecimen/services/SpecimenReceipt.js', mocks, globals);
  mocks['@/biospecimen/services/SpecimenProcessing.js'] = load('biospecimen/services/SpecimenProcessing.js', mocks, globals);
  mocks['@/biospecimen/services/SpecimenProcessing.js'].getForms = async () => [];
  mocks['@/biospecimen/services/SpecimenCollection.js'] = load('biospecimen/services/SpecimenCollection.js', mocks, globals);
  mocks['@/biospecimen/services/Specimen.js'] = {getReceivedEventAddEditFs: () => ({rows: []})};
  const source = tree();
  Object.assign(source[0], {id: 100, primaryReceived: true, status: 'Collected', availableQty: 10});
  Object.assign(source[1], {id: 105, primaryReceived: true, status: 'Collected', availableQty: 10});
  source[1].children = [{reqId: 6, lineage: 'Derived', type: 'Plasma', initialQty: 4, parentConsumedQty: 6}];
  if (lineage == 'Aliquot') {
    Object.assign(source[0].children[0], {id: 200, status: 'Collected', availableQty: 4});
    source[1].children = [];
  }
  mocks['@/biospecimen/services/Visit.js'] = {getSpecimens: async () => source, clearVisits() {}};
  const rendered = relative => {
    const component = load(relative, mocks, globals);
    const filename = path.join(__dirname, '../src', relative);
    const {descriptor} = parse(fs.readFileSync(filename, 'utf8'));
    const compiled = compileTemplate({source: descriptor.template.content, filename, id: 'matrix-test'});
    const {code} = babel.transformSync(compiled.code, {configFile: false, babelrc: false,
      plugins: [require('@babel/plugin-transform-modules-commonjs')]});
    const module = {exports: {}};
    vm.runInNewContext(code, {module, exports: module.exports, require, console: quiet});
    component.render = module.exports.render;
    return component;
  };
  const component = rendered('biospecimen/participants/visits/CollectSpecimens.vue');
  const table = rendered('common/components/TableForm.vue');
  const form = rendered('common/components/Form.vue');
  const specimenFields = load('biospecimen/schemas/specimens/specimen.js', mocks, globals).fields;
  const visitFields = mocks['@/common/services/FormUtil.js'].deFormToDict({rows: [
    [{name: 'dropdown1', udn: 'selection', type: 'combobox', caption: 'Selección', mandatory: true,
      pvs: [{value: 'opcion 1'}, {value: 'opcion 2'}]}],
    [{name: 'textfield1', udn: 'detail', type: 'stringTextField', caption: 'Detalle', mandatory: true,
      showWhen: "dropdown1 == 'opcion 1'"}],
    [{name: 'textfield2', type: 'stringTextField', caption: 'Otro detalle', mandatory: true,
      showWhen: "selection == 'opcion 1'"}],
    [{name: 'textfield3', type: 'stringTextField', caption: 'Contexto de visita',
      showWhen: "$extendedObj.extensionDetail.attrsMap.dropdown1 == 'opcion 1'"}]
  ]}, 'visit.extensionDetail.attrsMap.');
  specimenFields.push({type: 'text', name: 'specimen.extensionDetail.attrsMap.processing', label: 'Procesamiento',
    validations: {required: {message: 'Required processing detail'}}});
  const layout = {rows: ['specimen.createdOn', 'specimen.extensionDetail.attrsMap.processing']
    .map(name => ({fields: [{name}]}))};
  const host = {
    createElement: type => ({type, children: [], props: {}, style: {}, addEventListener() {}, removeEventListener() {}}),
    createText: text => ({text}), createComment: text => ({text}),
    setText: (node, text) => { node.text = text; }, setElementText: (node, text) => { node.text = text; },
    parentNode: node => node.parent,
    nextSibling: node => node.parent && node.parent.children[node.parent.children.indexOf(node) + 1],
    insert(node, parent, anchor) {
      if (node.parent) host.remove(node);
      node.parent = parent;
      const index = anchor ? parent.children.indexOf(anchor) : -1;
      parent.children.splice(index < 0 ? parent.children.length : index, 0, node);
    },
    remove(node) {
      if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1);
      node.parent = null;
    },
    patchProp: (node, name, oldValue, value) => { node.props[name] = value; }
  };
  const renderer = vue.createRenderer(host);
  const app = renderer.createApp(component, {cpr, visit: {...visit, id: 42, status: 'Complete'}});
  app.config.globalProperties.$t = translate;
  app.config.globalProperties.$route = {query: {mode: 'collect'}};
  const runtimeErrors = [];
  app.config.errorHandler = error => runtimeErrors.push(error);
  app.config.warnHandler = () => {};
  app.provide('cpViewCtx', {
    getCp: () => cp, getRole: () => ({}),
    getVisitDict: async () => visitFields,
    getVisitAddEditLayout: async () => ({rows: visitFields.map(field => ({fields: [{name: field.name}]}))}),
    getSpecimenDict: async () => specimenFields, getSpecimenAddEditLayout: async () => layout
  });
  app.component('os-table-form', table);
  app.component('os-form', form);
  const stub = {props: ['modelValue'], setup(props, {slots}) {
    return () => vue.h('div', [String(props.modelValue || ''), ...(slots.default ? slots.default() : [])]);
  }};
  for (const name of ['page', 'page-head', 'page-body', 'breadcrumb', 'message', 'button', 'button-group',
    'span', 'input-text', 'input-number', 'specimen-measure', 'storage-position', 'dropdown', 'date-picker',
    'label', 'inline-message', 'divider', 'icon', 'user-dropdown', 'pv-dropdown', 'site-dropdown', 'boolean-checkbox']) {
    app.component('os-' + name, stub);
  }
  app.directive('os-tooltip', {});
  const instance = app.mount({children: []});
  try {
    for (let tick = 0; tick < 40 && !instance.$refs.matrix; tick++) await vue.nextTick();
    assert.deepEqual(runtimeErrors.map(error => error.message), []);
    assert.equal(instance.loadError, null);
    assert.equal(instance.displayRows.length, 2);
    assert.ok(instance.displayRows.every(row => row.specimen.lineage == lineage));
    const components = (vnode, name) => {
      const found = [];
      const walk = node => {
        if (!node) return;
        if (node.component) {
          if (node.component.type === name) found.push(node.component.proxy);
          walk(node.component.subTree);
        } else if (Array.isArray(node.children)) node.children.forEach(walk);
      };
      walk(vnode);
      return found;
    };
    const tables = () => components(instance.$.subTree, table);
    assert.equal(tables().length, 1);
    assert.equal(tables()[0].itemModels.length, 2);
    assert.equal(instance.$refs.visitForm, undefined, 'a subsequent level does not require resaving visit fields');
    await instance.save();
    assert.equal(requests.length, 0, 'required specimen details must prevent submitting');
    for (const row of instance.displayRows) {
      mocks['@/common/services/ExpressionUtil.js'].setValue(row, 'specimen.extensionDetail.attrsMap.processing', 'Procesado');
    }
    instance.processingForms = [{formId: 176, formCtxtId: 50, formCaption: 'evento plasma'}];
    assert.equal(instance.processingGroups.length, 1);
    const group = instance.processingGroups[0];
    await instance.processingFormChanged(group, 50);
    await vue.nextTick();
    assert.ok(instance.$refs['processing-group-' + group.uid], 'selecting the form immediately displays shared fields');
    await instance.save();
    assert.equal(requests.length, 0, 'required event fields must prevent submitting');
    const refs = instance.$refs['processing-group-' + group.uid];
    const eventForm = Array.isArray(refs) ? refs[0] : refs;
    eventForm.formModel.notes = 'Shared processing';
    eventForm.handleInput(group.schema.rows[0].fields[0]);
    await vue.nextTick();
    group.shared = false;
    instance.processingShare(group);
    await vue.nextTick();
    assert.equal(instance.$refs['processing-group-' + group.uid]?.length || 0, 0);
    for (const row of group.rows) {
      assert.ok(instance.$refs['processing-' + row.uid], 'unchecking same data opens individual fields');
      assert.equal(row.processing.data.notes, 'Shared processing');
    }
    group.rows[0].processing.data.notes = 'Individual processing';
    assert.equal(group.rows[1].processing.data.notes, 'Shared processing', 'individual answers must not alias');
    const payload = mocks['@/biospecimen/services/SpecimenCollection.js'].buildSpecimens(instance.rows);
    const savedChildren = lineage == 'Derived' ? payload.map(parent => parent.children[0]) : payload[0].children[0].children;
    assert.equal(savedChildren[0].processingEvent.data.notes, 'Individual processing');
    assert.equal(savedChildren[1].processingEvent.data.notes, 'Shared processing');
    group.shared = true;
    instance.processingShare(group);
    await vue.nextTick();
    assert.ok(instance.$refs['processing-group-' + group.uid]);
    await instance.save();
    assert.deepEqual(runtimeErrors.map(error => error.message), []);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].url, 'specimens/matrix');
    assert.equal(requests[0].data.specimens[0].processingEvent, undefined, 'do not modify the collected parent');
    assert.equal(routes[0][1].visitId, 42);
  } finally {
    app.unmount();
  }
});

test('primary-only scope omits pending children and their required forms from collection and payload', () => {
  const {svc} = service();
  const rows = svc.prepareRows(tree(), {}, cp, cpr, visit, {allowed: ['collection'], createLineages: ['New']});
  assert.deepEqual(copy(rows.map(row => row.specimen.reqId)), [1, 5]);
  assert.equal(rows.every(row => row.specimen.lineage == 'New'), true);
  assert.equal(svc.buildSpecimens(rows).some(specimen => specimen.children), false);
  assert.equal(svc.prepareRows(tree(), select([3]), cp, cpr, visit, {allowed: ['collection'], createLineages: ['New']}).length, 0);
  assert.equal(svc.prepareRows(tree(), select([3]), cp, cpr, visit, {allowed: ['collection'], createLineages: ['Aliquot']}).length, 0,
    'a forbidden pending parent must not be implicitly collected');
});

test('matrix template compiles and new translations exist in supported locales', () => {
  const file = path.join(__dirname, '../src/biospecimen/participants/visits/CollectSpecimens.vue');
  const {descriptor} = parse(fs.readFileSync(file, 'utf8'));
  const result = compileTemplate({source: descriptor.template.content, filename: file, id: 'collection'});
  assert.deepEqual(result.errors, []);
  for (const locale of ['es', 'en', 'nl']) {
    const messages = JSON.parse(fs.readFileSync(path.join(__dirname, '../src/i18n/' + locale + '.json')));
    assert.ok(messages.specimens.collection_matrix.saved);
    assert.ok(messages.specimens.collection_matrix.visit_details);
    assert.ok(messages.specimens.collection_matrix.parent_not_collected);
  }
});


test('one matrix resumes collection, receipt and processing across separate roles without recreating completed stages', () => {
  const {svc} = service();
  const collected = tree();
  const collector = {allowed: ['collection'], createLineages: ['New'], canCreate: true, canUpdate: false};
  const first = svc.prepareRows(collected, {}, cp, cpr, {...visit, id: 42}, collector);
  assert.deepEqual(copy(first.map(row => row.specimen.reqId)), [1, 5]);
  assert.equal(svc.buildReceipts(first).length, 0);
  Object.assign(collected[0], {id: 100, label: 'B-100', status: 'Collected', availableQty: 10,
    receivedEvent: {receivedQuality: 'To be Received'}});
  Object.assign(collected[1], {id: 105, label: 'B-105', status: 'Collected', availableQty: 3,
    receivedEvent: {receivedQuality: 'To be Received'}});
  const receiver = {allowed: ['receipt'], createLineages: [], canCreate: false, canUpdate: true};
  const second = svc.prepareRows(collected, {}, cp, cpr, {...visit, id: 42}, receiver);
  assert.equal(second.filter(row => row.editable).length, 0);
  assert.equal(second.filter(row => row.receiptEditable).length, 2);
  second.forEach(row => row.specimen.receivedEvent = {receivedQuality: 'Acceptable', user: {id: 7}, time: 100});
  assert.equal(svc.validate(second), null);
  assert.deepEqual(copy(svc.buildSpecimens(second)), []);
  assert.equal(svc.buildReceipts(second).length, 2);
  collected.forEach(specimen => specimen.receivedEvent = {receivedQuality: 'Acceptable'});
  const processor = {allowed: [], createLineages: ['Derived', 'Aliquot'], canCreate: true, canUpdate: true};
  const third = svc.prepareRows(collected, {}, {...cp, visitCollectionMode: 'PRIMARY_SPMNS'}, cpr, {...visit, id: 42}, processor);
  assert.deepEqual(copy(third.filter(row => row.editable).map(row => row.specimen.lineage)), ['Derived', 'Aliquot', 'Aliquot']);
  assert.equal(third.some(row => row.receiptEditable), false);
  const payload = copy(svc.buildSpecimens(third));
  assert.equal(payload.length, 1); assert.deepEqual(Object.keys(payload[0]).sort(), ['children', 'id']);
  assert.equal(payload[0].id, 100); assert.equal(payload[0].children[0].children.length, 2);
  let nextId = 200;
  const complete = specimen => { Object.assign(specimen, {id: specimen.id || nextId++, status: 'Collected'}); (specimen.children || []).forEach(complete); };
  collected.forEach(complete);
  const last = svc.prepareRows(collected, {}, cp, cpr, {...visit, id: 42}, processor);
  assert.equal(last.some(row => row.editable || row.receiptEditable), false);
  assert.deepEqual(copy(svc.buildSpecimens(last)), []);
});

test('opening a selected collected primary includes its pending descendants and isolates repeated requirements in different visits', () => {
  const {svc} = service(), source = tree();
  Object.assign(source[0], {id: 100, primaryReceived: true, status: 'Collected', availableQty: 10});
  const access = {allowed: [], createLineages: ['Derived', 'Aliquot'], canCreate: true};
  const selection = {specimenIds: [100], explicit: true, includeDescendants: true, uidPrefix: 'v-42-'};
  const rows = svc.prepareRows(source, selection, cp, cpr, {...visit, id: 42}, access);
  assert.equal(rows.length, 4); assert.equal(rows.filter(row => row.editable).length, 3);
  assert.equal(rows[2].parentUid, rows[1].uid);
  const other = svc.prepareRows(source, {...selection, uidPrefix: 'v-43-'}, cp, cpr, {...visit, id: 43}, access);
  assert.equal(rows.some(row => other.some(item => item.uid === row.uid)), false);
});


test('resuming existing collected samples does not silently discard receipts to save visit fields', () => {
  const component = load('biospecimen/participants/visits/CollectSpecimens.vue');
  assert.equal(component.computed.needsVisitSave.call({visit: {id: 42, status: 'Pending'}, cpr,
    rows: [{originalStatus: 'Collected', receiptEditable: true}]}), false);
  assert.equal(component.computed.needsVisitSave.call({visit: {id: 42, status: 'Pending'}, cpr,
    rows: [{originalStatus: 'Pending'}]}), true);
  const {svc} = service();
  assert.equal(svc.prepareRows(tree(), {}, cp, {...cpr, contextOnly: true}, {id: 42, status: 'Pending'},
    {allowed: ['collection'], createLineages: ['New']}).length, 0);
});


test('the receipt visit panel shows primaries and omits only unauthorized planned processing', () => {
  const {svc} = service(), source = tree();
  Object.assign(source[0], {id: 100, primaryReceived: true, status: 'Collected'});
  const access = {unrestricted: false, allowed: ['receipt'], createLineages: ['New']};
  const result = svc.visibleVisitSpecimens(source, access);
  assert.equal(result.length, 2); assert.equal(result[0].id, 100); assert.equal(result[0].children.length, 0);
  assert.equal(source[0].children.length, 1, 'keep the original plan for other authorized roles');
  assert.equal(svc.visibleVisitSpecimens(source, {unrestricted: true, createLineages: []})[0].children.length, 1);
  Object.assign(source[0].children[0], {id: 101, status: 'Collected'});
  assert.equal(svc.visibleVisitSpecimens(source, access)[0].children[0].id, 101, 'existing specimen reading keeps its base permission');
});


test('collection proceeds through the shallowest pending level and never recreates completed rows', () => {
  const {svc} = service(), source = tree(), mode = {mode: 'collect'};
  const ids = rows => copy(rows.filter(row => row.editable).map(row => row.specimen.reqId));
  assert.deepEqual(ids(svc.prepareRows(source, mode, cp, cpr, visit)), [1, 5]);
  Object.assign(source[0], {id: 100, primaryReceived: true, status: 'Collected', availableQty: 10});
  assert.deepEqual(ids(svc.prepareRows(source, mode, cp, cpr, visit)), [5], 'finish the pending primary before advancing');
  Object.assign(source[1], {id: 105, primaryReceived: true, status: 'Collected'});
  source[1].children = [{reqId: 6, lineage: 'Derived', type: 'Serum', parentConsumedQty: 1, initialQty: 1}];
  assert.deepEqual(ids(svc.prepareRows(source, mode, cp, cpr, visit)), [2, 6]);
  Object.assign(source[0].children[0], {id: 200, status: 'Collected', availableQty: 4});
  assert.deepEqual(ids(svc.prepareRows(source, mode, cp, cpr, visit)), [6], 'do not include deeper aliquots while this level has work');
  Object.assign(source[1].children[0], {id: 206, status: 'Collected'});
  const aliquots = svc.prepareRows(source, mode, cp, cpr, visit);
  assert.deepEqual(ids(aliquots), [3, 4]);
  assert.equal(svc.validate(aliquots), null);
  const payload = svc.buildSpecimens(aliquots);
  assert.deepEqual(Object.keys(payload[0]).sort(), ['children', 'id']);
  assert.deepEqual(Object.keys(payload[0].children[0]).sort(), ['children', 'id']);
  source[0].children[0].children.forEach((child, i) => Object.assign(child, {id: 300 + i, status: 'Collected'}));
  assert.equal(svc.prepareRows(source, mode, cp, cpr, visit).length, 0);
});

test('an explicit selection includes only eligible marked samples without adding parents, children or siblings', () => {
  const {svc} = service(), source = tree();
  Object.assign(source[0], {id: 100, primaryReceived: true, status: 'Collected'});
  Object.assign(source[1], {id: 105, primaryReceived: true, status: 'Collected'});
  source[1].children = [{reqId: 6, lineage: 'Derived'}];
  const choose = reqIds => svc.prepareRows(source, {...select(reqIds), mode: 'collect'}, cp, cpr, visit);
  assert.deepEqual(copy(choose([2, 3, 6]).filter(row => row.editable).map(row => row.specimen.reqId)), [2, 6]);
  assert.equal(choose([3, 4]).length, 0, 'a selected pending parent does not make its children eligible');
  assert.equal(choose([]).length, 0);
  assert.deepEqual(copy(choose([6]).filter(row => row.editable).map(row => row.specimen.reqId)), [6]);
  Object.assign(source[0].children[0], {id: 200, status: 'Collected'});
  assert.deepEqual(copy(choose([3, 6]).filter(row => row.editable).map(row => row.specimen.reqId)), [3, 6],
    'explicit selection may span levels when all immediate parents were already collected');
});

test('collection eligibility observes creation scopes and blocks every descendant of a pending parent', () => {
  const {svc} = service(), source = tree(), access = {allowed: ['form:176'], canCreate: true, createLineages: ['Derived', 'Aliquot']};
  assert.equal(svc.collectionPlan(source, {}, access).length, 0);
  Object.assign(source[0], {id: 100, primaryReceived: true, status: 'Collected'});
  assert.deepEqual(copy(svc.collectionPlan(source, {}, access).map(item => item.specimen.reqId)), [2]);
  assert.equal(svc.collectionPlan(source, {}, {...access, canCreate: false}).length, 0);
  assert.equal(svc.collectionPlan(source, select([3]), access).length, 0);
  source[0].status = 'Missed Collection';
  assert.equal(svc.collectionPlan(source, select([2]), access).length, 0);
});

test('the level is based on hierarchy rather than lineage, including direct aliquots and nested derivatives', () => {
  const {svc} = service(), source = tree();
  Object.assign(source[0], {id: 100, primaryReceived: true, status: 'Collected'});
  Object.assign(source[1], {id: 105, primaryReceived: true, status: 'Collected'});
  source[1].children = [{reqId: 6, lineage: 'Aliquot'}];
  source[0].children[0].children.push({reqId: 7, lineage: 'Derived'});
  assert.deepEqual(copy(svc.collectionPlan(source).map(item => item.specimen.reqId)), [2, 6]);
  Object.assign(source[0].children[0], {id: 200, status: 'Collected'});
  Object.assign(source[1].children[0], {id: 206, status: 'Collected'});
  assert.deepEqual(copy(svc.collectionPlan(source).map(item => item.specimen.reqId)), [3, 4, 7]);
});

test('one top processing selector applies independently to every selected child and preserves shared answers when toggled', () => {
  const {svc} = service(), source = tree(), processing = load('biospecimen/services/SpecimenProcessing.js', {'@/common/services/Util.js': {clone: copy}});
  Object.assign(source[0], {id: 100, primaryReceived: true, status: 'Collected'});
  Object.assign(source[1], {id: 105, primaryReceived: true, status: 'Collected'});
  source[1].children = [{reqId: 6, lineage: 'Aliquot'}];
  const rows = svc.prepareRows(source, {mode: 'collect'}, cp, cpr, visit);
  const groups = processing.collectionGroups(rows);
  assert.equal(groups.length, 1); assert.equal(groups[0].rows.length, 2);
  const group = groups[0]; group.form = {formId: 176, formCtxtId: 50};
  group.data = {notes: 'Common', id: 999, appData: {objectId: 999}};
  assert.ok(group.rows.every(row => processing.payload(row).data.notes == 'Common'));
  assert.equal(processing.payload(group.rows[0]).data.id, undefined);
  group.shared = false; processing.share(group);
  group.rows[0].processing.data.notes = 'Individual';
  assert.equal(processing.payload(group.rows[1]).data.notes, 'Common');
  group.shared = true; processing.share(group);
  assert.equal(processing.payload(group.rows[1]).data.notes, 'Individual');
  group.rows[0].specimen.status = 'Pending';
  assert.equal(processing.payload(group.rows[0]), undefined);
});

test('saving an anticipated visit opens the persisted visit instead of its empty virtual origin', async () => {
  const origin = {name: 'ParticipantsListItemVisitDetail.Overview', params: {cpId: '1', cprId: '31', visitId: '-1'},
    query: {eventId: '2', view: 'participants_list'}};
  const {ctx, routes} = editor(async () => ({id: 47, cprId: 31, eventId: 2, status: 'Complete'}));
  ctx.visit = {...visit, id: -1};
  ctx.$route = {query: {returnTo: matrixNavigation.matrixOrigin(origin)}};
  await ctx.save();
  assert.equal(routes.length, 1);
  assert.equal(routes[0][0], origin.name);
  assert.equal(Number(routes[0][1].visitId), 47, 'returning to -1 recreates an empty anticipated visit despite successful persistence');
  assert.equal(routes[0][1].cprId, '31');
  assert.deepEqual(routes[0][2], origin.query);
});

test('cancelling an anticipated visit preserves its original route without claiming it was saved', () => {
  const origin = {name: 'ParticipantsListItemVisitDetail.Overview', params: {cpId: '1', cprId: '31', visitId: '-1'}, query: {eventId: '2'}};
  const {ctx, routes} = editor();
  ctx.visit = {...visit, id: -1};
  ctx.$route = {query: {returnTo: matrixNavigation.matrixOrigin(origin)}};
  ctx.cancel();
  assert.deepEqual(routes[0], [origin.name, origin.params, origin.query]);
});


test('primary receipt is required for derived and direct aliquot collection, with or without explicit selection', () => {
  const {svc} = service(), source = tree();
  Object.assign(source[0], {id: 100, status: 'Collected', primaryReceived: false});
  Object.assign(source[1], {id: 105, status: 'Collected', primaryReceived: false});
  source[1].children = [{reqId: 6, lineage: 'Aliquot'}];
  for (const selection of [{}, select([2, 6])]) {
    assert.equal(svc.collectionPlan(source, selection).length, 0);
    assert.equal(svc.prepareRows(source, {...selection, mode: 'collect'}, cp, cpr, visit).length, 0);
  }
  source[0].primaryReceived = true;
  assert.deepEqual(copy(svc.collectionPlan(source).map(item => item.specimen.reqId)), [2]);
  source[1].primaryReceived = true;
  assert.deepEqual(copy(svc.collectionPlan(source).map(item => item.specimen.reqId)), [2, 6]);
});

test('nested children cannot bypass primary receipt through an already collected derivative', () => {
  const {svc} = service(), source = tree();
  Object.assign(source[0], {id: 100, status: 'Collected', primaryReceived: false});
  Object.assign(source[0].children[0], {id: 200, status: 'Collected', primaryReceived: false});
  const selection = select([3, 4]);
  assert.equal(svc.collectionPlan(source, selection).length, 0);
  source[0].primaryReceived = true;
  // Only readiness is needed: this processing role has no access to receipt event fields.
  const access = {allowed: [], createLineages: ['Aliquot'], canCreate: true};
  assert.deepEqual(copy(svc.collectionPlan(source, selection, access).map(item => item.specimen.reqId)), [3, 4]);
  source[0].primaryReceived = false;
  source[0].children[0].primaryReceived = true;
  assert.equal(svc.collectionPlan(source, selection, access).length, 0, 'always trust the original primary state');
});
