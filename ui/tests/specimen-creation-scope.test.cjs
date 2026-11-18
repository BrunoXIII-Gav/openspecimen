const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const babel = require('@babel/core');
const {parse, compileTemplate} = require('@vue/compiler-sfc');
const policy = require('../src/common/services/protocol-content-policy.mjs');
const roleDependencies = require('../src/administrative/roles/role-content-dependencies.mjs');

function load(file, access = {createLineages: ['New']}, render = false) {
  const filename = path.join(__dirname, '../src', file);
  const {descriptor} = parse(fs.readFileSync(filename, 'utf8'));
  assert.deepEqual(compileTemplate({source: descriptor.template.content, filename, id: 'creation-scope'}).errors, []);
  const {code} = babel.transformSync(descriptor.script.content, {filename, configFile: false, babelrc: false,
    plugins: [require('@babel/plugin-transform-modules-commonjs')]});
  const module = {exports: {}};
  vm.runInNewContext(code, {module, exports: module.exports, require: name => {
    if (name == '@/common/services/ProtocolContent.js') return {...policy, getAccess: async () => access};
    if (name == './role-content-dependencies.mjs') return roleDependencies;
    if (name == '@/biospecimen/services/CollectionProtocol.js') return {getCps: async () => []};
    if (name == '@/common/services/HttpClient.js') return {get: async () => [
      {key: 'collection'}, {key: 'receipt'}, {key: 'form:1', entityTypes: ['VisitExtension']}
    ]};
    return {};
  }}, {filename});
  const component = module.exports.default;
  if (render) {
    const compiled = compileTemplate({source: descriptor.template.content, filename, id: 'creation-scope'});
    const rendered = babel.transformSync(compiled.code, {configFile: false, babelrc: false,
      plugins: [require('@babel/plugin-transform-modules-commonjs')]});
    const output = {exports: {}};
    vm.runInNewContext(rendered.code, {module: output, exports: output.exports, require});
    component.render = output.exports.render;
  }
  return component;
}

test('role selector keeps event permissions while independently selecting creation lineages', () => {
  const component = load('administrative/roles/ProtocolContentPermissions.vue');
  const original = {cpId: 1, allowed: ['collection', 'form:153']};
  let result;
  const ctx = {modelValue: [original], dependencies: roleDependencies.roleContentDependencies([{name: 'Specimen', create: true}]),
    update: rules => { result = JSON.parse(JSON.stringify(rules)); }};
  component.methods.toggleCreation.call(ctx, 0, 'Derived', false);
  assert.deepEqual(result[0].createLineages, ['New', 'Aliquot']);
  ctx.modelValue = result;
  component.methods.toggleCreation.call(ctx, 0, 'Aliquot', false);
  assert.deepEqual(result[0], {...original, createLineages: ['New']});
  assert.equal(original.createLineages, undefined, 'shared role data is not mutated');
});

test('specimen create menu hides children for primary-only roles and offers either child independently', () => {
  const component = load('biospecimen/participants/specimens/Overview.vue');
  const ctx = {$t: key => key, createDerivatives() {}, createAliquots() {}, creationAccess: {createLineages: ['New']}};
  const options = () => Array.from(component.computed.creationOptions.call(ctx), item => item.lineage);
  assert.deepEqual(options(), []);
  ctx.creationAccess = {createLineages: ['Derived']}; assert.deepEqual(options(), ['Derived']);
  ctx.creationAccess = {createLineages: ['Aliquot']}; assert.deepEqual(options(), ['Aliquot']);
  ctx.creationAccess = null; assert.deepEqual(options(), []);
});

test('direct child creation route stops before loading forms or specimen data when forbidden', async () => {
  const component = load('biospecimen/participants/specimens/CreateChildren.vue');
  const ctx = {specimen: {cpId: 1}, lineage: 'Aliquot', creationDenied: false};
  await component.created.call(ctx);
  assert.equal(ctx.creationDenied, true);
});

test('direct primary creation route is rejected if only aliquots are allowed', async () => {
  const component = load('biospecimen/participants/specimens/CreateMultiple.vue', {createLineages: ['Aliquot']});
  const ctx = {cpViewCtx: {getCp: () => ({id: 1})}, creationDenied: false};
  await component.created.call(ctx);
  assert.equal(ctx.creationDenied, true);
});

test('rendered role editor disables controls reactively and restores stored selections without mutation', async () => {
  const vue = require('vue');
  const component = load('administrative/roles/ProtocolContentPermissions.vue', undefined, true);
  const node = (tag, text = '') => ({tag, text, children: [], props: {}});
  const renderer = vue.createRenderer({
    createElement: tag => node(tag), createText: text => node('#text', text), createComment: text => node('#comment', text),
    setText: (item, text) => { item.text = text; }, setElementText: (item, text) => { item.text = text; item.children = []; },
    parentNode: item => item.parent, nextSibling: item => item.parent?.children[item.parent.children.indexOf(item) + 1],
    insert(item, parent, anchor) {
      if (item.parent) item.parent.children.splice(item.parent.children.indexOf(item), 1);
      item.parent = parent;
      const at = anchor ? parent.children.indexOf(anchor) : -1;
      if (at < 0) parent.children.push(item); else parent.children.splice(at, 0, item);
    },
    remove(item) { item.parent.children.splice(item.parent.children.indexOf(item), 1); item.parent = null; },
    patchProp: (item, key, previous, value) => { item.props[key] = value; }
  });
  const acl = vue.reactive([{name: 'Specimen', read: true, create: true}]);
  const rules = [{cpId: 1, allowed: ['collection', 'receipt'], createLineages: ['New']}];
  const original = JSON.stringify(rules), emitted = [];
  const app = renderer.createApp(component, {acl, modelValue: rules, 'onUpdate:modelValue': rules => emitted.push(rules)});
  app.config.globalProperties.$t = key => key;
  app.component('os-button', {props: ['disabled', 'label'], render() { return vue.h('button', {disabled: this.disabled}, this.label); }});
  app.component('os-message', {render() { return vue.h('p', this.$slots.default?.()); }});
  const root = node('root');
  const instance = app.mount(root);
  const find = (item, tag) => [...(item.tag == tag ? [item] : []), ...item.children.flatMap(child => find(child, tag))];
  const fields = () => find(root, 'fieldset');
  const lineageInputs = () => find(fields()[1], 'input');
  try {
    for (let tick = 0; tick < 20; tick++) await vue.nextTick();
    assert.equal(fields()[0].props.disabled, false);
    assert.equal(fields()[1].props.disabled, false);
    acl[0].create = false; await vue.nextTick();
    assert.equal(fields()[0].props.disabled, false, 'Read still permits event configuration');
    assert.equal(fields()[1].props.disabled, true, 'Create options become inactive immediately');
    assert.equal(lineageInputs().every(item => item.props.disabled), true);
    instance.toggleCreation(0, 'Derived', true);
    assert.equal(emitted.length, 0);
    acl[0].read = false; await vue.nextTick();
    assert.equal(fields()[0].props.disabled, true);
    instance.add(); assert.equal(emitted.length, 0);
    acl[0].name = 'PrimarySpecimen'; acl[0].create = true; await vue.nextTick();
    assert.deepEqual(lineageInputs().map(item => item.props.disabled), [false, true, true]);
    assert.equal(lineageInputs()[0].props.checked, true);
    assert.equal(JSON.stringify(rules), original, 'disabling and re-enabling must preserve saved selections');
  } finally { app.unmount(); }
});

test('bulk selection changes only content options enabled by the base ACL', () => {
  const component = load('administrative/roles/ProtocolContentPermissions.vue');
  const options = [{key: 'receipt'}, {key: 'form:1', entityTypes: ['VisitExtension']}];
  const ctx = {acl: [{name: 'Visit', read: true}], modelValue: [{cpId: 1, allowed: ['receipt', 'form:1']}],
    options: {1: options}, readOnly: false,
    $emit(event, rules) { this.modelValue = JSON.parse(JSON.stringify(rules)); }};
  Object.defineProperty(ctx, 'dependencies', {get: () => roleDependencies.roleContentDependencies(ctx.acl)});
  for (const [name, method] of Object.entries(component.methods)) ctx[name] = method.bind(ctx);
  ctx.clearAllowed(0); assert.deepEqual(ctx.modelValue[0].allowed, ['receipt']);
  ctx.selectAll(0); assert.deepEqual(ctx.modelValue[0].allowed, ['receipt', 'form:1']);
});
