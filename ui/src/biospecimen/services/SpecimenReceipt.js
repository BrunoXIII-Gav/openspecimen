import http from '@/common/services/HttpClient.js';

const fields = ['receivedQuality', 'user', 'time', 'comments', 'receivedQtyDifferent', 'receivedQty', 'receivedQtyReason'];

class SpecimenReceipt {
  isPending(specimen) {
    return specimen?.lineage === 'New' && specimen.status === 'Collected' &&
      (!specimen.receivedEvent?.receivedQuality || specimen.receivedEvent.receivedQuality === 'To be Received');
  }

  load(cpId, {ids, visitId}) {
    return http.get('specimens/pending-receipt', {cpId, ids: ids?.join(','), visitId});
  }

  payload(rows) {
    return rows.map(({specimen}) => ({id: specimen.id, receivedEvent: fields.reduce((event, name) => {
      if (specimen.receivedEvent[name] !== undefined) event[name] = specimen.receivedEvent[name];
      return event;
    }, {})})).map(item => ({...item, receivedEvent: {...item.receivedEvent,
      user: item.receivedEvent.user ? {id: item.receivedEvent.user.id} : null}}));
  }

  save(rows) { return http.put('specimens/receive', this.payload(rows)); }
}

export default new SpecimenReceipt();
