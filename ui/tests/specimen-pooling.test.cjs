const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const babel = require('@babel/core');
const {parse} = require('@vue/compiler-sfc');

// Exercise the actual route, component methods and HTTP service together without
// requiring a browser session or creating specimens in the live database.
function load(relativePath, mocks = {}, globals = {}) {
  const filename = path.join(__dirname, '../src', relativePath);
  const source = fs.readFileSync(filename, 'utf8');
  const script = filename.endsWith('.vue') ? parse(source).descriptor.script.content : source;
  const {code} = babel.transformSync(script, {
    filename, configFile: false, babelrc: false,
    plugins: [require('@babel/plugin-transform-modules-commonjs')]
  });
  const module = {exports: {}};
  vm.runInNewContext(code, {
    module, exports: module.exports, require: name => mocks[name] || {},
    URLSearchParams, ...globals
  }, {filename});
  return module.exports.default;
}

const copy = value => JSON.parse(JSON.stringify(value));
const specimens = [174, 175, 176].map(id => ({
  id, cpId: 1, cprId: 30, visitId: 41, label: 'PLASMA-' + id,
  status: 'Collected', activityStatus: 'Active', type: 'Plasma',
  children: [{id: id + 100}], availableQty: 2
}));

async function blank(query, sources = specimens) {
  const searches = [];
  const root = load('biospecimen/participants/specimens/DetailRoot.vue', {
    '@/biospecimen/services/CollectionProtocol.js': {getCpById: async () => ({specimenLabelFmt: 'AUTO'})},
    '@/biospecimen/services/Specimen.js': {
      getByIds: async ids => { searches.push(copy(ids)); return copy(sources); }
    }
  }, {window: {location: {hash: '#/specimen/-1/addedit?' + new URLSearchParams(query)}}});
  const ctx = {cpr: {id: 30, cpId: 1}, visit: {id: 41}, loaded: false};
  await root.methods._loadBlank.call(ctx);
  assert.equal(ctx.loaded, true);
  return {specimen: copy(ctx.specimen), searches, root};
}

function editor(specimen, post = async () => ({id: 200, cpId: 1, cprId: 30, visitId: 41})) {
  const requests = [], errors = [], routes = [];
  const service = load('biospecimen/services/Specimen.js', {
    '@/common/services/HttpClient.js': {
      post: async (url, data) => { requests.push({url, data: copy(data)}); return post(); },
      put: async (url, data) => { requests.push({url, data: copy(data)}); return post(); }
    }
  });
  const component = load('biospecimen/participants/specimens/AddEdit.vue', {
    '@/biospecimen/services/Specimen.js': service,
    '@/common/services/Util.js': {clone: copy},
    '@/common/services/FormUtil.js': {createCustomFieldsMap: () => {}},
    '@/common/services/Alerts.js': {error: error => errors.push(error)},
    '@/common/services/Router.js': {goto: (...args) => routes.push(copy(args))}
  });
  const ctx = {specimen, cpr: {id: 30}, visit: {id: 41}, cpViewCtx: {
    getCp: () => ({id: 1}), getRole: () => ({})
  }};
  Object.assign(ctx, component.data.call(ctx));
  for (const [name, method] of Object.entries(component.methods)) ctx[name] = method.bind(ctx);
  Object.defineProperty(ctx, 'isPooling', {get: () => component.computed.isPooling.call(ctx)});
  ctx.$refs = {spmnForm: {validate: () => true}};
  return {ctx, requests, errors, routes};
}

test('pool action preserves all selected sources through navigation, reload and the pooled API', async () => {
  let navigation;
  const workflow = load('biospecimen/services/Workflow.js', {
    '@/common/services/Router.js': {goto: (...args) => { navigation = copy(args); }}
  });
  await workflow.createPooledSpecimens(specimens);
  assert.deepEqual(navigation, ['SpecimenAddEdit', {
    cpId: 1, cprId: 30, visitId: 41, specimenId: -1
  }, {poolIds: '174,175,176'}]);

  const {specimen, searches} = await blank(navigation[2], [...specimens].reverse());
  assert.deepEqual(searches, [[174, 175, 176]]);
  assert.equal(specimen.lineage, 'New');
  assert.equal(specimen.parentId, null);
  assert.deepEqual(specimen.specimensPool.map(item => item.id), [174, 175, 176]);
  const {ctx, requests, routes} = editor(specimen);
  ctx.dataCtx.specimen.initialQty = 5;
  ctx.dataCtx.specimen.specimensPool[1].closeAfterPooledCreation = false;
  await ctx.saveOrUpdate();
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, 'specimens/pooled');
  assert.equal(requests[0].data.lineage, 'New');
  assert.equal(requests[0].data.status, 'Collected');
  assert.equal(requests[0].data.initialQty, 5);
  assert.equal('parentId' in requests[0].data, false);
  assert.deepEqual(requests[0].data.specimensPool, [
    {id: 174, closeAfterPooledCreation: true},
    {id: 175, closeAfterPooledCreation: false},
    {id: 176, closeAfterPooledCreation: true}
  ]);
  assert.equal(routes[0][0], 'ParticipantsListItemSpecimenDetail.Overview');
  assert.equal(routes[0][1].specimenId, 200);
});

test('invalid, duplicate-only and missing sources never fall back to ordinary specimen creation', async () => {
  for (const [query, sources] of [
    [{poolIds: ''}, []], [{poolIds: '174'}, specimens],
    [{poolIds: '174,174'}, specimens], [{poolIds: '174,bad'}, specimens],
    [{poolIds: '174,175,176'}, specimens.slice(0, 2)]
  ]) {
    const {specimen} = await blank(query, sources);
    const {ctx, requests, errors} = editor(specimen);
    assert.equal(ctx.isPooling, true);
    await ctx.saveOrUpdate();
    assert.equal(requests.length, 0);
    assert.equal(errors[0].code, 'common.specimen_actions.select_for_pooling');
  }
});

test('pool rejects sources from another protocol or sources that are pending or closed', async () => {
  for (const change of [{cpId: 2}, {status: 'Pending'}, {activityStatus: 'Closed'}]) {
    const sources = copy(specimens);
    Object.assign(sources[1], change);
    const {specimen} = await blank({poolIds: '174,175,176'}, sources);
    const {ctx, requests, errors} = editor(specimen);
    await ctx.saveOrUpdate();
    assert.equal(requests.length, 0);
    assert.equal(errors[0].code, 'specimens.invalid_pool_sources');
  }
});

test('ordinary new specimens and edits of existing pools keep using the ordinary save API', async () => {
  const {specimen} = await blank({});
  const create = editor(specimen);
  await create.ctx.saveOrUpdate();
  assert.equal(create.requests[0].url, 'specimens');
  const edit = editor({...specimen, id: 200, specimensPool: copy(specimens)});
  assert.equal(edit.ctx.isPooling, false);
  await edit.ctx.saveOrUpdate();
  assert.equal(edit.requests[0].url, 'specimens/200');
});

test('pool saving blocks duplicate submissions and can retry after a server error', async () => {
  const {specimen} = await blank({poolIds: '174,175,176'});
  let reject;
  const {ctx, requests} = editor(specimen, () => new Promise((resolve, fail) => { reject = fail; }));
  const save = ctx.saveOrUpdate();
  await ctx.saveOrUpdate();
  assert.equal(requests.length, 1);
  reject(new Error('server error'));
  await assert.rejects(save, /server error/);
  assert.equal(ctx.ctx.saving, false);
  const retry = ctx.saveOrUpdate();
  assert.equal(requests.length, 2);
  reject(new Error('server error'));
  await assert.rejects(retry, /server error/);
});

test('pool selection changes reload the source context', () => {
  const root = load('biospecimen/participants/specimens/DetailRoot.vue');
  let reloads = 0;
  const ctx = {specimenId: -1, _loadBlank: () => reloads++};
  root.watch['$route.query.poolIds'].call(ctx, '174,175', '174,176');
  assert.equal(reloads, 1);
  root.watch['$route.query.poolIds'].call(ctx, '174,175', '174,175');
  assert.equal(reloads, 1);
});
