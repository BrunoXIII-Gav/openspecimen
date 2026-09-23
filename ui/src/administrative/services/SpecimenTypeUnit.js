import http from '@/common/services/HttpClient.js';

class SpecimenTypeUnit {
  getUnits(opts) {
    return http.get('specimen-type-units', opts || {});
  }

  getUnit(id) {
    return http.get('specimen-type-units/' + id);
  }

  saveOrUpdate(unit) {
    if (unit.id) {
      return http.put('specimen-type-units/' + unit.id, unit);
    }

    return http.post('specimen-type-units', unit);
  }

  delete(id) {
    return http.delete('specimen-type-units/' + id);
  }
}

export default new SpecimenTypeUnit();
