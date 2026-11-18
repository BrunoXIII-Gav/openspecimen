import test from 'node:test';
import assert from 'node:assert/strict';
import {roleContentDependencies, canConfigureContentOption} from '../src/administrative/roles/role-content-dependencies.mjs';

test('unrelated resources and protocol configuration alone do not enable clinical content', () => {
  for (const resource of ['User', 'CollectionProtocol', 'StorageContainer']) {
    assert.equal(roleContentDependencies([{name: resource, read: true, create: true}]).content, false);
  }
  assert.equal(roleContentDependencies([]).content, false);
  assert.equal(roleContentDependencies([{name: 'Specimen', del: true, expImp: true}]).content, false);
  for (const name of ['Specimen', 'PrimarySpecimen', 'Visit', 'ParticipantPhi', 'ParticipantDeid']) {
    assert.equal(roleContentDependencies([{name, read: true}]).content, true);
  }
});

test('read or update keeps content active without enabling creation', () => {
  const acl = [{name: 'Specimen', read: true, update: true, create: false}];
  assert.equal(roleContentDependencies(acl).content, true);
  assert.equal(roleContentDependencies(acl).creation, false);
  assert.deepEqual(roleContentDependencies(acl).lineages, {New: false, Derived: false, Aliquot: false});
  acl[0].create = true;
  assert.deepEqual(roleContentDependencies(acl).lineages, {New: true, Derived: true, Aliquot: true});
  acl[0].create = false;
  assert.equal(roleContentDependencies(acl).creation, false);
});

test('primary specimen creation enables only the primary checkbox', () => {
  assert.deepEqual(roleContentDependencies([{name: 'PrimarySpecimen', create: true}]).lineages,
    {New: true, Derived: false, Aliquot: false});
  assert.equal(roleContentDependencies([{name: 'Visit', create: true}]).creation, false);
});

test('overview DTOs are supported and live editor flags take precedence over stale DTO grants', () => {
  const entry = {resourceName: 'Specimen', operations: [{operationName: 'Create'}]};
  assert.equal(roleContentDependencies([entry]).creation, true);
  Object.assign(entry, {name: 'Specimen', read: true, create: false});
  assert.equal(roleContentDependencies([entry]).creation, false);
  assert.equal(roleContentDependencies([{resourceName: 'ALL', operations: [{operationName: 'ALL'}]}]).creation, true);
});

test('events and associated forms require permissions on their own resource', () => {
  const acl = [{name: 'Visit', read: true}];
  assert.equal(canConfigureContentOption(acl, {key: 'receipt'}), false);
  assert.equal(canConfigureContentOption(acl, {key: 'form:1', entityTypes: ['SpecimenEvent']}), false);
  assert.equal(canConfigureContentOption(acl, {key: 'form:2', entityTypes: ['ParticipantExtension']}), false);
  assert.equal(canConfigureContentOption(acl, {key: 'form:3', entityTypes: ['VisitExtension']}), true);
  assert.equal(canConfigureContentOption(acl, {key: 'form:4', entityTypes: ['SpecimenCollectionGroup']}), true);
  assert.equal(canConfigureContentOption(acl, {key: 'form:5', entityTypes: ['ParticipantExtension', 'VisitExtension']}), true);
  assert.equal(canConfigureContentOption(acl, {key: 'form:6', entityTypes: []}), false);
  assert.equal(canConfigureContentOption([{name: 'PrimarySpecimen', read: true}],
    {key: 'form:7', entityTypes: ['SpecimenDerivedExtension']}), false);
});
