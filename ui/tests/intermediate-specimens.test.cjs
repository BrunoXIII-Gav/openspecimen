const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const babel = require('@babel/core');
const {parse, compileTemplate} = require('@vue/compiler-sfc');

const translations = JSON.parse(fs.readFileSync(path.join(__dirname, '../src/i18n/es.json'), 'utf8'));
const i18n = {
  exists(key) { return !!key.split('.').reduce((value, part) => value && value[part], translations); },
  msg(key, args = {}) {
    const value = key.split('.').reduce((value, part) => value && value[part], translations) || key;
    return value.replace(/\{(\w+)\}/g, (_, name) => args[name]);
  }
};

function load(file, mocks = {}) {
  const filename = path.join(__dirname, '../src', file);
  const source = fs.readFileSync(filename, 'utf8');
  const descriptor = file.endsWith('.vue') && parse(source, {filename}).descriptor;
  if (descriptor) {
    assert.deepEqual(compileTemplate({source: descriptor.template.content, filename, id: 'intermediate-specimens'}).errors, []);
  }
  const {code} = babel.transformSync(descriptor ? descriptor.script.content : source, {
    filename, configFile: false, babelrc: false, plugins: [require('@babel/plugin-transform-modules-commonjs')]
  });
  const module = {exports: {}};
  vm.runInNewContext(code, {module, exports: module.exports, require: name => mocks[name] || {}}, {filename});
  return module.exports.default;
}

const display = load('biospecimen/services/SpecimenDisplay.js', {'@/common/services/I18n.js': i18n});
const mockDisplay = {'@/biospecimen/services/SpecimenDisplay.js': display};
const intermediate = () => ({id: 101, cpId: 1, parentId: 100, lineage: 'Derived', type: 'Plasma',
  intermediate: true, label: 'CUSTOM-PROCESS-9001', parentLabel: 'NRC-0001.1.1',
  intermediateSourceLabel: 'NRC-0001.1.1', children: []});

test('display refers to the source and preserves the configured real identifier', () => {
  const specimen = intermediate();
  assert.equal(display.label(specimen), 'Plasma — procedente de NRC-0001.1.1');
  assert.equal(specimen.label, 'CUSTOM-PROCESS-9001');
  assert.equal(specimen.parentId, 100);
  assert.equal(display.label({...specimen, type: 'DNA'}), 'ADN — procedente de NRC-0001.1.1');
});

test('primary specimens, aliquots and unselected derivatives keep their labels', () => {
  for (const specimen of [
    {...intermediate(), intermediate: false},
    {...intermediate(), lineage: 'New'},
    {...intermediate(), lineage: 'Aliquot'},
    {label: 'OTHER-CP-001', type: 'Plasma', lineage: 'Derived'}
  ]) assert.equal(display.label(specimen), specimen.label);
});

test('chains and child breadcrumbs use the visible ancestor reference without relabeling the parent', () => {
  assert.equal(display.label({...intermediate(), parentLabel: 'INTERNAL-PARENT'}),
    'Plasma — procedente de NRC-0001.1.1');
  const aliquot = {label: 'NRC-0001.1.1.P1', parentLabel: 'INTERNAL-PARENT', parentId: 101,
    parentIntermediate: true, parentType: 'Plasma', parentIntermediateSourceLabel: 'NRC-0001.1.1'};
  assert.equal(display.parentLabel(aliquot), 'Plasma — procedente de NRC-0001.1.1');
  assert.equal(aliquot.parentLabel, 'INTERNAL-PARENT');
  assert.equal(display.parentLabel({parentLabel: 'NORMAL-PARENT'}), 'NORMAL-PARENT');
});

test('shared specimen descriptions and detail headings use the same presentation', () => {
  const specimen = intermediate();
  const description = load('common/components/SpecimenDescription.vue', mockDisplay);
  assert.equal(description.computed.description.call({object: specimen}), display.label(specimen));
  const detail = load('biospecimen/participants/specimens/Detail.vue', mockDisplay);
  assert.equal(detail.computed.displayLabel.call({specimen}), display.label(specimen));
  const children = load('biospecimen/participants/specimens/CreateChildren.vue', mockDisplay);
  assert.equal(children.computed.parentDisplayLabel.call({parent: specimen}), display.label(specimen));
  const edit = load('biospecimen/participants/specimens/AddEdit.vue', mockDisplay);
  assert.equal(edit.computed.specimenLabel.call({specimen}), display.label(specimen));
});

test('tree labels change visually while tree identity, selection and biological hierarchy are preserved', () => {
  const tree = load('biospecimen/components/SpecimenTree.vue', {...mockDisplay,
    '@/common/services/FormUtil.js': {createCustomFieldsMap() {}}});
  const specimen = intermediate();
  const aliquot = {id: 102, lineage: 'Aliquot', label: 'NRC-0001.1.1.P1'};
  specimen.children.push(aliquot);
  const ctx = {treeCfg: {}, oldTree: false, cpr: {}, visit: {}, specimen: null};
  ctx._flattenSpecimens = tree.methods._flattenSpecimens.bind(ctx);
  const rows = ctx._flattenSpecimens([specimen], 0);
  assert.equal(rows[0].specimen, specimen);
  assert.equal(rows[1].parentSpecimen, specimen);
  assert.equal(rows[1].specimen.id, 102);
  const label = tree.computed.fields.call(ctx).find(field => field.name === 'specimen.label');
  assert.equal(label.value(rows[0]), display.label(specimen));
  assert.equal(label.value(rows[1]), aliquot.label);
  assert.equal(rows[0].specimen.label, 'CUSTOM-PROCESS-9001');
});

test('overview moves the real identifier to technical details and formats intermediate parent references', () => {
  const overview = load('biospecimen/participants/specimens/Overview.vue', mockDisplay);
  const fields = [{name: 'specimen.label'}, {name: 'specimen.parentLabel'}, {name: 'specimen.initialQty'}];
  const specimen = intermediate();
  const visible = overview.computed.overviewFields.call({ctx: {dict: fields}, specimen});
  assert.ok(!visible.some(field => field.name === 'specimen.label'));
  assert.ok(visible.some(field => field.name === 'specimen.initialQty'));
  assert.equal(specimen.label, 'CUSTOM-PROCESS-9001');
  const aliquot = {lineage: 'Aliquot', label: 'P1', parentIntermediate: true, parentType: 'Plasma',
    parentLabel: 'CUSTOM-PROCESS-9001', parentIntermediateSourceLabel: 'NRC-0001.1.1'};
  const childFields = overview.computed.overviewFields.call({ctx: {dict: fields}, specimen: aliquot});
  assert.ok(childFields.some(field => field.name === 'specimen.label'));
  assert.equal(childFields.find(field => field.name === 'specimen.parentLabel').value({specimen: aliquot}),
    'Plasma — procedente de NRC-0001.1.1');
});

test('event and form record views compile with the shared display helper', () => {
  load('biospecimen/participants/specimens/AddEditEvent.vue', mockDisplay);
  load('biospecimen/participants/specimens/AddEditFormRecord.vue', mockDisplay);
});
