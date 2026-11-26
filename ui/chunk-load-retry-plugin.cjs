// Retry a failed lazy-loaded script once, bypassing cached error responses.
// Keep Webpack's loader so nonce, timeout and concurrent loading still apply.
module.exports = class RetryChunkLoadPlugin {
  apply(compiler) {
    const {RuntimeGlobals, RuntimeModule} = compiler.webpack;

    class RetryChunkLoadRuntimeModule extends RuntimeModule {
      constructor() {
        super('retry failed chunk script', RuntimeModule.STAGE_ATTACH);
      }

      generate() {
        return `
          var loadScript = ${RuntimeGlobals.loadScript};
          var retryToken = Date.now().toString(36);
          ${RuntimeGlobals.loadScript} = function(url, done, key, chunkId) {
            loadScript(url, function(event) {
              if (event && (event.type === 'error' || event.type === 'timeout')) {
                var retryUrl = new URL(url, document.baseURI);
                retryUrl.searchParams.set('_os_chunk_retry', retryToken);
                loadScript(retryUrl.href, done, key, chunkId);
              } else {
                done(event);
              }
            }, key, chunkId);
          };
        `;
      }
    }

    compiler.hooks.thisCompilation.tap('RetryChunkLoadPlugin', compilation => {
      compilation.hooks.runtimeRequirementInTree
        .for(RuntimeGlobals.loadScript)
        .tap('RetryChunkLoadPlugin', chunk => {
          compilation.addRuntimeModule(chunk, new RetryChunkLoadRuntimeModule());
        });
    });
  }
};
