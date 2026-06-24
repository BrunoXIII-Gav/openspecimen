
import routerSvc from '@/common/services/Router.js';
import i18n      from '@/common/services/I18n.js';

export default {
  columns: [
    {
      name: 'pv.value',
      captionCode: 'pvs.value',
      value: (rowObject) => {
        const pv = rowObject.pv;
        return i18n.exists(pv.labelCode) ? i18n.msg(pv.labelCode) : pv.value;
      },
      href: (row) => routerSvc.getUrl('PvAddEdit', {pvId: row.rowObject.pv.id}, {attribute: row.rowObject.pv.attribute})
    },
    {
      name: 'pv.labelCode',
      captionCode: 'pvs.label_code'
    },
    {
      name: 'pv.conceptCode',
      captionCode: 'pvs.concept_code'
    },
    {
      name: 'pv.activityStatus',
      captionCode: 'pvs.status'
    }
  ]
}
