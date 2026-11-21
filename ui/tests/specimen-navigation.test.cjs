const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const babel = require('@babel/core');
const {parse, compileTemplate} = require('@vue/compiler-sfc');

function load(file, mocks = {}, render = false) {
  const filename = path.join(__dirname, '../src', file), source = fs.readFileSync(filename, 'utf8');
  const descriptor = file.endsWith('.vue') ? parse(source).descriptor : null;
  const {code} = babel.transformSync(descriptor ? descriptor.script.content : source, {filename, configFile: false, babelrc: false,
    plugins: [require('@babel/plugin-transform-modules-commonjs')]});
  const module = {exports: {}};
  vm.runInNewContext(code, {module, exports: module.exports, Number, Date,
    require: name => mocks[name] || (name === 'vue' ? require('vue') : {})}, {filename});
  const component = module.exports.default;
  if (descriptor) {
    const result = compileTemplate({source: descriptor.template.content, filename, id: 'navigation-test'});
    assert.deepEqual(result.errors, []);
    if (render) {
      const output = {exports: {}};
      const compiled = babel.transformSync(result.code, {configFile: false, babelrc: false, plugins: [require('@babel/plugin-transform-modules-commonjs')]});
      vm.runInNewContext(compiled.code, {module: output, exports: output.exports, require}); component.render = output.exports.render;
    }
  }
  return component;
}
const cpr = () => ({id: 32, cpId: 1, ppid: 'NRC-0022', contextOnly: true, participant: {pmis: []}});
const visit = () => ({id: 43, cpId: 1, cprId: 32, contextOnly: true, collectionReady: true});

test('participant overview is a navigation container without participant, consent or form requests', async () => {
  const component = load('biospecimen/participants/DetailRoot.vue', {
    '@/biospecimen/services/SpecimenNavigation.js': {registration: async () => ({cpr: cpr()})},
    '@/biospecimen/services/Cpr.js': {getCpr() { throw Error('Forbidden participant read'); }}
  });
  const instance = {cprId: 32, requestId: 0,
    cpViewCtx: {getCp: () => ({id: 1}), isReadParticipantAllowed: () => false, isReadSpecimenAllowed: () => true},
    $route: {name: 'ParticipantsListItemDetail.Overview', params: {}}, $osSvc: {ecValidationSvc: {getParticipantStatus() { throw Error('Forbidden consent read'); }}}};
  await component.methods._setupCpr.call(instance);
  assert.equal(instance.loaded, true); assert.equal(instance.cpr.contextOnly, true); assert.equal(instance.cpr.ppid, 'NRC-0022'); assert.equal(instance.cpr.participant.firstName, undefined);
  const overview = load('biospecimen/participants/Overview.vue');
  let setup = false;
  overview.created.call({cpr: instance.cpr, _setupCpr() { setup = true; }}); assert.equal(setup, true);
});

test('restricted participant forms and restricted visit forms do not mount using navigation access', async () => {
  const participant = load('biospecimen/participants/DetailRoot.vue', {
    '@/biospecimen/services/SpecimenNavigation.js': {registration() { throw Error('Must not request a form context'); }}
  });
  const parent = {cprId: 32, requestId: 0, cpViewCtx: {isReadParticipantAllowed: () => false, isReadSpecimenAllowed: () => true},
    $route: {name: 'ParticipantsListItemDetail.Forms', params: {}}};
  await participant.methods._setupCpr.call(parent); assert.equal(parent.loaded, false); assert.equal(parent.contextError, true);
  const component = load('biospecimen/participants/visits/DetailRoot.vue');
  const child = {cpr: cpr(), requestId: 0, cpViewCtx: {isReadVisitAllowed: () => false},
    $route: {name: 'ParticipantsListItemVisitDetail.Forms', params: {visitId: 43}}};
  await component.methods._loadVisit.call(child, 43); assert.equal(child.loaded, false); assert.equal(child.contextError, true);
});

test('visit overview and matrix routes load minimal visit context without requiring a full visit', async () => {
  for (const name of ['ParticipantsListItemVisitDetail.Overview', 'VisitCollectSpecimens']) {
    let calls = 0;
    const component = load('biospecimen/participants/visits/DetailRoot.vue', {
      '@/biospecimen/services/SpecimenNavigation.js': {visit: async (cpId, cprId, visitId) => {
        assert.deepEqual([cpId, cprId, visitId], [1, 32, 43]); calls++; return visit();
      }},
      '@/biospecimen/services/Visit.js': {getVisit() { throw Error('Forbidden visit data read'); }}
    });
    const instance = {cpr: cpr(), requestId: 0, cpViewCtx: {isReadVisitAllowed: () => false}, $route: {name, params: {visitId: 43}}};
    await component.methods._loadVisit.call(instance, 43);
    assert.equal(calls, 1); assert.equal(instance.loaded, true); assert.equal(instance.visit.visitDate, undefined);
  }
});

test('a granted visit read remains usable when the participant is a minimal container', async () => {
  let fullReads = 0;
  const component = load('biospecimen/participants/visits/DetailRoot.vue', {
    '@/biospecimen/services/Visit.js': {getVisit: async () => { fullReads++; return {id: 43, visitDate: 123}; }},
    '@/common/services/FormUtil.js': {createCustomFieldsMap() {}}
  });
  const instance = {cpr: cpr(), requestId: 0, cpViewCtx: {isReadVisitAllowed: () => true}, $route: {params: {visitId: 43}}};
  await component.methods._loadVisit.call(instance, 43);
  assert.equal(instance.loaded, true); assert.equal(fullReads, 1); assert.equal(instance.visit.visitDate, 123);
});

test('rendered restricted visit keeps the specimen tree and matrix without requesting clinical schemas or references', async () => {
  const vue = require('vue'), {renderToString} = require('@vue/server-renderer');
  let treeReads = 0;
  const specimens = [{id: 212, label: 'B-212', lineage: 'New', status: 'Collected'}];
  const tree = {props: ['specimens'], render() { return vue.h('div', ['specimen-tree', this.$slots['after-cart']?.({selectedSpecimens: []})]); }};
  const collectionSvc = load('biospecimen/services/SpecimenCollection.js');
  const component = load('biospecimen/participants/visits/Overview.vue', {
    '@/biospecimen/components/SpecimenTree.vue': tree,
    '@/biospecimen/services/SpecimenCollection.js': collectionSvc,
    '@/common/services/ProtocolContent.js': {...require('../src/common/services/protocol-content-policy.mjs'), getAccess: async () => ({unrestricted: true})},
    '@/biospecimen/services/SpecimenReceipt.js': load('biospecimen/services/SpecimenReceipt.js'),
    '@/biospecimen/components/FieldReferences.vue': {render() { throw Error('Forbidden field reference read'); }},
    '@/biospecimen/services/Visit.js': {getSpecimens: async (cprId, eventId, visitId) => {
      assert.equal(cprId, 32); assert.equal(visitId, 43); treeReads++; return specimens;
    }}
  }, true);
  const app = vue.createSSRApp(component, {cpr: cpr(), visit: visit()});
  const cpViewCtx = {getCp: () => ({id: 1}), getRole: () => ({}),
    getVisitDict() { throw Error('Forbidden visit schema'); }, isReadSpecimenAllowed: () => true,
    isUpdateSpecimenAllowed: () => true, isCreateSpecimenAllowed: () => false, isPrintSpecimenAllowed: () => false};
  app.provide('cpViewCtx', cpViewCtx);
  app.config.globalProperties.$route = {query: {}}; app.config.globalProperties.$t = key => key; app.config.warnHandler = () => {};
  const slot = {render() { return vue.h('div', this.$slots.default?.()); }};
  for (const name of ['os-page-toolbar', 'os-grid', 'os-grid-column']) app.component(name, slot);
  app.component('os-button', {props: ['label'], render() { return vue.h('button', this.label); }});
  app.component('os-overview', {render() { throw Error('Forbidden visit overview'); }});
  const html = await renderToString(app);
  assert.equal(treeReads, 1); assert.equal(html.includes('specimen-tree'), true);
  assert.equal(html.includes('specimens.collection_matrix.open'), false, 'receipt stays hidden until collected primaries have loaded');
  assert.equal(html.includes('common.buttons.edit'), false);
  assert.equal(html.includes('common.buttons.delete'), false);
  const instance = {cpr: cpr(), visit: visit(), cpViewCtx, $route: {query: {}}};
  Object.assign(instance, component.data.call(instance));
  for (const [name, method] of Object.entries(component.methods)) instance[name] = method.bind(instance);
  instance.isReadSpecimenAllowed = true;
  await component.created.call(instance);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(instance.ctx.specimens[0].id, 212);
  assert.equal(component.computed.canOpenMatrix.call(instance), true);
  assert.equal(instance.ctx.dict.length, 0);
});

test('minimal visit requests reject a protocol, participant or visit mismatch', async () => {
  for (const mode of ['cp', 'cpr', 'visit']) {
    const service = load('biospecimen/services/SpecimenNavigation.js', {
      '@/common/services/HttpClient.js': {get: async () => ({cpId: mode === 'cp' ? 2 : 1,
        cpr: {id: mode === 'cpr' ? 33 : 32}, visit: {id: mode === 'visit' ? 44 : 43}})}
    });
    await assert.rejects(service.visit(1, 32, 43), /Invalid navigation context/);
  }
});


test('a readable participant overview does not load visit or consent schemas without those permissions', async () => {
  const component = load('biospecimen/participants/Overview.vue');
  let setup = 0;
  const instance = {cpr: {id: 32}, ctx: {}, cpViewCtx: {
    getCprDict: async () => [], getRole: async () => ({}), isReadConsentAllowed: () => false, isReadVisitAllowed: () => false,
    getConsentsDict() { throw Error('Forbidden consent schema'); }, getVisitDict() { throw Error('Forbidden visit schema'); }
  }, _setupCpr() { setup++; }, _loadMoreMenuOptions() {}};
  component.created.call(instance);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(setup, 1); assert.equal(instance.ctx.visitDict.length, 0); assert.equal(instance.ctx.consentsDict.length, 0);
});


test('the visit matrix does not request visit fields for a navigation-only visit', () => {
  const component = load('biospecimen/participants/visits/CollectSpecimens.vue');
  assert.equal(component.computed.needsVisitSave.call({cpr: {id: 32}, visit: visit(), rows: []}), false);
});

test('specimen tree links stay relative to the current UI application path', () => {
  const component = load('common/components/SpecimenDescription.vue', {
    '@/biospecimen/services/CollectionProtocol.js': {},
    '@/common/services/Router.js': {getUrl: () => '#/cp-view/1/participants/34/visit/47/specimen/239/overview?eventId=2&reqId=5'},
    '@/common/services/Util.js': {},
    '@/biospecimen/services/SpecimenDisplay.js': {}
  });
  const link = component.computed.link.call({
    noLink: false,
    object: {id: 239, eventId: 2, reqId: 5},
    $route: {query: {view: 'specimens'}}
  });
  assert.equal(link, '#/cp-view/1/participants/34/visit/47/specimen/239/overview?eventId=2&reqId=5');
});

test('the original query list displays only PPID and uses no clinical list config, queries or filters', async () => {
  const calls = [];
  const navigationSvc = load('biospecimen/services/SpecimenNavigation.js', {
    '@/common/services/HttpClient.js': {get: async (url, query) => {
      calls.push({url, query});
      return {registrations: [{id: 32, cpId: 1, ppid: 'NRC-0022'}], size: 1};
    }}
  });
  const component = load('common/components/QueryListView.vue', {
    '@/common/services/HttpClient.js': {get() { throw Error('Must not load the clinical list configuration'); }, post() { throw Error('Must not query participant data'); }},
    '@/common/services/Util.js': {uriEncode: () => ''},
    '@/common/services/ExpressionUtil.js': {eval: () => '#participant'}
  });
  const instance = {name: 'participant-list-view', objectId: 1, url: '#participant',
    dataSource: navigationSvc.participantListSource(1), $emit() {}};
  Object.assign(instance, component.data.call(instance));
  for (const [name, method] of Object.entries(component.methods)) instance[name] = method.bind(instance);
  await component.created.call(instance);
  await instance.loadList({filters: {ppid: 'NRC', name: 'do not send'}, pageSize: 101, orderDirection: 'asc'});
  assert.deepEqual(Array.from(instance.schema.columns, column => column.caption), ['PPID']);
  assert.deepEqual(Array.from(instance.schema.filters, field => field.name), ['ppid']);
  assert.equal(instance.data[0].column.a_0, 'NRC-0022');
  assert.equal(instance.data[0].hidden.cprId, 32);
  assert.equal(calls[0].url, 'specimens/navigation'); assert.equal(calls[0].query.ppid, 'NRC');
  assert.equal(calls[0].query.name, undefined); assert.equal(calls[0].query.descending, false);
  await instance.loadListSize(); assert.equal(instance.size, 1); assert.equal(calls[1].query.includeCount, true);
});

test('restricted event rows reuse the original visit card components with only the Event column', () => {
  const visitSvc = load('biospecimen/services/Visit.js');
  for (const name of ['OccurredVisits', 'PendingVisits', 'MissedVisits']) {
    const component = load('biospecimen/participants/' + name + '.vue', {
      '@/biospecimen/services/Visit.js': visitSvc
    });
    const instance = {navigationOnly: true};
    component.created.call(instance);
    assert.deepEqual(Array.from(instance.tabFields, field => field.name), ['visit.description']);
    assert.equal(instance.tabFields[0].component, 'os-visit-event-desc');
    assert.equal(instance.tabFields[0].data({visit: visit()}).showLink, true);
    assert.equal(fs.readFileSync(path.join(__dirname, '../src/biospecimen/participants/' + name + '.vue'), 'utf8')
      .includes(':showRowActions="!navigationOnly"'), true);
  }
});

test('the original participant overview loads event cards without personal details', async () => {
  const event = {...visit(), eventId: 2, eventLabel: 'Sample collection', status: 'Complete'};
  const component = load('biospecimen/participants/Overview.vue', {
    '@/biospecimen/services/SpecimenNavigation.js': {visits: async () => [event]},
    './OccurredVisits.vue': {name: 'OriginalOccurredVisits'}, './PendingVisits.vue': {name: 'OriginalPendingVisits'}, './MissedVisits.vue': {name: 'OriginalMissedVisits'}
  });
  const instance = {cpr: cpr(), ctx: {visits: [], visitDict: []}, navigationRequest: 0};
  await component.methods._loadNavigationVisits.call(instance);
  assert.equal(instance.ctx.visits[0].eventLabel, 'Sample collection');
  assert.equal(component.computed.hasOccurredVisits.call(instance), true);
  assert.equal(component.components.OccurredVisits.name, 'OriginalOccurredVisits');
  assert.equal(component.components.NavigationVisits, undefined);
});
