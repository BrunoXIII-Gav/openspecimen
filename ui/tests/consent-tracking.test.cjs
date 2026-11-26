const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const babel = require('@babel/core');
const {parse, compileTemplate} = require('@vue/compiler-sfc');

function load(relative, services = {}) {
  const filename = path.join(__dirname, '../src', relative);
  const source = fs.readFileSync(filename, 'utf8');
  const descriptor = relative.endsWith('.vue') ? parse(source).descriptor : null;
  if (descriptor) assert.deepEqual(compileTemplate({source: descriptor.template.content, filename, id:'consent-tracking'}).errors, []);
  const {code} = babel.transformSync(descriptor ? descriptor.script.content : source, {filename,
    configFile:false, babelrc:false, plugins:[require('@babel/plugin-transform-modules-commonjs')]});
  const module = {exports:{}};
  vm.runInNewContext(code, {module, exports:module.exports, require:name => services[name] || {}});
  return module.exports.default;
}

test('warnings request consent status only when the existing permissions permit it', () => {
  const component = load('biospecimen/consents/PendingNotice.vue');
  const instance = {cpr:{id:1}, cpViewCtx:{getCp:()=>({}),isReadConsentAllowed:()=>true},$osSvc:{}};
  const allowed = () => !!component.computed.allowed.call(instance);
  assert.equal(allowed(),true);
  instance.cpr.contextOnly=true;
  assert.equal(allowed(),false);
  instance.cpr.contextOnly=false;
  instance.cpViewCtx.isReadConsentAllowed=()=>false;
  assert.equal(allowed(),false);
  instance.cpViewCtx.isReadConsentAllowed=()=>true;
  instance.cpViewCtx.getCp=()=>({consentsWaived:true});
  assert.equal(allowed(),false);
  instance.cpViewCtx.getCp=()=>({visitLevelConsents:true});
  assert.equal(allowed(),false);
  instance.cpViewCtx.getCp=()=>({});
  instance.$osSvc.ecDocSvc={};
  assert.equal(allowed(),false);
});

test('late responses and failures cannot overwrite another participant status or block entry', async () => {
  const responses = [];
  const component = load('biospecimen/consents/PendingNotice.vue', {
    '@/biospecimen/services/ConsentTracking.js': {getStatus:()=>new Promise((resolve,reject)=>responses.push({resolve,reject}))}
  });
  const instance={...component.data(),cpr:{id:1},allowed:true};
  const first=component.methods.refresh.call(instance);
  instance.cpr={id:2};
  const second=component.methods.refresh.call(instance);
  responses[1].resolve({pending:false,status:'NO_PENDING'});
  await second;
  responses[0].resolve({pending:true,status:'BOTH_PENDING'});
  await first;
  assert.equal(instance.status.status,'NO_PENDING');
  const failed=component.methods.refresh.call(instance);
  responses[2].reject(new Error('Unavailable'));
  await failed;
  assert.equal(instance.unavailable,true);
  assert.equal(instance.status,null);
  instance.allowed=false;
  await component.methods.refresh.call(instance);
  assert.equal(responses.length,3);
  assert.equal(instance.unavailable,false);
});

test('consent changes update the existing notice and subscriptions are removed on exit', () => {
  const service = load('biospecimen/services/ConsentTracking.js');
  const component = load('biospecimen/consents/PendingNotice.vue', {'@/biospecimen/services/ConsentTracking.js':service});
  let refreshes=0;
  const instance={cpr:{id:10},request:0,refresh(){++refreshes;}};
  component.mounted.call(instance);
  service.changed(11);
  assert.equal(refreshes,0);
  service.changed(10);
  assert.equal(refreshes,1);
  component.beforeUnmount.call(instance);
  service.changed(10);
  assert.equal(refreshes,1);
});

test('editing an existing group preserves follow-up requirements and completion mode', () => {
  const component=load('biospecimen/cps/Consents.vue');
  const config={groups:[{caption:'Alternatives',required:false,completionMode:'ALL',members:[{statementCode:'CI001'}]}]};
  const groups=component.methods._normaliseConsentGroups(config);
  assert.equal(groups[0].required,false);
  assert.equal(groups[0].completionMode,'ALL');
  const defaults=component.methods._normaliseConsentGroups({groups:[{caption:'Default',members:[]}]});
  assert.equal(defaults[0].required,true);
  assert.equal(defaults[0].completionMode,'ANY');
});
