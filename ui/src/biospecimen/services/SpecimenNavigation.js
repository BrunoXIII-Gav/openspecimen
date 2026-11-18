import http from '@/common/services/HttpClient.js';

class SpecimenNavigation {
  registrations(cpId, startAt = 0) { return http.get('specimens/navigation', {cpId, startAt, maxResults: 50}); }
  participantListSource(cpId) {
    const request = ({filters, pageSize, orderDirection, includeCount}) => http.get('specimens/navigation', {
      cpId, maxResults: pageSize || 101, ppid: filters?.ppid || undefined,
      descending: orderDirection !== 'asc', includeCount: !!includeCount
    });
    return {
      config: {filters: [{expr: 'ppid', caption: 'PPID', dataType: 'STRING', searchType: 'contains'}]},
      load: async options => {
        const result = await request(options);
        return {columns: [{expr: 'ppid', caption: 'PPID', metainfo: {showLink: 'true'}}],
          rows: result.registrations.map(row => ({data: [row.ppid], hidden: {cpId: row.cpId, cprId: row.id}})), size: result.size};
      },
      count: async filters => (await request({filters, pageSize: 1, includeCount: true})).size
    };
  }

  async visits(cpId, cprId) {
    const result = [];
    for (let startAt = 0; ; startAt += 50) {
      const page = await this.registration(cpId, cprId, startAt);
      result.push(...page.visits);
      if (!page.hasMore) return result;
    }
  }
  registration(cpId, cprId, startAt = 0) { return http.get('specimens/navigation/' + cprId, {cpId, startAt, maxResults: 50}); }
  visit(cpId, cprId, visitId) {
    return http.get('specimens/matrix-context', {cpId, visitId}).then(context => {
      if (Number(context.cpId) !== Number(cpId) || Number(context.cpr?.id) !== Number(cprId) ||
        Number(context.visit?.id) !== Number(visitId)) throw Error('Invalid navigation context');
      return {...context.visit, collectionReady: context.visitStatus === 'Complete'};
    });
  }
}

export default new SpecimenNavigation();
