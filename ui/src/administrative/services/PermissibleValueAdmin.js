
import http from '@/common/services/HttpClient.js';

class PermissibleValueAdmin {
  async getAttributes(opts) {
    return http.get('permissible-values/attributes', opts || {});
  }

  async getPvs(opts) {
    return http.get('permissible-values/v', opts || {});
  }

  async getPvsCount(opts) {
    return http.get('permissible-values/count', opts || {});
  }

  async getPv(id) {
    return http.get('permissible-values/v/' + id);
  }

  async saveOrUpdate(pv) {
    if (!pv.id) {
      return http.post('permissible-values/v', pv);
    } else {
      return http.put('permissible-values/v/' + pv.id, pv);
    }
  }

  async delete(pvId) {
    return http.delete('permissible-values/v/' + pvId);
  }

  getKnownAttributes() {
    return [
      'anatomic_site',
      'clinical_diagnosis',
      'clinical_status',
      'collection_container',
      'collection_procedure',
      'fixation_type',
      'frozen_method',
      'gender',
      'laterality',
      'pathology_status',
      'race',
      'receive_quality',
      'site_type',
      'specimen_biohazard',
      'specimen_type',
      'vital_status'
    ];
  }
}

export default new PermissibleValueAdmin();
