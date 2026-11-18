import http from '@/common/services/HttpClient.js';
import {allows, allowsExtension, canCreate, filterFields, stripRestrictedData} from './protocol-content-policy.mjs';

class ProtocolContent {
  async getAccess(cpId) {
    if (!(cpId > 0)) return {unrestricted: true, allowed: [], extensionKeys: {}};
    // Do not retain authorizations across role changes or users.
    return http.get('protocol-content/access', {cpId});
  }

  async filterFields(cpId, fields, extensionType) {
    return filterFields(fields, await this.getAccess(cpId), extensionType);
  }

  async filterLayout(cpId, layout, extensionType) {
    const access = await this.getAccess(cpId);
    return {...layout, rows: (layout.rows || []).map(row => ({...row,
      fields: filterFields(row.fields, access, extensionType)
    })).filter(row => row.fields.length)};
  }

  allows = allows;
  canCreate = canCreate;
  allowsExtension = allowsExtension;
  stripRestrictedData = stripRestrictedData;
}

export default new ProtocolContent();
