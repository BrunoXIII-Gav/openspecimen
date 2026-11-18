import test from 'node:test';
import assert from 'node:assert/strict';
import {allows, allowsExtension, canCreate, filterFields, stripRestrictedData} from '../src/common/services/protocol-content-policy.mjs';

const limited = {unrestricted: false, allowed: ['collection', 'form:12'], extensionKeys: {SpecimenPrimaryExtension: 'form:13'}};

test('creation scope preserves old rules, fails closed before loading and permits independent lineages', () => {
  assert.equal(canCreate(null, 'New'), false);
  assert.equal(canCreate(limited, 'Aliquot'), true);
  const access = {...limited, createLineages: ['New']};
  assert.equal(canCreate(access, 'New'), true);
  assert.equal(canCreate(access, 'Derived'), false);
  assert.equal(canCreate(access, 'Aliquot'), false);
  assert.equal(canCreate({...access, createLineages: []}, 'New'), false);
  assert.equal(canCreate({...access, createLineages: ['Aliquot']}, 'Aliquot'), true);
});

test('missing authorization fails closed and legacy access preserves all fields', () => {
  assert.equal(allows(null, 'receipt'), false);
  const fields = [{name: 'specimen.receivedEvent.time', validations: {required: true}}];
  assert.deepEqual(filterFields(fields, {unrestricted: true}), fields);
});

test('hidden events and extension fields are absent from the schema including required rules', () => {
  const fields = [
    {name: 'specimen.label'},
    {name: 'specimen.collectionEvent.time'},
    {name: 'specimen.receivedEvent.time', validations: {required: true}},
    {name: 'specimen.extensionDetail.attrsMap.required_field', validations: {required: true}},
    {name: 'external', contentKey: 'form:12'},
    {name: 'other', formId: 14}
  ];
  assert.deepEqual(filterFields(fields, limited, 'SpecimenPrimaryExtension').map(field => field.name),
    ['specimen.label', 'specimen.collectionEvent.time', 'external']);
  assert.equal(fields.length, 6, 'shared form definitions are not mutated');
});

test('restricted values and defaults are omitted recursively from the payload', () => {
  const specimen = {lineage: 'New', label: 'A', collectionEvent: {time: 1}, receivedEvent: {receivedQuality: 'Acceptable'},
    extensionDetail: {attrsMap: {required_field: 'secret'}},
    children: [{lineage: 'New', receivedEvent: {time: 2}}]};
  stripRestrictedData(specimen, limited);
  assert.deepEqual(specimen, {lineage: 'New', label: 'A', collectionEvent: {time: 1}, children: [{lineage: 'New'}]});
});

test('a newly associated form is unavailable until explicitly permitted', () => {
  assert.equal(allows(limited, 'form:999'), false);
  assert.equal(allows({...limited, allowed: [...limited.allowed, 'form:999']}, 'form:999'), true);
});

test('lineage specific extension permissions do not hide a permitted derived form', () => {
  const access = {...limited, extensionKeys: {...limited.extensionKeys, SpecimenDerivedExtension: 'form:12'}};
  const field = {name: 'specimen.extensionDetail.attrsMap.result'};
  assert.deepEqual(filterFields([field], access, 'SpecimenDerivedExtension'), [field]);
  assert.deepEqual(filterFields([field], access, 'SpecimenPrimaryExtension'), []);
});

test('an unrelated role grant cannot expose a blocked extension in another resource', () => {
  const access = {...limited, blockedExtensions: ['SpecimenPrimaryExtension'], extensionKeys: {SpecimenPrimaryExtension: 'form:12'}};
  assert.equal(allowsExtension(access, 'SpecimenPrimaryExtension', 12), false);
  assert.deepEqual(filterFields([{name: 'specimen.extensionDetail.attrsMap.private'}], access, 'SpecimenPrimaryExtension'), []);
  const specimen = {lineage: 'New', extensionDetail: {attrsMap: {private: 'secret'}}};
  stripRestrictedData(specimen, access);
  assert.equal(specimen.extensionDetail, undefined);
});
