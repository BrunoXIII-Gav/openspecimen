const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const babel = require('@babel/core');
const {parse, compileTemplate} = require('@vue/compiler-sfc');

const copy = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
function load(relativePath, mocks = {}, globals = {}) {
  const filename = path.join(__dirname, '../src', relativePath);
  const source = fs.readFileSync(filename, 'utf8');
  const script = filename.endsWith('.vue') ? parse(source).descriptor.script.content : source;
  const {code} = babel.transformSync(script, {filename, configFile: false, babelrc: false,
    plugins: [require('@babel/plugin-transform-modules-commonjs')]});
  const module = {exports: {}};
  vm.runInNewContext(code, {module, exports: module.exports, Date, Number, Set, Map,
    require: name => mocks[name] || (name.startsWith('@/') ? {} : require(name)), console, ...globals}, {filename});
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
  Object.assign(source[0], {id: 100, label: 'B-100', status: 'Collected', availableQty: 6});
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

test('the tree action carries every selected requirement, specimen and event to the matrix; plugin behavior stays intact', async () => {
  const routes = [];
  const component = load('biospecimen/components/SpecimenTree.vue', {
    '@/common/services/Router.js': {goto: (...args) => routes.push(copy(args))}
  });
  const ctx = {cp, cpr, visit, $osSvc: {}, selectedPendingSpecimens: [
    {specimen: {reqId: 3}}, {specimen: {id: 102, reqId: 4}}
  ]};
  await component.methods.collectSpecimens.call(ctx);
  assert.deepEqual(routes[0], ['VisitCollectSpecimens', {cpId: 1, cprId: 31, visitId: -1},
    {eventId: 2, specimenIds: '102', reqIds: '3'}]);
  let options, opened;
  ctx.$osSvc.tmWfInstanceSvc = {
    createInstance: async (...args) => { options = args; return {id: 9}; },
    gotoInstance: id => { opened = id; }
  };
  ctx.$t = value => value;
  ctx._getBatchParams = (title, ids) => ids;
  ctx._getCollectVisitsWf = async () => 'sys-collect-visits';
  await component.methods.collectSpecimens.call(ctx);
  assert.equal(routes.length, 1);
  assert.equal(options[4][0].cpe.id, 2);
  assert.deepEqual(copy(options[5].params), {specimens: [102], requirements: [3]});
  assert.equal(opened, 9);
});

function editor(collect) {
  const {svc} = service();
  const routes = [], errors = [];
  const component = load('biospecimen/participants/visits/CollectSpecimens.vue', {
    '@/common/services/Util.js': {clone: copy},
    '@/common/services/FormUtil.js': {createCustomFieldsMap: () => {}},
    '@/biospecimen/services/SpecimenCollection.js': {...svc,
      validate: svc.validate.bind(svc), buildSpecimens: svc.buildSpecimens.bind(svc), collect},
    '@/biospecimen/services/Visit.js': {clearVisits: () => {}},
    '@/common/services/Router.js': {replace: (...args) => routes.push(copy(args))},
    '@/common/services/Alerts.js': {success: () => {}, error: error => errors.push(error)}
  });
  const ctx = {cpr: copy(cpr), visit: {...visit}, cpViewCtx: {getCp: () => cp, getRole: () => ({})}};
  Object.assign(ctx, component.data.call(ctx));
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

test('mounted matrix renders the real table and forms, validates hidden required details, then saves the hierarchy', async () => {
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
  mocks['@/biospecimen/services/SpecimenCollection.js'] = load('biospecimen/services/SpecimenCollection.js', mocks, globals);
  mocks['@/biospecimen/services/Specimen.js'] = {getReceivedEventAddEditFs: () => ({rows: []})};
  mocks['@/biospecimen/services/Visit.js'] = {getSpecimens: async () => tree(), clearVisits() {}};
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
    createElement: type => ({type, children: [], props: {}, style: {}}),
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
  const app = renderer.createApp(component, {cpr, visit});
  app.config.globalProperties.$t = translate;
  app.config.globalProperties.$route = {query: {reqIds: '3', specimenIds: ''}};
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
    'label', 'inline-message', 'divider', 'icon', 'user-dropdown', 'pv-dropdown', 'site-dropdown']) {
    app.component('os-' + name, stub);
  }
  app.directive('os-tooltip', {});
  const instance = app.mount({children: []});
  try {
    for (let tick = 0; tick < 12; tick++) await vue.nextTick();
    assert.deepEqual(runtimeErrors.map(error => error.message), []);
    assert.equal(instance.loadError, null);
    assert.equal(instance.rows.length, 3);
    assert.equal(instance.$refs.matrix.itemModels.length, 3);
    const visitForm = instance.$refs.visitForm;
    const prefix = 'visit.extensionDetail.attrsMap.';
    const visibleVisitFields = () => visitForm.formRows.flatMap(row => row.fields.map(field => field.name));
    const inputVisit = async (name, value) => {
      visitForm.formModel[prefix + name] = value;
      visitForm.handleInput(visitFields.find(field => field.name == prefix + name));
      await vue.nextTick();
    };
    assert.deepEqual(copy(visibleVisitFields()), [prefix + 'dropdown1']);
    await inputVisit('dropdown1', 'opcion 1');
    assert.deepEqual(copy(visibleVisitFields()), visitFields.map(field => field.name));
    assert.equal(visitForm.validate(), false, 'visible conditional fields must be mandatory');
    await inputVisit('textfield1', 'Detalle de visita');
    await inputVisit('textfield2', 'Otro dato de visita');
    assert.equal(visitForm.validate(), true);
    await inputVisit('dropdown1', 'opcion 2');
    await inputVisit('textfield1', '');
    await inputVisit('textfield2', '');
    assert.deepEqual(copy(visibleVisitFields()), [prefix + 'dropdown1']);
    assert.equal(visitForm.validate(), true, 'hidden conditional fields must not block collection');
    await inputVisit('dropdown1', 'opcion 1');
    assert.equal(visitForm.validate(), false);
    await inputVisit('textfield1', 'Detalle de visita');
    await inputVisit('textfield2', 'Otro dato de visita');
    errors.length = 0;
    for (const row of instance.rows.slice(0, -1)) {
      mocks['@/common/services/ExpressionUtil.js'].setValue(row, 'specimen.extensionDetail.attrsMap.processing', 'Procesado');
    }
    for (const row of instance.rows) {
      row.show = row.depth == 0;
      row.expanded = false;
    }
    await instance.save();
    assert.equal(requests.length, 0, 'required details on collapsed descendants must prevent the HTTP request');
    assert.ok(errors.length > 0);
    for (const row of instance.rows) {
      mocks['@/common/services/ExpressionUtil.js'].setValue(row, 'specimen.extensionDetail.attrsMap.processing', 'Procesado');
    }
    await vue.nextTick();
    await instance.save();
    assert.deepEqual(runtimeErrors.map(error => error.message), []);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].url, 'visits/collect');
    assert.equal(requests[0].data.visit.eventId, 2);
    assert.equal(requests[0].data.visit.extensionDetail.attrsMap.dropdown1, 'opcion 1');
    assert.equal(requests[0].data.visit.extensionDetail.attrsMap.textfield1, 'Detalle de visita');
    assert.equal(requests[0].data.specimens[0].children[0].children[0].reqId, 3);
    assert.equal(routes[0][1].visitId, 42);
  } finally {
    app.unmount();
  }
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
