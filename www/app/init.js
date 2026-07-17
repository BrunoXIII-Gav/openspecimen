
var ui = ui || {};
ui.os = ui.os || {};
ui.os.server = {
  /*hostname: 'localhost', // testing purpose
  port: 8080,
  secure: false,
  app: '/openspecimen'*/
};

ui.os.appProps = {
  plugins: []
};

ui.os.getPortalLogoutUrl = function() {
  var portalUrl = ui.os.appProps && ui.os.appProps.portal_logout_url;
  return typeof portalUrl == 'string' ? portalUrl.trim() : '';
};

ui.os.buildPortalRedirectUrl = function(source, reason) {
  var portalUrl = ui.os.getPortalLogoutUrl();
  if (!portalUrl) {
    return '';
  }

  try {
    var url = new URL(portalUrl, window.location.origin);
    url.searchParams.set('source', source || 'openspecimen');
    if (reason) {
      url.searchParams.set(reason, '1');
    }

    return url.toString();
  } catch (error) {
    console.log('Invalid portal logout URL', error);
    return '';
  }
};

ui.os.buildPortalBounceUrl = function(source, reason) {
  var targetUrl = ui.os.buildPortalRedirectUrl(source, reason);
  if (!targetUrl) {
    return '';
  }

  try {
    return (
      window.location.origin +
      window.location.pathname +
      '#/portal-logout?target=' +
      encodeURIComponent(targetUrl)
    );
  } catch (error) {
    console.log('Invalid portal bounce URL', error);
    return targetUrl;
  }
};

ui.os.redirectToPortal = function(source, reason) {
  var targetUrl = ui.os.buildPortalBounceUrl(source, reason);
  if (!targetUrl) {
    return false;
  }

  window.location.replace(targetUrl);
  return true;
};

ui.os.isSamlSessionActive = function() {
  return !!localStorage.getItem('osAuthToken') && !!ui.os.getPortalLogoutUrl();
};

ui.os.installBackGuard = function() {
  if (!ui.os.isSamlSessionActive() || sessionStorage.getItem('openspecimen.loggedOutToPortal') == '1') {
    sessionStorage.removeItem('openspecimen.samlBackGuardArmed');
    return;
  }

  if (sessionStorage.getItem('openspecimen.samlBackGuardArmed') != '1') {
    history.replaceState(
      Object.assign({}, history.state || {}, { openspecimenSamlBackGuard: 'root' }),
      '',
      window.location.href
    );
    history.pushState(
      Object.assign({}, history.state || {}, { openspecimenSamlBackGuard: 'trap' }),
      '',
      window.location.href
    );
    sessionStorage.setItem('openspecimen.samlBackGuardArmed', '1');
  }
};

ui.os.rearmBackGuard = function() {
  if (!ui.os.isSamlSessionActive() || sessionStorage.getItem('openspecimen.loggedOutToPortal') == '1') {
    sessionStorage.removeItem('openspecimen.samlBackGuardArmed');
    return;
  }

  if (((history.state || {}).openspecimenSamlBackGuard) != 'trap') {
    history.pushState(
      Object.assign({}, history.state || {}, { openspecimenSamlBackGuard: 'trap' }),
      '',
      window.location.href
    );
  }
};

ui.os.blockBrowserBack = function() {
  if (!ui.os.isSamlSessionActive() || sessionStorage.getItem('openspecimen.loggedOutToPortal') == '1') {
    sessionStorage.removeItem('openspecimen.samlBackGuardArmed');
    return;
  }

  history.go(1);
  setTimeout(ui.os.rearmBackGuard, 0);
};

(function($) {
  var pluginScriptsCnt = 0;

  var pluginsCnt = 0;

  var errorCount = 0;

  function init() {
    var server = ui.os.server;

    server.url = '';
    if (server.hostname) {
      var protocol = server.secure ? 'https://' : 'http://';
      server.url = protocol + server.hostname + ':' + server.port + server.app + '/';
    }

    $.get(server.url + 'rest/ng/config-settings/app-props')
      .done(
        function(appProps) {
          appProps = appProps || {};
          appProps.plugins = appProps.plugins || [];

          ui.os.appProps = appProps;
          handlePortalRestore();
          ui.os.installBackGuard();
          if (appProps.plugins.length > 0) {
            var qp = '_buildVersion=' + appProps.build_version +
              '&_buildDate=' + appProps.build_date;

            appProps.plugins.forEach(
              function(plugin) {
                loadPluginResources(qp, plugin);
              }
            );
          } else {
            bootstrapApp();
          }

          errorCount = 0;
        }
      ).fail(
        function(xhr) {
          console.log("Failed to load app props. Initialisation might fail");
          console.log(xhr);
          if (xhr.status == 401) {
            localStorage.removeItem('osAuthToken');
          }

          ++errorCount;
          if (errorCount == 3) {
            alert('Failed to load app props. Initialisation failed');
          } else {
            init();
          }
        }
      );
  }

  function handlePortalRestore() {
    if (sessionStorage.getItem('openspecimen.loggedOutToPortal') != '1') {
      return;
    }

    if (localStorage.getItem('osAuthToken')) {
      sessionStorage.removeItem('openspecimen.loggedOutToPortal');
      return;
    }

    ui.os.redirectToPortal('openspecimen', 'logged_out');
  }

  window.addEventListener('pageshow', ui.os.rearmBackGuard);
  window.addEventListener('hashchange', ui.os.rearmBackGuard);
  window.addEventListener('popstate', ui.os.blockBrowserBack);

  function loadPluginResources(qp, plugin) {
    var url = 'plugin-ui-resources/' + plugin + '/def.json?' + qp;
    $.get(url).done(
      function(def) {
        def.styles = def.styles || [];
        def.styles.forEach(loadCss);

        def.scripts = def.scripts || [];
        def.scripts.forEach(loadScript);

        --pluginsCnt;
        bootstrapAppIfAllResourcesLoaded();
      }
    ).fail(
      function() {
        console.log("Failed to load resources of plugin: " + plugin);

        --pluginsCnt;
        bootstrapAppIfAllResourcesLoaded();
      }
    );

    ++pluginsCnt;
  }

  function loadCss(src) {
    var link = document.createElement('link');
    link.rel = "stylesheet";
    link.type = "text/css";
    link.href = 'plugin-ui-resources/' + src
    document.head.appendChild(link);
  }

  function loadScript(src) {
    var script = document.createElement('script');
    script.src = 'plugin-ui-resources/' + src;
    document.body.appendChild(script);
    
    script.onload = /*script.onreadystatechange = */ onScriptLoadSuccess;
    script.onerror = onScriptLoadError;

    ++pluginScriptsCnt;
  }

  function onScriptLoadSuccess() {
    --pluginScriptsCnt;
    bootstrapAppIfAllResourcesLoaded();
  }

  function onScriptLoadError() {
    console.log("Failed to load: " + this.src);
    onScriptLoadSuccess();
  }

  function getPluginModules(plugins) {
    return plugins.map(pluginModuleName).filter(isModuleDefined);
  }

  function pluginModuleName(plugin) {
    return 'os.plugins.' + plugin;
  }

  function isModuleDefined(moduleName) {
    try {
      angular.module(moduleName);
      return true;
    } catch (error) {
      console.log("Module " + moduleName + " not defined");
      return false;
    }
  }

  function bootstrapAppIfAllResourcesLoaded() {
    if (pluginsCnt <= 0 && pluginScriptsCnt <= 0) {
      bootstrapApp();
    }
  }
    
  function bootstrapApp() {
    console.log("Bootstraping...");
    var definedModules = getPluginModules(ui.os.appProps.plugins);
    angular.element(document).ready(function() {
      angular.bootstrap(document, ['openspecimen'].concat(definedModules));
    });
  }

  init();

  function handlePortalRestoreEvent() {
    handlePortalRestore();
  }

  window.addEventListener('pageshow', handlePortalRestoreEvent);
  window.addEventListener('popstate', handlePortalRestoreEvent);
  window.addEventListener('hashchange', handlePortalRestoreEvent);
  document.addEventListener('visibilitychange', handlePortalRestoreEvent);
})(jQuery);
