const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const babel = require('@babel/core');
const {parse, compileTemplate} = require('@vue/compiler-sfc');
const policy = require('../src/common/services/protocol-content-policy.mjs');
const navigation = require('../src/biospecimen/services/specimen-context.mjs');
const copy = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));

function load(file, mocks = {}) {
  const filename = path.join(__dirname, '../src', file), source = fs.readFileSync(filename, 'utf8');
  const descriptor = file.endsWith('.vue') ? parse(source).descriptor : null;
  if (descriptor) assert.deepEqual(compileTemplate({source: descriptor.template.content, filename, id: 'receipt-test'}).errors, []);
  const {code} = babel.transformSync(descriptor ? descriptor.script.content : source, {filename, configFile: false, babelrc: false,
    plugins: [require('@babel/plugin-transform-modules-commonjs')]});
  const module = {exports: {}};
  vm.runInNewContext(code, {module, exports: module.exports, Date, Number, Map, Set,
    require: name => name === '@/biospecimen/services/specimen-context.mjs' ? navigation : mocks[name] || {}}, {filename});
  return module.exports.default;
}

const context = () => ({specimenId: 212, cpId: 1,
  cpr: {id: 32, cpId: 1, cpShortTitle: 'CCIN', contextOnly: true, participant: {pmis: []}},
  visit: {id: 43, cpId: 1, cprId: 32, contextOnly: true}});

test('specimen detail uses authorized identifiers without requesting participant, visit or consent records', async () => {
  let participantCalls = 0, visitCalls = 0;
  const parent = load('biospecimen/participants/DetailRoot.vue', {
    '@/biospecimen/services/Specimen.js': {getAuthorizedContext: async () => context()},
    '@/biospecimen/services/Cpr.js': {getCpr() { participantCalls++; throw Error('Unauthorized participant read'); }}
  });
  const instance = {cprId: 32, cpViewCtx: {getCp: () => ({id: 1}), isReadParticipantAllowed: () => false},
    $route: {params: {specimenId: 212, visitId: 43}}, requestId: 0, $osSvc: {ecValidationSvc: {getParticipantStatus() { throw Error('Consent read'); }}}};
  await parent.methods._setupCpr.call(instance);
  assert.equal(instance.loaded, true); assert.equal(instance.cpr.contextOnly, true); assert.equal(participantCalls, 0);
  assert.equal(instance.cpr.hasConsented, undefined, 'minimal context must not invent a consent authorization');
  const visit = load('biospecimen/participants/visits/DetailRoot.vue', {
    '@/biospecimen/services/Visit.js': {getVisit() { visitCalls++; throw Error('Unauthorized visit read'); }}
  });
  const child = {cpr: instance.cpr, specimenNavigation: instance.specimenNavigation, $route: instance.$route,
    cpViewCtx: {isReadVisitAllowed: () => false}, loaded: false};
  visit.methods._loadVisit.call(child, 43);
  assert.equal(child.loaded, true); assert.equal(child.visit.contextOnly, true); assert.equal(visitCalls, 0);
});

test('mismatched route identifiers and denied specimen reads cannot open a minimal participant context', async () => {
  for (const mode of ['wrong-visit', 'wrong-protocol', 'denied']) {
    const component = load('biospecimen/participants/DetailRoot.vue', {
      '@/biospecimen/services/Specimen.js': {getAuthorizedContext: async () => {
        if (mode === 'denied') throw Error('Denied');
        return context();
      }},
      '@/biospecimen/services/Cpr.js': {getCpr() { throw Error('Must not fall back to participant records'); }}
    });
    const instance = {cprId: 32, cpViewCtx: {getCp: () => ({id: mode === 'wrong-protocol' ? 2 : 1}), isReadParticipantAllowed: () => false},
      $route: {params: {specimenId: 212, visitId: mode === 'wrong-visit' ? 44 : 43}}, requestId: 0};
    await component.methods._setupCpr.call(instance);
    assert.equal(instance.loaded, false); assert.equal(instance.contextError, true);
  }
});

test('normal participant pages still use the existing protected endpoint', async () => {
  let participantCalls = 0;
  const component = load('biospecimen/participants/DetailRoot.vue', {
    '@/biospecimen/services/Cpr.js': {getCpr: async () => { participantCalls++; throw Error('Denied'); }}
  });
  const instance = {cprId: 32, cpViewCtx: {isReadParticipantAllowed: () => true}, $route: {params: {}}, requestId: 0};
  await component.methods._setupCpr.call(instance);
  assert.equal(participantCalls, 1); assert.equal(instance.loaded, false);
});

test('receipt serialization sends only an existing id and receipt fields', () => {
  const service = load('biospecimen/services/SpecimenReceipt.js');
  const payload = copy(service.payload([{specimen: {id: 212, label: 'do not change', status: 'Collected',
    initialQty: 99, collectionEvent: {time: 1}, children: [{id: 2}], extensionDetail: {secret: true},
    receivedEvent: {receivedQuality: 'Acceptable', user: {id: 7, emailAddress: 'private'}, time: 100,
      newLabel: 'do not relabel', collectionEvent: {}, receivedQtyDifferent: false}}}]));
  assert.deepEqual(payload, [{id: 212, receivedEvent: {receivedQuality: 'Acceptable', user: {id: 7}, time: 100, receivedQtyDifferent: false}}]);
});

async function matrix(access = {allowed: ['receipt'], createLineages: []}) {
  let loads = 0, writes = 0;
  const schema = {rows: ['receivedQuality', 'user', 'time', 'comments'].map(name => ({fields: [{name: 'specimen.receivedEvent.' + name}]}))};
  const receipt = load('biospecimen/services/SpecimenReceipt.js');
  const collection = load('biospecimen/services/SpecimenCollection.js', {
    '@/common/services/ProtocolContent.js': policy,
    '@/common/services/Util.js': {clone: copy},
    '@/biospecimen/services/SpecimenReceipt.js': receipt
  });
  const component = load('biospecimen/participants/visits/CollectSpecimens.vue', {
    '@/common/services/ProtocolContent.js': {...policy, getAccess: async () => access},
    '@/common/services/Util.js': {clone: copy},
    '@/common/services/FormUtil.js': {createCustomFieldsMap() {}, getFormSchema: () => ({rows: []})},
    '@/biospecimen/services/Specimen.js': {getReceivedEventAddEditFs: () => copy(schema)},
    '@/biospecimen/services/SpecimenCollection.js': {
      prepareRows: collection.prepareRows.bind(collection), validate: collection.validate.bind(collection),
      buildReceipts: collection.buildReceipts.bind(collection), buildSpecimens: collection.buildSpecimens.bind(collection),
      loadMatrix: async () => { loads++; return [{cpr: context().cpr, visit: {...context().visit, status: 'Complete'}, selectedIds: [212],
        specimens: [{id: 212, label: 'B-212', type: 'Blood', lineage: 'New', initialQty: 10, availableQty: 8,
          status: 'Collected', receivedEvent: {receivedQuality: 'To be Received'}, collectionEvent: {time: 20}}]}]; },
      saveMatrix: async () => { writes++; }
    },
    '@/common/services/Router.js': {replace() {}},
    '@/common/services/Alerts.js': {error() {}, success() {}}
  });
  const instance = {cpViewCtx: {getCp: () => ({id: 1}), getRole: () => 'Lab Reception',
    isReadSpecimenAllowed: () => true, isUpdateSpecimenAllowed: () => true, isCreateSpecimenAllowed: () => false,
    getSpecimenDict: async () => schema.rows.flatMap(row => row.fields), getSpecimenAddEditLayout: async () => ({rows: []}),
    getVisitDict() { throw Error('Must not load visit forms'); }}, $t: value => value, $route: {query: {ids: '212'}}, needsVisitSave: false};
  Object.assign(instance, component.data.call(instance));
  for (const [name, method] of Object.entries(component.methods)) instance[name] = method.bind(instance);
  await component.created.call(instance);
  return {instance, component, loads: () => loads, writes: () => writes};
}

test('the original matrix loads receipt without Create, participant, visit or collection forms', async () => {
  const result = await matrix();
  assert.equal(result.loads(), 1); assert.equal(result.instance.loadError, null); assert.equal(result.instance.rows.length, 1);
  const row = result.instance.rows[0];
  assert.equal(row.editable, false); assert.equal(row.receiptEditable, true);
  assert.equal(row.specimen.availableQty, 8, 'receipt must preserve the existing available quantity');
  assert.equal(row.specimen.collectionEvent, undefined);
  assert.equal(row.detailSchema.rows.length, 0);
  assert.equal(result.component.computed.matrixSchema.call({...result.instance, $t: value => value}).columns
    .find(field => field.name === 'specimen.initialQty').showCellWhen(row), false);
});

test('blocking receipt leaves an existing primary read only in the original matrix', async () => {
  const result = await matrix({allowed: ['collection'], createLineages: []});
  assert.equal(result.instance.rows[0].receiptEditable, false);
  assert.equal(result.component.computed.hasWork.call(result.instance), false);
});

test('the original matrix validates receipt forms and sends only receipt updates for existing primaries', async () => {
  const result = await matrix(), instance = result.instance, row = instance.rows[0];
  row.specimen.receivedEvent = {receivedQuality: 'Acceptable', user: {id: 7}, time: 100};
  instance.$nextTick = async () => {};
  instance.$refs = {['receipt-' + row.uid]: {validate: () => false}};
  await instance.save(); assert.equal(result.writes(), 0);
  instance.$refs['receipt-' + row.uid].validate = () => true;
  await instance.save(); assert.equal(result.writes(), 1);
});
