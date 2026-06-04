import routerSvc from '@/common/services/Router.js';

class Workflow {
  async collectVisitSpecimens(visit, repeat) {
    const {cpId, cprId, id: visitId} = visit;
    if (repeat) {
      routerSvc.goto('VisitAddEdit', {cpId, cprId, visitId: -1}, {eventId: visit.eventId});
    } else {
      routerSvc.goto('SpecimenAddEdit', {cpId, cprId, visitId, specimenId: -1});
    }
  }

  async collectPending(visit) {
    const {cpId, cprId, id: visitId} = visit;
    routerSvc.goto('SpecimenAddEdit', {cpId, cprId, visitId, specimenId: -1});
  }

  async addSpecimen(cp, visit) {
    let {cpId, cprId, id: visitId} = visit;
    cpId = cpId || cp?.id;
    if (cp?.specimenCentric && (!cprId || cprId <= 0)) {
      cprId = -1;
      visitId = visitId || -1;
    }

    routerSvc.goto('SpecimenAddEdit', {cpId, cprId, visitId, specimenId: -1});
  }

  async createAliquots(specimens) {
    const {cpId, cprId, visitId, id: parentId} = specimens[0];
    routerSvc.goto('SpecimenAddEdit', {cpId, cprId, visitId, specimenId: -1}, {parentId, lineage: 'Aliquot'});
  }

  async createDerivedSpecimens(specimens) {
    const {cpId, cprId, visitId, id: parentId} = specimens[0];
    routerSvc.goto('SpecimenAddEdit', {cpId, cprId, visitId, specimenId: -1}, {parentId, lineage: 'Derived'});
  }

  async createPooledSpecimens(specimens) {
    const {cpId, cprId, visitId, id: specimenId} = specimens[0];
    routerSvc.goto('SpecimenAddEdit', {cpId, cprId, visitId, specimenId: -1}, {parentId: specimenId, lineage: 'Pooled'});
  }

  async transferSpecimens(specimens) {
    const {cpId, cprId, visitId, id: specimenId} = specimens[0];
    routerSvc.goto('ParticipantsListItemSpecimenDetail.Overview', {cpId, cprId, visitId, specimenId});
  }

  async bulkAddEditEvents(specimens) {
    const {cpId, cprId, visitId, id: specimenId} = specimens[0];
    routerSvc.goto('SpecimenAddEdit', {cpId, cprId, visitId, specimenId});
  }

  async _createChildSpecimens(cpId, cpShortTitle, specimens) {
    const {cprId, visitId, id: specimenId} = specimens[0];
    routerSvc.goto('SpecimenAddEdit', {cpId, cprId, visitId, specimenId});
  }

  // eslint-disable-next-line no-unused-vars
  getDictionary(cpId)            { return Promise.resolve({}); }
  // eslint-disable-next-line no-unused-vars
  getWorkflow(cpId, name)        { return Promise.resolve(null); }
  // eslint-disable-next-line no-unused-vars
  getSysWorkflow(name)           { return Promise.resolve(null); }
  overrideFields(dict, columns)  { return columns; }

  _wfInstanceSvc() { return null; }
}

export default new Workflow();
