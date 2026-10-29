const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const babel = require('@babel/core');
const {parse, compileTemplate} = require('@vue/compiler-sfc');
const Vue = require('vue');
const {renderToString} = require('@vue/server-renderer');

const copy = value => JSON.parse(JSON.stringify(value));
function load(file, mocks = {}, ssr = false) {
  const filename = path.join(__dirname, '../src', file);
  const source = fs.readFileSync(filename, 'utf8');
  const descriptor = file.endsWith('.vue') && parse(source).descriptor;
  const {code} = babel.transformSync(descriptor ? descriptor.script.content : source, {
    filename, configFile: false, babelrc: false,
    plugins: [require('@babel/plugin-transform-modules-commonjs')]
  });
  const module = {exports: {}};
  const context = {module, exports: module.exports,
    require: name => mocks[name] || (name.startsWith('@/') || name.startsWith('.') ? {} : require(name))};
  vm.runInNewContext(code, context, {filename});
  const component = module.exports.default;
  if (ssr) {
    const template = compileTemplate({source: descriptor.template.content, filename, id: 'aliquot-quantities',
      ssr: true, ssrCssVars: []});
    assert.deepEqual(template.errors, []);
    const render = babel.transformSync(template.code, {configFile: false, babelrc: false,
      plugins: [require('@babel/plugin-transform-modules-commonjs')]}).code;
    vm.runInNewContext(render, context, {filename});
    component.ssrRender = module.exports.ssrRender;
  }
  return component;
}

function editor(lineage = 'Aliquot') {
  const requests = [], errors = [];
  const service = load('biospecimen/services/Specimen.js', {
    '@/common/services/HttpClient.js': {post: async (url, data) => {
      requests.push({url, data: copy(data)});
      return data.specimens.map((specimen, i) => ({...specimen, id: 300 + i}));
    }}
  });
  const component = load('biospecimen/participants/specimens/CreateChildren.vue', {
    '@/biospecimen/services/Specimen.js': service,
    '@/biospecimen/services/SpecimenDisplay.js': {label: () => 'Parent'},
    '@/common/services/Alerts.js': {error: error => errors.push(error.code), success() {}},
    '@/common/services/Router.js': {goto() {}},
    '@/common/services/Util.js': {clone: copy, getSpecimenMeasureUnit: () => 'ml'}
  }, true);
  const ctx = {...component.data(), specimen: {cpId: 1, cprId: 31, visitId: 42, lineage, labelFmt: 'AUTO'},
    cpViewCtx: {getCp: () => ({})}, parent: {id: 205, initialQty: 6, availableQty: 6},
    $refs: {}, $t: key => key};
  for (const [name, method] of Object.entries(component.methods)) ctx[name] = method.bind(ctx);
  for (const [name, computed] of Object.entries(component.computed)) {
    Object.defineProperty(ctx, name, {get: () => computed.call(ctx)});
  }
  ctx.count = 2;
  ctx.qtyPerAliquot = 1;
  ctx.resizeRows();
  function individual() {
    ctx.individualAliquotQuantities = true;
    ctx.aliquotQuantitiesChanged();
  }
  return {ctx, component, requests, errors, individual};
}

test('common quantity remains the default and is saved for every aliquot', async () => {
  const {ctx, requests} = editor();
  assert.equal(ctx.individualAliquotQuantities, false);
  ctx.qtyPerAliquot = 2;
  assert.equal(ctx.totalParentConsumption, 4);
  await ctx.createAll();
  assert.equal(requests[0].url, 'specimens/batch');
  assert.deepEqual(requests[0].data.specimens.map(row => [row.initialQty, row.availableQty]), [[2, 2], [2, 2]]);
});

test('saves 4 and 2 with a total consumption of 6 in both event sharing modes', async () => {
  for (const shareEventData of [true, false]) {
    const {ctx, requests, individual} = editor();
    individual();
    ctx.rows[0].initialQty = 4;
    ctx.rows[1].initialQty = 2;
    ctx.eventForms = [{formId: 7, formCtxtId: 8}];
    ctx.selectedEventFormId = 7;
    ctx.shareEventData = shareEventData;
    ctx.sharedEventFormData = {detail: 'shared'};
    ctx.rows.forEach((row, i) => row.eventFormData = {detail: 'row ' + i});
    assert.equal(ctx.totalParentConsumption, 6);
    assert.equal(ctx.validate(), null);
    await ctx.createAll();
    assert.deepEqual(requests[0].data.specimens.map(row => [row.initialQty, row.availableQty]), [[4, 4], [2, 2]]);
    assert.deepEqual(requests[0].data.formRecords.map(record => record.data.detail),
      shareEventData ? ['shared', 'shared'] : ['row 0', 'row 1']);
  }
});

test('rejects invalid quantities per row and consumption exceeding the parent without sending a request', async () => {
  const {ctx, requests, errors, individual} = editor();
  individual();
  for (const invalid of [null, '', 0, -1, NaN, Infinity]) {
    ctx.rows[1].initialQty = invalid;
    await ctx.createAll();
    assert.equal(errors.at(-1), 'specimens.aliquot_quantity_required');
  }
  ctx.rows[0].initialQty = 4;
  ctx.rows[1].initialQty = 3;
  await ctx.createAll();
  assert.equal(errors.at(-1), 'specimens.parent_quantity_insufficient');
  assert.equal(requests.length, 0);
  ctx.status = 'Pending';
  assert.equal(ctx.validate(), null);
  ctx.rows[1].initialQty = 0;
  assert.equal(ctx.validate(), 'specimens.aliquot_quantity_required');
});

test('switching modes and changing the count preserve existing individual edits', () => {
  const {ctx, individual} = editor();
  individual();
  assert.deepEqual(Array.from(ctx.rows, row => row.initialQty), [1, 1]);
  ctx.rows[0].initialQty = 4;
  ctx.rows[1].initialQty = 2;
  ctx.count = 3;
  ctx.resizeRows();
  assert.deepEqual(Array.from(ctx.rows, row => row.initialQty), [4, 2, 1]);
  ctx.count = 2;
  ctx.resizeRows();
  ctx.individualAliquotQuantities = false;
  ctx.aliquotQuantitiesChanged();
  ctx.qtyPerAliquot = 0.1;
  assert.equal(ctx.totalParentConsumption, 0.2);
  individual();
  assert.deepEqual(Array.from(ctx.rows, row => row.initialQty), [4, 2]);
  ctx.rows[0].initialQty = 0.1;
  ctx.rows[1].initialQty = 0.2;
  assert.equal(ctx.totalParentConsumption, 0.3);
});

test('derivatives retain their resulting quantities and parent consumption', async () => {
  const {ctx, requests} = editor('Derived');
  ctx.rows.forEach((row, i) => Object.assign(row, {type: 'Plasma', specimenClass: 'Fluid',
    initialQty: i + 1, parentConsumedQty: 0.5}));
  assert.equal(ctx.totalParentConsumption, 1);
  await ctx.createAll();
  assert.deepEqual(requests[0].data.specimens.map(row => [row.initialQty, row.parentConsumedQty]), [[1, 0.5], [2, 0.5]]);
});

test('renders editable quantities in aliquot rows independently of the event checkbox', async () => {
  const {component, ctx} = editor();
  for (const individualAliquotQuantities of [false, true]) {
    for (const shareEventData of [false, true]) {
      const app = Vue.createSSRApp({...component, created: undefined, data: () => ({
        ...component.data(), parent: {...ctx.parent}, count: 2, qtyPerAliquot: 1,
        rows: ctx.rows.map((row, i) => ({...row, initialQty: i === 0 ? 4 : 2})),
        individualAliquotQuantities, shareEventData,
        eventForms: [{formId: 7, formCtxtId: 8}], selectedEventFormId: 7,
        eventFormSchema: {rows: [{fields: []}]}
      })}, {specimen: ctx.specimen});
      app.provide('cpViewCtx', ctx.cpViewCtx);
      app.config.globalProperties.$t = key => key;
      app.config.warnHandler = () => {};
      const slot = {setup: (_, {slots}) => () => Vue.h('div', slots.default ? slots.default() : [])};
      for (const name of ['page', 'page-head', 'page-body', 'breadcrumb', 'date-picker', 'button', 'form']) {
        app.component('os-' + name, slot);
      }
      app.component('os-input-number', {props: ['modelValue'],
        setup: props => () => Vue.h('input', {value: props.modelValue, class: 'quantity-input'})});
      const html = await renderToString(app);
      const table = html.slice(html.indexOf('<tbody>'), html.indexOf('</tbody>'));
      assert.equal((table.match(/quantity-input/g) || []).length, individualAliquotQuantities ? 2 : 0);
      if (individualAliquotQuantities) {
        assert.match(table, /value="4"/);
        assert.match(table, /value="2"/);
      }
    }
  }
  for (const language of ['es', 'en']) {
    const messages = require('../src/i18n/' + language + '.json');
    assert.ok(messages.specimens.individual_aliquot_quantities);
  }
});
