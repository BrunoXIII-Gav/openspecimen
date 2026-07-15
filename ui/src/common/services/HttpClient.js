
import axios from 'axios';
import alertSvc from './Alerts.js';
import routerSvc from './Router.js';
import ui from '@/global.js';

const PORTAL_LOGOUT_MARKER = 'openspecimen.loggedOutToPortal';

class HttpClient {
  protocol = '';

  host = '';

  port = '';

  path = '';

  headers = {'X-OS-API-CLIENT': 'webui'};

  listeners = [];

  constructor() {
  }

  addListener(listener) {
    if (this.listeners.indexOf(listener) == -1) {
      this.listeners.push(listener);
    }
  }

  removeListener(listener) {
    const idx = this.listeners.indexOf(listener);
    if (idx >= 0) {
      this.listeners.splice(idx, 1);
    }
  }

  get(url, params, options, errorHandler) {
    return this.promise('get', () => axios.get(this.getUrl(url), this.config(params, options)), errorHandler);
  }

  async post(url, data, params, options, errorHandler) {
    return this.promise('post', () => axios.post(this.getUrl(url), data, this.config(params, options)), errorHandler);
  }

  async put(url, data, params, options, errorHandler) {
    return this.promise('put', () => axios.put(this.getUrl(url), data, this.config(params, options)), errorHandler);
  }

  async patch(url, data, params, options, errorHandler) {
    return this.promise('patch', () => axios.patch(this.getUrl(url), data, this.config(params, options)), errorHandler);
  }

  async delete(url, data, params, options, errorHandler) {
    const config = this.config(params, options);
    config.data = data;
    return this.promise('delete', () => axios.delete(this.getUrl(url), config), errorHandler);
  }

  getUrl(url, {query = ''} = {}) {
    if (url.indexOf('http://') != 0 && url.indexOf('https://') != 0) {
      if (url.indexOf('/') == 0) {
        url = url.substring(1);
      }

      url = this.getServerAppUrl() + 'rest/ng/' + url;
    }

    if (query && typeof query == 'object') {
      let qp = '';
      Object.keys(query).forEach(
        (name) => {
          if (qp) {
            qp += '&';
          }

          qp += name + '=' + encodeURIComponent(query[name])
        }
      );

      if (qp) {
        url += '?' + qp;
      }
    }

    return url;
  }

  getServerUrl() {
    let result = '';
    if (this.host) {
      result = this.protocol ? (this.protocol + '://') : 'https://';
      result += this.host;
      if (this.port) {
        result += ':' + this.port;
      }
    }

    return result;
  }

  getServerAppUrl() {
    let result = this.getServerUrl();
    if (this.path) {
      result += this.path;
    }

    if (result && !result.endsWith('/')) {
      result += '/';
    }

    return result;
  }

  downloadFile(url) {
    let clickEvent;
    if (typeof Event == 'function') {
      clickEvent = new MouseEvent('click', { view: window, bubbles: true, cancelable: false });
    } else {
      clickEvent = document.createEvent('Event');
      clickEvent.initEvent('click', true, false);
    }

    let link = document.createElement('a');
    link.href = url;
    link.target = '_blank';
    link.dispatchEvent(clickEvent);
  }

  config(params, options = {}) {
    if (params) {
      params = Object.keys(params).reduce(
        (urlSearchParams, name) => {
          let value = params[name];
          if (value === undefined || value === null) {
            return urlSearchParams;
          }

          if (value instanceof Array) {
            value.forEach(element => urlSearchParams.append(name, element));
          } else {
            urlSearchParams.append(name, value);
          }

          return urlSearchParams;
        },
        new URLSearchParams()
      );
    }

    return {headers: this.headers, params: params, ...options};
  }

  notifyStart(method) {
    this.listeners.forEach(listener => listener.callStarted({method}));
  }

  notifyComplete(method, response) {
    this.listeners.forEach(listener => listener.callCompleted({method, response}));
  }

  notifyFail(method, response) {
    this.listeners.forEach(listener => listener.callFailed({method, response}));
  }

  buildPortalReturnUrl(baseUrl, source, reason) {
    const url = new URL(baseUrl, window.location.origin);
    url.searchParams.set('source', source);
    if (reason) {
      url.searchParams.set(reason, '1');
    }

    return url.toString();
  }

  buildPortalBounceUrl(baseUrl, source, reason) {
    const targetUrl = this.buildPortalReturnUrl(baseUrl, source, reason);
    return (
      window.location.origin +
      window.location.pathname +
      '#/portal-logout?target=' +
      encodeURIComponent(targetUrl)
    );
  }

  async redirectToPortalOnSamlExpiry(currentDomain) {
    const appProps = (ui && ui.global && ui.global.appProps) || {};
    const portalLogoutUrl = (appProps.portal_logout_url || '').trim();
    if (!portalLogoutUrl || !currentDomain) {
      return false;
    }

    try {
      const resp = await axios.get(this.getUrl('auth-domains'), {
        headers: {'X-OS-API-CLIENT': 'webui'}
      });
      const domains = resp?.data || [];
      const domain = domains.find(domain => domain.name == currentDomain);
      if (domain?.type != 'saml') {
        return false;
      }

      sessionStorage.setItem(PORTAL_LOGOUT_MARKER, '1');
      window.location.replace(this.buildPortalBounceUrl(portalLogoutUrl, 'openspecimen', 'session_expired'));
      return true;
    } catch (error) {
      console.error('Error determining SAML domain for portal timeout redirect', error);
      return false;
    }
  }

  async handleError(resp) {
    if (typeof resp == 'string') {
      alertSvc.error(resp);
    } else if (resp && typeof resp == 'object') {
      if (resp.status == 401) {
        const currentDomain = ui?.currentUser?.domain;
        localStorage.removeItem('osAuthToken');
        delete this.headers['X-OS-API-TOKEN'];

        if (await this.redirectToPortalOnSamlExpiry(currentDomain)) {
          return;
        }

        delete ui.currentUser;

        const {name, params, query} = routerSvc.getCurrentRoute();
        if (this._canSaveReqState(name)) {
          localStorage.setItem('osReqState', JSON.stringify({name, params, query, at: Date.now()}));
          routerSvc.goto('UserLogin', {}, {redirect: true});
        } else {
          localStorage.removeItem('osReqState');
          routerSvc.goto('UserLogin');
        }
        return;
      }

      const errors = resp.data;
      if (errors instanceof Array) {
        const msg = errors.map(err => err.message + ' (' + err.code + ')').join(',');
        alertSvc.error(msg);
      } else if (errors) {
        alertSvc.error(errors);
      } else {
        alertSvc.error(resp.status + ': ' + resp.statusText);
      }
    } else {
      alert(resp);
    }
  }

  _canSaveReqState(name) {
    if (!name) {
      return false;
    }

    const noLoginRoutes = [
      'App',
      'LoginApp',
      'NoLoginApp',
      'AppShell',
      'UserLogin',
      'UserLoginError',
      'UserForgotPassword',
      'UserResetPassword',
      'UserSignUp',
      'UserResetOtpSecretCode'
    ];

    return noLoginRoutes.indexOf(name) == -1;
  }

  promise(method, apiCall, errorHandler) {
    this.notifyStart(method);
    return new Promise((resolve, reject) => {
      apiCall()
        .then(resp => {
          this.notifyComplete(method, resp);
          resolve(resp.data);
        })
        .catch(e => {
          this.notifyFail(method, e.response || e.message);
          reject(e);
          if (typeof errorHandler == 'function') {
            errorHandler(resolve, e.response || e.message);
            return;
          }

          void this.handleError(e.response || e.message);
        });
    });
  }
}

export default new HttpClient();
