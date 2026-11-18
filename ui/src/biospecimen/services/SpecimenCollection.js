import http from '@/common/services/HttpClient.js';
import util from '@/common/services/Util.js';
import contentSvc from '@/common/services/ProtocolContent.js';
import receiptSvc from '@/biospecimen/services/SpecimenReceipt.js';

const pending = specimen => !specimen.status || specimen.status == 'Pending';
const populated = value => value !== null && value !== undefined && value !== '';

class SpecimenCollection {
  visibleVisitSpecimens(specimens, access) {
    if (access?.unrestricted !== false || !Array.isArray(access.createLineages)) return specimens;
    const visible = source => {
      if (pending(source) && source.lineage !== 'New' && !access.createLineages.includes(source.lineage)) return null;
      return {...source, children: (source.children || []).map(visible).filter(Boolean)};
    };
    return specimens.map(visible).filter(Boolean);
  }

  // Reload the visit tree rather than passing editable objects through navigation.
  // Include the complete ancestry, including intermediate derivatives hidden in the overview.
  prepareRows(specimens, selection, cp, cpr, visit, access) {
    const ids = new Set((selection.specimenIds || []).map(String));
    const reqIds = new Set((selection.reqIds || []).map(String));
    const explicit = selection.explicit;
    const resuming = specimens.some(specimen => specimen.status == 'Collected');
    const rows = [];
    const walk = (source, parent, depth, selectedAncestor = false) => {
      // A pending forbidden ancestor cannot be collected implicitly for an allowed child.
      if (pending(source) && Array.isArray(access?.createLineages) && !access.createLineages.includes(source.lineage)) return [];
      if (pending(source) && (access?.canCreate === false || (source.lineage == 'New' && access && !contentSvc.allows(access, 'collection')))) return [];
      // Initial visit fields belong to the original visit collection flow.
      if (pending(source) && source.lineage == 'New' && (cpr.contextOnly || visit.contextOnly) && visit.status != 'Complete' && !visit.collectionReady) return [];
      const receiptEditable = source.id > 0 && source.lineage == 'New' && source.status == 'Collected' &&
        (!source.activityStatus || source.activityStatus == 'Active') &&
        (!source.receivedEvent?.receivedQuality || source.receivedEvent.receivedQuality == 'To be Received') &&
        access?.canUpdate !== false && !!access && contentSvc.allows(access, 'receipt');
      const selected = explicit ? ids.has(String(source.id)) || reqIds.has(String(source.reqId)) || selectedAncestor :
        receiptEditable || source.status == 'Collected' || (pending(source) && (resuming || cp.visitCollectionMode != 'PRIMARY_SPMNS' || source.lineage == 'New'));
      const specimen = util.clone({...source, children: undefined});
      delete specimen.children;
      delete specimen.parent;
      const row = {
        uid: (selection.uidPrefix || '') + (source.id > 0 ? 's-' + source.id : 'r-' + source.reqId),
        parentUid: parent && parent.uid,
        parentSpecimen: parent && parent.specimen,
        specimen, cp, cpr, visit, depth, expanded: true, show: true,
        editable: pending(source), receiptEditable, originalStatus: source.status || 'Pending'
      };
      const children = (source.children || []).flatMap(child => walk(child, row, depth + 1, selection.includeDescendants && selected));
      if (!selected && children.length == 0) return [];

      Object.assign(specimen, {
        cpId: cp.id, cpShortTitle: cp.shortTitle, cprId: cpr.id,
        eventId: visit.eventId, visitId: visit.id > 0 ? visit.id : null
      });
      if (!(specimen.id > 0)) delete specimen.id;
      if (!specimen.labelFmt) {
        specimen.labelFmt = specimen.lineage == 'Aliquot' ? cp.aliquotLabelFmt || cp.aliquotLabelFmtToUse :
          specimen.lineage == 'Derived' ? cp.derivativeLabelFmt : cp.specimenLabelFmt;
      }
      if (row.editable) specimen.status = 'Collected';
      row.hasChildren = children.length > 0;
      return [row, ...children];
    };
    for (const specimen of specimens) rows.push(...walk(specimen, null, 0));
    return rows;
  }

  manualLabel(row) {
    return row.cp.manualSpecLabelEnabled || !row.specimen.labelFmt;
  }

  validate(rows) {
    if (!rows.some(row => (row.editable && row.specimen.status != 'Pending') || this.receiving(row))) {
      return 'specimens.collection_matrix.nothing_to_collect';
    }
    const labels = new Set();
    const barcodes = new Set();
    for (const row of rows) {
      const specimen = row.specimen;
      const parent = rows.find(item => item.uid == row.parentUid);
      if (!row.editable && specimen.status != 'Collected') return 'specimens.collection_matrix.parent_not_collected';
      if (row.editable && !['Collected', 'Pending', 'Missed Collection', 'Not Collected'].includes(specimen.status)) {
        return 'specimens.collection_status_req';
      }
      if (row.editable && specimen.status != 'Pending' && parent && parent.specimen.status == 'Pending') {
        return 'specimens.collection_matrix.parent_not_collected';
      }
      if (row.editable && specimen.status == 'Collected') {
        if (parent && parent.specimen.status != 'Collected') return 'specimens.collection_matrix.parent_not_collected';
        if (this.manualLabel(row) && !String(specimen.label || '').trim()) return 'specimens.label_req';
        if (specimen.lineage == 'Aliquot' && row.cp.aliquotQtyReq && !populated(specimen.initialQty)) {
          return 'specimens.initial_quantity_req';
        }
        for (const name of ['initialQty', 'parentConsumedQty', 'concentration']) {
          if (populated(specimen[name]) && (!Number.isFinite(+specimen[name]) || +specimen[name] < 0)) {
            return 'specimens.collection_matrix.invalid_quantity';
          }
        }
        if (specimen.lineage == 'Derived' && !(+specimen.parentConsumedQty > 0)) {
          return 'specimens.parent_consumed_quantity_required';
        }
        if (specimen.lineage == 'Aliquot' && !(+specimen.initialQty > 0)) {
          return 'specimens.aliquot_quantity_required';
        }
      }
      for (const [name, seen] of [['label', labels], ['barcode', barcodes]]) {
        const value = String(specimen[name] || '').trim();
        if (!value) continue;
        if (seen.has(value)) return 'specimens.collection_matrix.duplicate_' + name;
        seen.add(value);
      }
      const children = rows.filter(child => child.parentUid == row.uid && child.editable && child.specimen.status == 'Collected');
      const quantity = row.originalStatus == 'Collected' ? specimen.availableQty : specimen.initialQty;
      const consumed = children.reduce((sum, child) => sum +
        (+((child.specimen.lineage == 'Aliquot' ? child.specimen.initialQty : child.specimen.parentConsumedQty)) || 0), 0);
      if (children.length && (!populated(specimen.initialQty) || !populated(quantity))) return 'specimens.parent_quantity_required';
      if (populated(quantity) && consumed - +quantity > 0.000001) return 'specimens.parent_quantity_insufficient';
    }
    return null;
  }

  buildSpecimens(rows) {
    const fields = [
      'id', 'reqId', 'cpId', 'cprId', 'visitId', 'eventId', 'lineage', 'status',
      'label', 'barcode', 'additionalLabel', 'specimenClass', 'type', 'initialQty',
      'parentConsumedQty', 'concentration', 'storageLocation', 'collectionEvent', 'receivedEvent',
      'createdOn', 'createdBy', 'pathology', 'anatomicSite', 'laterality', 'biohazards',
      'freezeThawCycles', 'comments', 'extensionDetail', 'externalIds', 'imageId'
    ];
    const build = row => {
      // Untouched requirements stay anticipated; do not create pending specimens
      // merely because their rows were displayed in this collection.
      if (row.editable && row.specimen.status == 'Pending') return null;
      const children = rows.filter(child => child.parentUid == row.uid).map(build).filter(Boolean);
      if (!row.editable && !children.length) return null;
      // A collected ancestor is only a link. Never resubmit its old quantities or events.
      const specimen = row.editable ? fields.reduce((result, name) => {
        if (row.specimen[name] !== undefined) result[name] = util.clone(row.specimen[name]);
        return result;
      }, {}) : {id: row.specimen.id};
      if (row.editable && row.specimen.parentId > 0) specimen.parentId = row.specimen.parentId;
      if (typeof specimen.label == 'string') specimen.label = specimen.label.trim() || null;
      if (typeof specimen.barcode == 'string') specimen.barcode = specimen.barcode.trim() || null;
      if (specimen.lineage != 'Derived') delete specimen.parentConsumedQty;
      for (const name of ['initialQty', 'parentConsumedQty', 'concentration']) {
        if (populated(specimen[name])) specimen[name] = +specimen[name];
      }
      if (specimen.lineage != 'New' || specimen.status != 'Collected') {
        delete specimen.collectionEvent;
        delete specimen.receivedEvent;
      }
      if ((specimen.status && specimen.status != 'Collected') || row.specimen.storageType == 'Virtual') {
        delete specimen.storageLocation;
      }
      if (children.length) specimen.children = children;
      return specimen;
    };
    return rows.filter(row => !row.parentUid).map(build).filter(Boolean);
  }

  receiving(row) {
    return row.receiptEditable && row.specimen.receivedEvent?.receivedQuality && row.specimen.receivedEvent.receivedQuality != 'To be Received';
  }

  buildReceipts(rows) {
    return receiptSvc.payload(rows.filter(row => this.receiving(row)));
  }

  async loadMatrix(cpId, query) {
    const ids = query.ids ? String(query.ids).split(',').map(Number) : [];
    const visitId = query.visitId ? Number(query.visitId) : undefined;
    if ((!ids.length && !visitId) || ids.length > 500 || ids.some(id => !Number.isSafeInteger(id) || id <= 0) ||
      (visitId !== undefined && (!Number.isSafeInteger(visitId) || visitId <= 0))) throw Error('Invalid matrix selection');
    const contexts = ids.length ? await Promise.all(ids.map(id => http.get('specimens/' + id + '/authorized-context'))) :
      [await http.get('specimens/matrix-context', {cpId, visitId})];
    const groups = new Map();
    for (const context of contexts) {
      if (Number(context.cpId) !== Number(cpId) || !(context.visit?.id > 0) || !(context.cpr?.id > 0)) throw Error('Invalid matrix context');
      if (!groups.has(context.visit.id)) groups.set(context.visit.id, {cpr: context.cpr,
        visit: {...context.visit, status: context.visitStatus}, selectedIds: []});
      if (context.specimenId) groups.get(context.visit.id).selectedIds.push(context.specimenId);
    }
    for (const group of groups.values()) {
      group.specimens = await http.get('specimens', {cprId: group.cpr.id, visitId: group.visit.id});
    }
    return [...groups.values()];
  }

  saveMatrix(cpId, specimens, receipts) { return http.post('specimens/matrix', {cpId, specimens, receipts}); }

  async collect(visit, specimens) {
    if (!(visit.id > 0) || visit.status != 'Complete') {
      const toSave = util.clone(visit);
      if (!(toSave.id > 0)) delete toSave.id;
      toSave.status = 'Complete';
      delete toSave.$specimens;
      const result = await http.post('visits/collect', {visit: toSave, specimens});
      return result.visit;
    }
    await http.post('specimens/collect', specimens);
    return visit;
  }
}

export default new SpecimenCollection();
