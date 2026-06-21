
import routerSvc from '@/common/services/Router.js';

export default {
  columns: [
    {
      name: 'pv.value',
      captionCode: 'pvs.value',
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
