
import routerSvc from '@/common/services/Router.js';

export default {
  summary: {
    title: {
      text: 'attribute.name',
      url: (ro, query) => routerSvc.getUrl('PvsListItemValues', {attribute: ro.attribute.name}, query)
    },
    descriptions: []
  },

  columns: [
    {
      name: 'attribute.name',
      captionCode: 'pvs.attribute',
      href: (row, query) => routerSvc.getUrl('PvsListItemValues', {attribute: row.rowObject.attribute.name}, query)
    },
    {
      name: 'attribute.count',
      captionCode: 'pvs.value_count'
    }
  ]
}
