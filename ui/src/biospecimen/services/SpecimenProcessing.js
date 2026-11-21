import http from '@/common/services/HttpClient.js';
import util from '@/common/services/Util.js';

class SpecimenProcessing {
  getForms(cpId) { return http.get('specimens/processing-forms', {cpId}); }

  settings() { return {form: null, schema: {rows: []}, data: {}, loading: false, error: false, request: 0}; }

  collectionGroups(rows) {
    const items = rows.filter(row => row.editable && ['Derived', 'Aliquot'].includes(row.specimen.lineage));
    if (!items.length) return [];
    const group = {...this.settings(), uid: 'collection', shared: true, rows: items};
    items.forEach(row => {
      row.processingGroup = group;
      row.processing = this.settings();
    });
    return [group];
  }

  active(row) { return row.editable && row.specimen.status == 'Collected' && !!row.processing; }

  effective(row) {
    const group = row.processingGroup;
    return {...group, data: group.shared ? group.data : row.processing.data};
  }

  share(group) {
    if (group.shared) {
      const first = group.rows[0];
      if (first) group.data = util.clone(first.processing.data);
    } else {
      group.rows.forEach(row => row.processing.data = util.clone(group.data));
    }
  }

  payload(row) {
    if (!this.active(row)) return undefined;
    const settings = this.effective(row);
    if (!settings.form) return undefined;
    const data = util.clone(settings.data);
    delete data.id; delete data.recordId; delete data.appData;
    return {formId: settings.form.formId, formCtxtId: settings.form.formCtxtId, data};
  }
}

export default new SpecimenProcessing();
