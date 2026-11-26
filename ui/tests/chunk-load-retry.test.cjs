const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const webpack = require('webpack');
const RetryChunkLoadPlugin = require('../chunk-load-retry-plugin.cjs');

test('real Webpack imports recover from cached 404s and still reject genuinely missing chunks', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'os-chunk-retry-'));
  try {
    await fs.writeFile(path.join(directory, 'entry.js'), `
      globalThis.importQueries = () => import(/* webpackChunkName: "queries" */ './queries.js');
      globalThis.importContainers = () => import(/* webpackChunkName: "containers" */ './containers.js');
    `);
    await fs.writeFile(path.join(directory, 'queries.js'), 'export default "queries loaded";');
    await fs.writeFile(path.join(directory, 'containers.js'), 'export default "containers loaded";');
    const output = path.join(directory, 'dist');
    await new Promise((resolve, reject) => {
      const compiler = webpack({mode:'development', devtool:false, context:directory,
        entry:'./entry.js', output:{path:output, filename:'app.js', chunkFilename:'[name].js', publicPath:'./', uniqueName:'ChunkRetryTest'},
        plugins:[new RetryChunkLoadPlugin()]});
      compiler.run((error, stats) => compiler.close(closeError => {
        if (error || closeError || stats.hasErrors()) reject(error || closeError || Error(stats.toString()));
        else resolve();
      }));
    });
    const sources = {};
    for (const filename of await fs.readdir(output)) sources[filename] = await fs.readFile(path.join(output, filename), 'utf8');
    const requests = [];
    let permanentlyMissing = false;
    let context;
    const scripts = [];
    const head = {
      appendChild(script) {
        scripts.push(script);
        script.parentNode = head;
        requests.push(script.src);
        queueMicrotask(() => {
          const url = new URL(script.src, document.baseURI);
          if (permanentlyMissing || !url.searchParams.has('_os_chunk_retry')) {
            script.onerror({type:'error', target:script});
          } else {
            vm.runInContext(sources[path.basename(url.pathname)], context);
            script.onload?.({type:'load', target:script});
          }
        });
      },
      removeChild(script) { scripts.splice(scripts.indexOf(script), 1); script.parentNode = null; }
    };
    const document = {
      baseURI:'https://example.invalid/custom-context/ui-app/', head,
      getElementsByTagName:()=>scripts,
      createElement:()=>({setAttribute(name,value){this[name]=value;},getAttribute(name){return this[name];}})
    };
    context = vm.createContext({document, URL, Date, setTimeout, clearTimeout});
    context.self = context;
    vm.runInContext(sources['app.js'], context);
    const [first, concurrent] = await Promise.all([context.importQueries(), context.importQueries()]);
    assert.equal(first.default, 'queries loaded');
    assert.equal(concurrent.default, 'queries loaded');
    assert.equal(requests.length, 2, 'concurrent imports share one initial request and one retry');
    assert.match(requests[1], /^https:\/\/example.invalid\/custom-context\/ui-app\/queries.js\?_os_chunk_retry=/);
    await context.importQueries();
    assert.equal(requests.length, 2, 'already loaded chunks are reused');
    permanentlyMissing = true;
    await assert.rejects(context.importContainers(), error => error.name === 'ChunkLoadError');
    assert.equal(requests.length, 4, 'a permanent failure retries once without a loop');
  } finally {
    await fs.rm(directory, {recursive:true, force:true});
  }
});
