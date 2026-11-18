const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const babel = require('@babel/core');
const {parse, compileTemplate} = require('@vue/compiler-sfc');
const navigation = require('../src/biospecimen/services/cp-list-view.mjs');

function load(file, mocks = {}, render = false) {
  const filename = path.join(__dirname, '../src', file);
  const source = fs.readFileSync(filename, 'utf8');
  const descriptor = file.endsWith('.vue') ? parse(source).descriptor : null;
  const {code} = babel.transformSync(descriptor ? descriptor.script.content : source, {filename, configFile: false, babelrc: false,
    plugins: [require('@babel/plugin-transform-modules-commonjs')]});
  const module = {exports: {}};
  vm.runInNewContext(code, {module, exports: module.exports, console: {log() {}},
    require: name => name == '@/biospecimen/services/cp-list-view.mjs' ? navigation :
      name == '@/biospecimen/services/SpecimenNavigation.js' ? (mocks[name] || {participantListSource: () => ({config: {filters: [{caption: 'PPID'}]}})}) :
      name == '@/common/services/ProtocolContent.js' ? {getAccess: async () => ({allowed: ['receipt']}),
        allows: (access, key) => !!access?.allowed?.includes(key)} : mocks[name] || {}}, {filename});
  const component = module.exports.default;
  if (descriptor) {
    const compiled = compileTemplate({source: descriptor.template.content, filename, id: 'cp-list-navigation'});
    assert.deepEqual(compiled.errors, []);
    if (render) {
      const output = {exports: {}};
      const rendered = babel.transformSync(compiled.code, {configFile: false, babelrc: false,
        plugins: [require('@babel/plugin-transform-modules-commonjs')]});
      vm.runInNewContext(rendered.code, {module: output, exports: output.exports, require});
      component.render = output.exports.render;
    }
  }
  return component;
}

function context(grants) {
  const Context = load('biospecimen/participants/CpViewContext.js', {
    '@/common/services/Authorization.js': {isAllowed: ({resources, operations}) =>
      resources.some(resource => operations.some(operation => (grants[resource] || []).includes(operation)))}
  });
  const result = Object.create(Context.prototype);
  result.cp = {id: 1, shortTitle: 'CCIN', activityStatus: 'Active'};
  result.cpSites = ['INCN']; result.accessBasedOnMrn = false;
  result._loadAccessRights();
  return result;
}

const reception = () => context({CollectionProtocol: ['Read'], Specimen: ['Read', 'Create', 'Update']});

test('reception role keeps the participant navigation without gaining participant data access', async () => {
  const cpViewCtx = reception(), routes = [];
  const component = load('biospecimen/participants/DefListView.vue', {
    '@/biospecimen/services/CollectionProtocol.js': {getWorkflowProperty: async () => 'participants_list'},
    '@/common/services/Router.js': {goto: (...args) => routes.push(args)}
  });
  await component.created.call({cpViewCtx});
  assert.equal(routes[0][2].view, 'participants_list');
  assert.equal(cpViewCtx.isReadParticipantAllowed(), false);
  assert.equal(cpViewCtx.isReadSpecimenAllowed(), true);
});

test('direct participant URL keeps navigation and discards protected participant filters', () => {
  const routes = [];
  const component = load('biospecimen/participants/List.vue', {
    '@/common/services/Router.js': {replace: (...args) => routes.push(args)}
  });
  const instance = {ui: {}, cpViewCtx: reception(), view: 'participants_list', filters: 'a participant search'};
  Object.assign(instance, component.data.call(instance));
  Object.defineProperty(instance, 'navigationOnly', {get: () => component.computed.navigationOnly.call(instance)});
  Object.defineProperty(instance, 'currentViewAllowed', {get: () => component.computed.currentViewAllowed.call(instance)});
  instance._loadMoreOptions = () => {};
  component.created.call(instance);
  assert.equal(instance.ctx.view, 'participants_list');
  assert.equal(instance.ctx.query, undefined);
  assert.equal(routes.length, 0);
});

async function renderList(cpViewCtx) {
  const vue = require('vue');
  const {renderToString} = require('@vue/server-renderer');
  const lists = [];
  const component = load('biospecimen/participants/List.vue', {
    '@/common/services/Router.js': {replace() {}},
  }, true);
  component.methods._loadMoreOptions = () => {};
  const app = vue.createSSRApp(component, {view: 'participants_list'});
  app.provide('cpViewCtx', cpViewCtx); app.provide('ui', {});
  app.config.globalProperties.$t = key => key;
  app.config.globalProperties.$route = {query: {}};
  app.config.warnHandler = () => {};
  const slots = {render() { return vue.h('div', this.$slots.default?.()); }};
  for (const name of ['os-page', 'os-page-head', 'os-page-body', 'os-page-toolbar']) app.component(name, slots);
  app.component('os-query-list-view', {props: ['name', 'dataSource'], render() { lists.push(this.name); return vue.h('div', this.dataSource ? 'original-ppid-list' : ''); }});
  return {html: await renderToString(app), lists};
}

test('rendered reception page reuses the original participant list with a restricted data source', async () => {
  const result = await renderList(reception());
  assert.deepEqual(result.lists, ['participant-list-view']);
  assert.equal(result.html.includes('original-ppid-list'), true);
  assert.equal(result.html.includes('participants.view_participants'), false);
});

test('protocol-read-only role shows a message without requesting either clinical list', async () => {
  const result = await renderList(context({CollectionProtocol: ['Read']}));
  assert.deepEqual(result.lists, []);
  assert.equal(result.html.includes('participants.no_list_access'), true);
});

test('participant protocols always use the PPID list and specimen-centric protocols retain their specimen list', () => {
  assert.equal(navigation.resolveCpListView({}, 'participants_list', true, true), 'participants_list');
  assert.equal(navigation.resolveCpListView({}, 'specimens_list', true, true), 'participants_list');
  assert.equal(navigation.resolveCpListView({}, 'specimens_list', false, true), 'participants_list');
  assert.equal(navigation.resolveCpListView({}, 'specimens_list', true, false), 'participants_list');
  assert.equal(context({ParticipantDeid: ['Read']}).isReadParticipantAllowed(), true);
  assert.equal(navigation.resolveCpListView({specimenCentric: true}, 'participants_list', true, true), 'specimens_list');
});

test('an old global specimen-list URL redirects to PPID navigation and discards specimen filters', () => {
  const routes = [];
  const component = load('biospecimen/participants/List.vue', {
    '@/common/services/Router.js': {replace: (...args) => routes.push(args)}
  });
  const instance = {ui: {}, cpViewCtx: reception(), view: 'specimens_list', filters: 'specimen-only-filter'};
  Object.assign(instance, component.data.call(instance));
  Object.defineProperty(instance, 'navigationOnly', {get: () => component.computed.navigationOnly.call(instance)});
  Object.defineProperty(instance, 'currentViewAllowed', {get: () => component.computed.currentViewAllowed.call(instance)});
  component.created.call(instance);
  assert.equal(instance.ctx.query, undefined);
  assert.deepEqual(JSON.parse(JSON.stringify(routes[0])), ['ParticipantsList', {cpId: 1}, {view: 'participants_list'}]);
});
