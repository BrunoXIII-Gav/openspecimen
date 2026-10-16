const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const babel = require('@babel/core');
const {parse, compileTemplate} = require('@vue/compiler-sfc');
const {compile} = require('@vue/compiler-dom');
const Vue = require('vue');

const copy = value => JSON.parse(JSON.stringify(value));
const workflowName = 'specimenTypeAbbreviations';

function load(file, mocks = {}) {
  const filename = path.join(__dirname, '../src', file);
  const source = fs.readFileSync(filename, 'utf8');
  const script = file.endsWith('.vue') ? parse(source).descriptor.script.content : source;
  const {code} = babel.transformSync(script, {filename, configFile: false, babelrc: false,
    plugins: [require('@babel/plugin-transform-modules-commonjs')]});
  const module = {exports: {}};
  vm.runInNewContext(code, {module, exports: module.exports, require: name => mocks[name] || {}}, {filename});
  return module.exports.default;
}

function editor(cpId = 1, store = {}, types) {
  const requests = [];
  types = types || [
    {id: 10, value: 'Fluid', props: {}},
    {id: 21, value: 'Plasma', parentValue: 'Fluid', labelCode: 'plasma', props: {abbreviation: 'Pl'}},
    {id: 22, value: 'Serum', parentValue: 'Fluid', activityStatus: 'Disabled', props: {abbreviation: 'Se'}}
  ];
  const http = {
    async get(url, opts) {
      requests.push({method: 'GET', url, opts: copy(opts || {})});
      if (url === 'permissible-values/v') {
        return copy(types.slice(opts.startAt, opts.startAt + opts.maxResults));
      }
      return {workflows: copy(store[url.split('/')[1]] || {})};
    },
    async put(url, body) {
      requests.push({method: 'PUT', url, body: copy(body)});
      if (http.failCpSave) throw new Error('Protocol save failed');
      return copy(body);
    },
    async patch(url, body) {
      requests.push({method: 'PATCH', url, body: copy(body)});
      if (http.failSave) throw new Error('Save failed');
      const id = url.split('/')[1];
      const workflows = store[id] = {...store[id]};
      for (const workflow of body) workflows[workflow.name] = copy(workflow);
      return {workflows: copy(workflows)};
    }
  };
  const cpSvc = load('biospecimen/services/CollectionProtocol.js', {'@/common/services/HttpClient.js': http});
  const pvSvc = load('administrative/services/PermissibleValueAdmin.js', {'@/common/services/HttpClient.js': http});
  const component = load('biospecimen/cps/SpecimenTypeAbbreviations.vue', {
    '@/biospecimen/services/CollectionProtocol.js': cpSvc,
    '@/administrative/services/PermissibleValueAdmin.js': pvSvc,
    '@/common/services/Alerts.js': {success() {}}
  });
  const ctx = {...component.data(), cp: {id: cpId}, editMode: false, saving: false,
    $emit() {}, $te: key => key === 'plasma', $t: key => 'translated:' + key};
  for (const [name, method] of Object.entries(component.methods)) ctx[name] = method.bind(ctx);
  return {ctx, requests, store, http, cpSvc, types, pvSvc};
}

test('loads local overrides and global defaults without inheriting another workflow scope', async () => {
  const {ctx, requests} = editor(1, {1: {
    [workflowName]: {name: workflowName, data: {version: 1, abbreviations: {21: 'P'}}}
  }});
  await ctx.load();
  assert.equal(ctx.types.length, 2);
  assert.equal(ctx.abbreviations[21], 'P');
  assert.equal(ctx.types[0].props.abbreviation, 'Pl');
  assert.equal(ctx.typeLabel(ctx.types[0]), 'translated:plasma');
  assert.equal(ctx.typeLabel(ctx.types[1]), 'Serum');
  assert.equal(ctx.loading, false);
  assert.equal(ctx.loadFailed, false);
  assert.ok(!requests.some(request => request.url.includes('/-1/')));
});

test('saves only the selected protocol workflow and preserves other workflows and catalog values', async () => {
  const store = {1: {labelSettings: {name: 'labelSettings', data: {specimen: {rules: []}}}},
    2: {[workflowName]: {name: workflowName, data: {version: 1, abbreviations: {21: 'PLAS'}}}}};
  const {ctx, requests, cpSvc, types} = editor(1, store);
  await ctx.load();
  ctx.edit();
  ctx.draft[21] = ' P ';
  ctx.draft[22] = 'L';
  ctx.draftIntermediateTypeIds = ['21'];
  await ctx.save();
  const request = requests.find(request => request.method === 'PATCH');
  assert.equal(request.url, 'collection-protocols/1/workflows');
  assert.deepEqual(request.body, [{name: workflowName, data: {version: 1, abbreviations: {21: 'P', 22: 'L'}, intermediateTypeIds: ['21']}}]);
  assert.ok(store[1].labelSettings);
  assert.equal(store[2][workflowName].data.abbreviations[21], 'PLAS');
  assert.equal(types[1].props.abbreviation, 'Pl');
  assert.equal(ctx.editMode, false);
  cpSvc.workflows = {};
  await ctx.load();
  assert.deepEqual(copy(ctx.abbreviations), {21: 'P', 22: 'L'});
  assert.deepEqual(copy(ctx.intermediateTypeIds), ['21']);
});

test('clearing an override removes it so the global abbreviation can be used', async () => {
  const {ctx, store} = editor(1, {1: {
    [workflowName]: {name: workflowName, data: {version: 1, abbreviations: {21: 'P', 22: 'L'}}}
  }});
  await ctx.load();
  ctx.edit();
  ctx.draft[21] = '   ';
  await ctx.save();
  assert.deepEqual(store[1][workflowName].data.abbreviations, {22: 'L'});
  assert.equal(ctx.types[0].props.abbreviation, 'Pl');
});

test('discarding a draft does not mutate saved overrides or send a request', async () => {
  const {ctx, requests} = editor(1, {1: {
    [workflowName]: {name: workflowName, data: {version: 1, abbreviations: {21: 'P'}}}
  }});
  await ctx.load();
  ctx.edit();
  ctx.draft[21] = 'Changed';
  ctx.editMode = false;
  assert.equal(ctx.abbreviations[21], 'P');
  ctx.edit();
  assert.equal(ctx.draft[21], 'P');
  assert.ok(!requests.some(request => request.method === 'PATCH'));
});

test('failed saves preserve the draft and previously saved configuration', async () => {
  const {ctx, store, http} = editor();
  await ctx.load();
  ctx.edit();
  ctx.editMode = true;
  ctx.draft[21] = 'P';
  http.failSave = true;
  await assert.rejects(ctx.save(), /Save failed/);
  assert.equal(ctx.editMode, true);
  assert.equal(ctx.draft[21], 'P');
  assert.equal(ctx.abbreviations[21], undefined);
  assert.equal(store[1], undefined);
  assert.equal(ctx.saving, false);
});

test('intermediate selectors round-trip per protocol and can be removed independently of abbreviations', async () => {
  const store = {1: {[workflowName]: {name: workflowName,
    data: {version: 1, abbreviations: {21: 'P'}, intermediateTypeIds: ['21']}}},
  2: {[workflowName]: {name: workflowName, data: {version: 1, abbreviations: {}, intermediateTypeIds: ['22']}}}};
  const {ctx} = editor(1, store);
  await ctx.load();
  assert.deepEqual(copy(ctx.intermediateTypeIds), ['21']);
  ctx.edit();
  ctx.draftIntermediateTypeIds.push('22');
  ctx.editMode = false;
  assert.deepEqual(copy(ctx.intermediateTypeIds), ['21']);
  ctx.edit();
  ctx.draftIntermediateTypeIds = [];
  await ctx.save();
  assert.deepEqual(store[1][workflowName].data.intermediateTypeIds, []);
  assert.equal(store[1][workflowName].data.abbreviations[21], 'P');
  assert.deepEqual(store[2][workflowName].data.intermediateTypeIds, ['22']);
});

test('loads every catalog page instead of silently omitting later specimen types', async () => {
  const types = Array.from({length: 1001}, (_, index) => ({id: index + 1, value: 'Type ' + index, parentId: 2000}));
  const {ctx, requests} = editor(1, {}, types);
  await ctx.load();
  assert.equal(ctx.types.length, 1001);
  assert.deepEqual(requests.filter(request => request.url === 'permissible-values/v').map(request => request.opts.startAt), [0, 1000]);
});

test('the new section and its parent compile and expose translated labels', () => {
  for (const file of ['SpecimenTypeAbbreviations.vue', 'LabelFormats.vue']) {
    const filename = path.join(__dirname, '../src/biospecimen/cps', file);
    const {descriptor, errors} = parse(fs.readFileSync(filename, 'utf8'));
    assert.deepEqual(errors, []);
    assert.deepEqual(compileTemplate({source: descriptor.template.content, filename, id: 'cp-abbreviations'}).errors, []);
  }
  for (const lang of ['es', 'en']) {
    const messages = JSON.parse(fs.readFileSync(path.join(__dirname, '../src/i18n', lang + '.json'), 'utf8'));
    assert.ok(messages.cps.specimen_type_abbreviations);
    assert.ok(messages.cps.specimen_type_abbreviations_saved);
    assert.ok(messages.cps.protocol_abbreviation);
  }
});


function rendered(file, mocks = {}) {
  const component = load(file, mocks);
  const source = fs.readFileSync(path.join(__dirname, '../src', file), 'utf8');
  const template = parse(source).descriptor.template.content;
  return {...component, render: new Function('Vue', compile(template, {mode: 'function', prefixIdentifiers: true}).code)(Vue)};
}

async function mountedEditor() {
  const state = editor();
  const node = (type, text = '') => ({type, text, children: [], props: {}, style: {},
    tagName: type.toUpperCase(), addEventListener() {}, removeEventListener() {}});
  const renderer = Vue.createRenderer({
    createElement: node,
    createText: text => node('#text', text),
    createComment: text => node('#comment', text),
    setText: (el, text) => { el.text = text; },
    setElementText: (el, text) => { el.text = text; el.children = []; },
    parentNode: el => el.parent,
    nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1],
    insert(el, parent, anchor) {
      if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1);
      el.parent = parent;
      const index = anchor ? parent.children.indexOf(anchor) : -1;
      parent.children.splice(index < 0 ? parent.children.length : index, 0, el);
    },
    remove(el) { el.parent.children.splice(el.parent.children.indexOf(el), 1); },
    patchProp(el, key, oldValue, value) { el.props[key] = value; el[key] = value; }
  });
  const child = rendered('biospecimen/cps/SpecimenTypeAbbreviations.vue', {
    '@/biospecimen/services/CollectionProtocol.js': state.cpSvc,
    '@/administrative/services/PermissibleValueAdmin.js': state.pvSvc,
    'primevue/inputtext': {props: ['modelValue'], render() { return Vue.h('input', {value: this.modelValue}); }}
  });
  const parent = rendered('biospecimen/cps/LabelFormats.vue', {
    './SpecimenTypeAbbreviations.vue': child,
    './Resources.js': {updateOpts: {}},
    '@/biospecimen/services/CollectionProtocol.js': state.cpSvc,
    '@/biospecimen/schemas/cps/label-settings.js': {fields: []},
    '@/biospecimen/schemas/cps/misc-settings.js': {fields: []},
    '@/biospecimen/schemas/cps/label-settings-addedit.js': {layout: []},
    '@/common/services/FormUtil.js': {getFormSchema() { return {}; }, createCustomFieldsMap() {}},
    '@/common/services/Util.js': {clone: copy},
    '@/common/services/Alerts.js': {success() {}}
  });
  const root = node('root');
  const app = renderer.createApp(parent, {cp: {id: 1, specimenLabelFmt: 'original'}});
  app.config.globalProperties.$t = key => key;
  app.config.globalProperties.$te = () => false;
  app.directive('t', {});
  app.directive('show-if-allowed', {});
  app.component('os-section', rendered('common/components/Section.vue'));
  app.component('os-panel', {render() { return Vue.h('div', {}, [
    this.$slots.header?.(), this.$slots['primary-actions']?.(), this.$slots.default?.()
  ]); }});
  app.component('os-icon', {props: ['name'], render() { return Vue.h('i', {name: this.name}); }});
  app.component('os-button', {props: ['label'], render() { return Vue.h('button', {}, this.label); }});
  for (const name of ['os-overview', 'os-button-link', 'os-divider']) {
    app.component(name, {render() { return Vue.h('div'); }});
  }
  app.component('os-form', {methods: {validate() { return true; }}, render() { return Vue.h('form'); }});
  const parentVm = app.mount(root);
  await new Promise(resolve => setImmediate(resolve));
  await Vue.nextTick();
  const findAll = (test, el = root) => [
    ...(test(el) ? [el] : []), ...el.children.flatMap(childNode => findAll(test, childNode))
  ];
  return {...state, app, parentVm, root, findAll};
}

test('one Edit controls all fields and the bottom actions save drafts even while the table is collapsed', async () => {
  const {app, parentVm, findAll, requests, store} = await mountedEditor();
  try {
    const child = parentVm.$refs.abbreviations;
    const section = child.$.subTree.component.proxy;
    assert.equal(section.collapsed, true);
    assert.equal(findAll(el => el.type === 'button' && el.text === 'common.buttons.edit').length, 1);
    parentVm.switchToEditMode();
    await Vue.nextTick();
    assert.equal(child.editMode, true);
    assert.equal(findAll(el => el.type === 'button' && el.text === 'common.buttons.edit').length, 0);
    findAll(el => el.type === 'i' && el.props.name === 'chevron-circle-right').at(-1).props.onClick();
    await Vue.nextTick();
    assert.equal(section.collapsed, false);
    child.draft[21] = 'P';
    child.draftIntermediateTypeIds.push('21');
    parentVm.dataCtx.cp.specimenLabelFmt = 'edited';
    findAll(el => el.type === 'i' && el.props.name === 'chevron-circle-down').at(-1).props.onClick();
    await Vue.nextTick();
    assert.equal(section.collapsed, true);
    assert.equal(parentVm.$refs.abbreviations, child);
    assert.equal(child.draft[21], 'P');
    assert.deepEqual(copy(child.draftIntermediateTypeIds), ['21']);
    const tableIndex = findAll(el => el.type !== '#comment').findIndex(el => el.type === 'table');
    const updateIndex = findAll(el => el.type !== '#comment')
      .findIndex(el => el.type === 'button' && el.text === 'common.buttons.update');
    assert.ok(updateIndex > tableIndex);
    assert.equal(findAll(el => el.type === 'button' && el.text === 'common.buttons.update').length, 1);
    await parentVm.update();
    await Vue.nextTick();
    assert.equal(parentVm.editMode, false);
    assert.equal(requests.find(request => request.method === 'PUT').body.specimenLabelFmt, 'edited');
    assert.equal(store[1][workflowName].data.abbreviations[21], 'P');
    assert.deepEqual(store[1][workflowName].data.intermediateTypeIds, ['21']);
  } finally { app.unmount(); }
});

test('Cancel discards both drafts and reopening restores saved values without sending changes', async () => {
  const {app, parentVm, requests} = await mountedEditor();
  try {
    parentVm.switchToEditMode();
    await Vue.nextTick();
    const child = parentVm.$refs.abbreviations;
    child.draft[21] = 'discard';
    child.draftIntermediateTypeIds.push('21');
    parentVm.dataCtx.cp.specimenLabelFmt = 'discard';
    parentVm.switchToViewMode();
    await Vue.nextTick();
    parentVm.switchToEditMode();
    await Vue.nextTick();
    assert.equal(parentVm.$refs.abbreviations, child);
    assert.equal(child.draft[21], undefined);
    assert.deepEqual(copy(child.draftIntermediateTypeIds), []);
    assert.equal(parentVm.dataCtx.cp.specimenLabelFmt, 'original');
    assert.ok(!requests.some(request => ['PUT', 'PATCH'].includes(request.method)));
  } finally { app.unmount(); }
});

test('save errors retain the entire edit session for retry and skip unchanged workflow writes', async () => {
  const {app, parentVm, http, requests} = await mountedEditor();
  try {
    parentVm.switchToEditMode();
    await Vue.nextTick();
    const child = parentVm.$refs.abbreviations;
    child.draft[21] = 'P';
    parentVm.dataCtx.cp.specimenLabelFmt = 'edited';
    http.failCpSave = true;
    await assert.rejects(parentVm.update(), /Protocol save failed/);
    assert.equal(parentVm.editMode, true);
    assert.equal(parentVm.saving, false);
    assert.ok(!requests.some(request => request.method === 'PATCH'));
    http.failCpSave = false;
    http.failSave = true;
    await assert.rejects(parentVm.update(), /Save failed/);
    assert.equal(parentVm.editMode, true);
    assert.equal(parentVm.dataCtx.cp.specimenLabelFmt, 'edited');
    assert.equal(child.draft[21], 'P');
    assert.equal(parentVm.saving, false);
    http.failSave = false;
    await parentVm.update();
    await Vue.nextTick();
    assert.equal(parentVm.editMode, false);
    const writes = requests.filter(request => request.method === 'PATCH').length;
    parentVm.switchToEditMode();
    await Vue.nextTick();
    await parentVm.update();
    assert.equal(requests.filter(request => request.method === 'PATCH').length, writes);
  } finally { app.unmount(); }
});
