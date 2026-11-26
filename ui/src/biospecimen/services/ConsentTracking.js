import http from '@/common/services/HttpClient.js';

const listeners = new Set();

export default {
  getStatus(cprId) {
    return http.get('collection-protocol-registrations/' + cprId + '/consent-status');
  },

  subscribe(listener) {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },

  changed(cprId) {
    listeners.forEach(listener => listener(cprId));
  }
};
