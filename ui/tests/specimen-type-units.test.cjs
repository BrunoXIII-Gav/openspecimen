const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const babel = require('@babel/core');
const {parse} = require('@vue/compiler-sfc');
const Vue = require('vue');

function load(file, mocks = {}) {
  const filename = path.join(__dirname, '../src', file);
  const script = parse(fs.readFileSync(filename, 'utf8')).descriptor.script.content;
  const {code} = babel.transformSync(script, {filename, configFile: false, babelrc: false,
    plugins: [require('@babel/plugin-transform-modules-commonjs')]});
  const module = {exports: {}};
  vm.runInNewContext(code, {module, exports: module.exports,
    require: name => name === 'vue' ? Vue : mocks[name] || {}}, {filename});
  return module.exports.default;
}

async function editor(unit) {
  const component = load('administrative/specimen-type-units/AddEdit.vue', {
    '@/common/services/Router.js': {getUrl: () => ''},
    '@/common/services/I18n.js': {msg: key => key},
    '@/biospecimen/services/CollectionProtocol.js': {getCps: async () => []},
    '@/administrative/services/SpecimenTypeUnit.js': {getUnit: async () => ({...unit})},
    '@/administrative/services/PermissibleValueAdmin.js': {getPvs: async ({attribute}) =>
      attribute === 'specimen_type' ? [
        {value: 'Cell'}, {value: 'Fluid'},
        {value: 'PBMC', parentValue: 'Cell'},
        {value: 'Plasma', parentValue: 'Fluid'}
      ] : [{value: 'mL'}]}
  });
  const ctx = {...component.setup(), unitId: unit ? unit.id : -1, $t: key => key};
  for (const [name, method] of Object.entries(component.methods)) ctx[name] = method.bind(ctx);
  await component.created.call(ctx);

  // Use the real form event path: Form updates the shared data before emitting input.
  const form = load('common/components/Form.vue', {
    '@/common/services/ExpressionUtil.js': {
      getValue: (data, name) => data.unit[name.split('.')[1]],
      setValue: (data, name, value) => { data.unit[name.split('.')[1]] = value; }
    }
  });
  function input(name, value) {
    form.methods.handleInput.call({
      ctx: {formData: ctx.dataCtx}, formModel: {[name]: value},
      v$: {formModel: {}, $invalid: false},
      $emit: (event, payload) => { if (event === 'input') ctx.handleInput(payload); }
    }, {name});
  }
  return {ctx, input};
}

const values = ctx => Array.from(ctx.ctx.specimenTypes, option => option.value);

test('selecting a class in a new unit rule loads its permissible specimen types', async () => {
  const {ctx, input} = await editor();
  input('unit.specimenClass', 'Cell');
  assert.deepEqual(values(ctx), [null, 'PBMC']);
});

test('changing or clearing the class removes the previous type and refreshes options', async () => {
  const {ctx, input} = await editor();
  input('unit.specimenClass', 'Cell');
  input('unit.type', 'PBMC');
  input('unit.specimenClass', 'Fluid');
  assert.equal(ctx.dataCtx.unit.type, null);
  assert.deepEqual(values(ctx), [null, 'Plasma']);
  input('unit.type', 'Plasma');
  input('unit.specimenClass', null);
  assert.equal(ctx.dataCtx.unit.type, null);
  assert.deepEqual(values(ctx), [null]);
});

test('editing a saved rule loads its types and preserves the selection for unrelated inputs', async () => {
  const {ctx, input} = await editor({id: 1, specimenClass: 'Cell', type: 'PBMC'});
  assert.deepEqual(values(ctx), [null, 'PBMC']);
  input('unit.cpId', 42);
  input('unit.quantityUnit', 'mL');
  input('unit.specimenClass', 'Cell');
  assert.equal(ctx.dataCtx.unit.type, 'PBMC');
  assert.deepEqual(values(ctx), [null, 'PBMC']);
});
