<template>
  <os-page>
    <os-page-head>
      <template #breadcrumb><os-breadcrumb :items="bcrumb" /></template>
      <span class="os-title"><h3>{{ $t(collectionMode ? 'specimens.collection_matrix.title' : 'specimens.receipt_matrix.title') }}</h3></span>
    </os-page-head>
    <os-page-body>
      <div class="collection-matrix" v-if="loaded">
        <os-message type="error" v-if="loadError">{{ $t(loadError) }}</os-message>
        <template v-else>
          <os-message type="info" v-if="!hasWork">{{ $t(collectionMode ? 'specimens.collection_matrix.no_authorized_pending' : 'specimens.receipt_matrix.empty') }}</os-message>
          <fieldset :disabled="saving" v-if="hasWork">
            <section v-if="needsVisitSave">
              <h4>{{ $t('specimens.collection_matrix.visit_details') }}</h4>
              <os-form ref="visitForm" :schema="visitSchema" :data="visitCtx" />
            </section>
            <details class="common-details" v-if="hasPrimaries && commonSchema.rows.length">
              <summary>{{ $t(hasCollectPrimaries ? (contentSvcAllowed('receipt') ? 'specimens.collection_matrix.common_details' : 'roles.content.collection_common') : 'specimens.receipt_matrix.common') }}</summary>
              <os-form ref="commonForm" :schema="commonSchema" :data="commonCtx" />
              <os-button :label="$t('specimens.collection_matrix.apply_common')" @click="applyCommon" :disabled="saving" />
            </details>
            <section class="processing-section" v-for="group in processingGroups" :key="group.uid" v-show="processingForms.length">
              <div class="processing-controls">
                <label>{{ $t('specimens.collection_matrix.processing_form') }}</label>
                <os-dropdown :model-value="group.form?.formCtxtId || null" :list-source="processingFormOptions"
                  :placeholder="$t('specimens.collection_matrix.choose_form')"
                  @update:model-value="processingFormChanged(group, $event)" />
                <os-boolean-checkbox v-model="group.shared" :inline-label="$t('specimens.collection_matrix.same_data')"
                  @change="processingShare(group)" />
                <span v-if="group.loading">{{ $t('common.loading') }}</span>
              </div>
              <os-message type="error" v-if="group.error">{{ $t('specimens.collection_matrix.processing_load_failed') }}</os-message>
              <div class="shared-processing-fields" v-if="group.shared && group.form">
                <os-form :ref="'processing-group-' + group.uid" :schema="group.schema" :data="group.data" />
              </div>
            </section>
            <div class="matrix-table">
              <os-table-form ref="matrix" :schema="matrixSchema" :data="{cp}" :items="displayRows"
                :tree-layout="!collectionMode" :show-row-actions="true" :expanded="expanded" @input="rowInput">
                <template #row-actions="{rowItem}">
                  <os-button text left-icon="edit" :label="$t('specimens.collection_matrix.details')"
                    @click="toggleDetails(rowItem.item)" v-if="(rowItem.item.editable || rowItem.item.receiptEditable) && rowItem.item.specimen.status == 'Collected'" />
                </template>
                <template #expanded-row="{rowItem}">
                  <div class="row-details" v-if="(rowItem.item.editable || rowItem.item.receiptEditable) && rowItem.item.specimen.status == 'Collected'">
                    <h4>{{ description(rowItem.item) }}</h4>
                    <os-form :ref="'details-' + rowItem.item.uid" :schema="rowItem.item.detailSchema" :data="rowItem.item"
                      :reference-prefix="'collection-' + rowItem.item.uid" @input="detailInput(rowItem.item)" />
                    <os-form :ref="'receipt-' + rowItem.item.uid" :schema="rowItem.item.receiptSchema" :data="rowItem.item"
                      v-if="rowItem.item.specimen.lineage == 'New' && rowItem.item.receiptSchema.rows.length" />
                    <div class="processing-detail" v-if="rowItem.item.processing && processingForms.length && !rowItem.item.processingGroup.shared && rowItem.item.processingGroup.form">
                      <os-form :ref="'processing-' + rowItem.item.uid" :schema="processingSvc.effective(rowItem.item).schema"
                        :data="processingSvc.effective(rowItem.item).data" />
                    </div>
                  </div>
                </template>
              </os-table-form>
            </div>
          </fieldset>
        </template>
        <div class="actions">
          <os-button primary :label="$t(collectionMode ? 'common.buttons.submit' : 'specimens.receipt_matrix.save')" :disabled="saving || !!loadError || !hasWork" @click="save" />
          <os-button text :label="$t('common.buttons.cancel')" :disabled="saving" @click="cancel" />
        </div>
      </div>
    </os-page-body>
  </os-page>
</template>

<script>
import contentSvc from '@/common/services/ProtocolContent.js';
import alertsSvc from '@/common/services/Alerts.js';
import exprUtil from '@/common/services/ExpressionUtil.js';
import formUtil from '@/common/services/FormUtil.js';
import routerSvc from '@/common/services/Router.js';
import util from '@/common/services/Util.js';
import cpSvc from '@/biospecimen/services/CollectionProtocol.js';
import collectionSvc from '@/biospecimen/services/SpecimenCollection.js';
import processingSvc from '@/biospecimen/services/SpecimenProcessing.js';
import formSvc from '@/forms/services/Form.js';
import {matrixReturnRoute} from '@/biospecimen/services/specimen-matrix-navigation.mjs';
import specimenSvc from '@/biospecimen/services/Specimen.js';
import visitSvc from '@/biospecimen/services/Visit.js';

const MATRIX_FIELDS = ['specimen.label', 'specimen.barcode', 'specimen.initialQty',
  'specimen.parentConsumedQty', 'specimen.storageLocation', 'specimen.status'];
const COMMON_FIELDS = ['specimen.collectionEvent.time', 'specimen.collectionEvent.user',
  'specimen.collectionEvent.container', 'specimen.collectionEvent.procedure',
  'specimen.receivedEvent.time', 'specimen.receivedEvent.user', 'specimen.receivedEvent.receivedQuality'];

export default {
  props: ['cpr', 'visit'],
  inject: ['cpViewCtx'],

  data() {
    const cp = this.cpViewCtx.getCp();
    const visit = util.clone({...this.visit, $specimens: undefined});
    const cpr = this.cpr || {cpId: cp.id, contextOnly: true};
    Object.assign(visit, {cpId: cp.id, cprId: cpr.id});
    if (!(visit.id > 0) || visit.status != 'Complete') {
      visit.status = 'Complete';
      visit.visitDate = visit.visitDate || new Date();
      if (!visit.site && cp.cpSites && cp.cpSites.length == 1) visit.site = cp.cpSites[0].siteName;
    }
    formUtil.createCustomFieldsMap(visit);
    return {
      contentAccess: null,
      matrixGroups: [],
      processingSvc, processingGroups: [], processingForms: [],
      cp, rows: [], expanded: [], loaded: false, saving: false, loadError: null,
      visitSchema: {rows: []}, commonSchema: {rows: []},
      visitCtx: {cp, cpr, visit, userRole: this.cpViewCtx.getRole(),
        objName: 'visit', objCustomFields: 'visit.extensionDetail.attrsMap'},
      commonCtx: {cp, specimen: {lineage: 'New', status: 'Collected', collectionEvent: {}, receivedEvent: {}}}
    };
  },

  computed: {
    collectionMode() { return this.$route.query.mode != 'information'; },

    displayRows() { return this.collectionMode ? this.rows.filter(row => row.editable) : this.rows; },

    processingFormOptions() {
      return {selectProp: 'formCtxtId', displayProp: 'formCaption', options: this.processingForms};
    },

    needsVisitSave() {
      if (this.collectionMode === false) return false;
      return !!this.visit && !this.cpr?.contextOnly && !this.visit.contextOnly && (!(this.visit.id > 0) ||
        (this.visit.status != 'Complete' && !this.rows.some(row => row.originalStatus == 'Collected')));
    },

    hasPrimaries() {
      return this.rows.some(row => (row.editable || row.receiptEditable) && row.specimen.lineage == 'New');
    },

    hasCollectPrimaries() { return this.rows.some(row => row.editable && row.specimen.lineage == 'New'); },

    hasWork() { return this.rows.some(row => row.editable || row.receiptEditable); },

    bcrumb() {
      if (!this.cpr) return [{url: routerSvc.getUrl('ParticipantsList', {cpId: this.cp.id}), label: this.cp.shortTitle}];
      return [
        {url: routerSvc.getUrl('ParticipantsList', {cpId: this.cp.id}), label: this.cp.shortTitle},
        {url: routerSvc.getUrl('ParticipantsListItemDetail.Overview', {cpId: this.cp.id, cprId: this.cpr.id}), label: this.cpr.ppid},
        {url: routerSvc.getUrl('ParticipantsListItemVisitDetail.Overview', this.routeParams(), {eventId: this.visit.eventId}),
          label: cpSvc.getEventDescription(this.visit)}
      ];
    },

    matrixSchema() {
      const editable = row => row.editable;
      const collected = row => row.editable && row.specimen.status == 'Collected';
      return {columns: [
        {name: 'description', type: 'text', labelCode: 'specimens.description',
          value: row => this.description(row), showCellWhen: () => false, showValue: true,
          uiStyle: {'min-width': '240px'}},
        {name: 'specimen.label', type: 'text', labelCode: 'specimens.label',
          value: row => row.specimen.label || (collectionSvc.manualLabel(row) ? '' : this.$t('specimens.collection_matrix.automatic')),
          showCellWhen: row => editable(row) && collectionSvc.manualLabel(row), showValue: true},
        ...(this.collectionMode && this.rows.some(row => row.editable && row.specimen.lineage != 'New') ? [
          {name: 'parentLabel', type: 'text', labelCode: 'specimens.parent_specimen',
            value: row => row.parentSpecimen?.label || row.specimen.parentLabel || '',
            showCellWhen: () => false, showValue: true}
        ] : []),
        ...(this.cp.barcodingEnabled || this.rows.some(row => row.specimen.preBarcodedTube) ? [
          {name: 'specimen.barcode', type: 'text', labelCode: 'specimens.barcode', showValue: true,
            showCellWhen: row => collected(row) && (!this.cp.specimenBarcodeFmt || row.specimen.preBarcodedTube)}
        ] : []),
        {name: 'specimen.initialQty', type: 'specimen-measure', entity: 'specimen', measure: 'quantity',
          labelCode: 'specimens.initial_quantity', showCellWhen: collected, showValue: true},
        {name: 'specimen.parentConsumedQty', type: 'number', maxFractionDigits: 8,
          minFractionDigits: 0,
          labelCode: 'specimens.parent_consumed_quantity',
          unit: ({parentSpecimen}) => parentSpecimen ? util.getSpecimenMeasureUnit(parentSpecimen, 'quantity') : '',
          showCellWhen: row => collected(row) && row.specimen.lineage == 'Derived', showValue: true,
          uiStyle: {'min-width': '170px'}},
        {name: 'specimen.storageLocation', type: 'storage-position', labelCode: 'specimens.location',
          showCellWhen: row => collected(row) && row.specimen.storageType != 'Virtual',
          showValue: true, uiStyle: {'min-width': '350px'}},
        {name: 'specimen.status', type: 'dropdown', labelCode: 'specimens.collection_status',
          listSource: {selectProp: 'value', displayProp: 'label', options: this.statusOptions()},
          valueI18nKeys: Object.fromEntries(this.statusOptions().map(option => [option.value, option.code])),
          showCellWhen: editable, showValue: true}
      ]};
    }
  },

  async created() {
    try {
      this.contentAccess = await contentSvc.getAccess(this.cp.id);
      const query = this.$route.query;
      const parseIds = value => String(value || '').split(',').filter(Boolean).map(Number);
      const selection = {specimenIds: parseIds(query.specimenIds), reqIds: parseIds(query.reqIds),
        explicit: query.specimenIds !== undefined || query.reqIds !== undefined,
        mode: this.collectionMode ? 'collect' : 'information'};
      if ([...selection.specimenIds, ...selection.reqIds].some(id => !Number.isSafeInteger(id) || id <= 0)) {
        this.loadError = 'specimens.collection_matrix.invalid_selection';
        return;
      }
      const rights = {canCreate: this.cpViewCtx.isCreateSpecimenAllowed?.() !== false,
        canUpdate: this.cpViewCtx.isUpdateSpecimenAllowed?.() !== false};
      const access = {...this.contentAccess, ...rights};
      if (!this.cpr || !this.visit) {
        this.matrixGroups = await collectionSvc.loadMatrix(this.cp.id, query);
        this.rows = this.matrixGroups.flatMap(group => collectionSvc.prepareRows(group.specimens,
          {specimenIds: group.selectedIds, explicit: group.selectedIds.length > 0, mode: selection.mode,
            includeDescendants: selection.mode == 'information', uidPrefix: 'v-' + group.visit.id + '-'},
          this.cp, group.cpr, group.visit, access));
      } else {
        const specimens = await visitSvc.getSpecimens(this.cpr.id, this.visit.eventId, this.visit.id > 0 ? this.visit.id : undefined);
        if (!selection.explicit && !specimens.length && this.needsVisitSave) {
          routerSvc.replace('VisitAddEdit', this.routeParams(), {eventId: this.visit.eventId}); return;
        }
        this.rows = collectionSvc.prepareRows(specimens, selection, this.cp, this.cpr, this.visitCtx.visit, access);
      }
      if (this.needsVisitSave) {
        const [visitFields, visitLayout] = await Promise.all([this.cpViewCtx.getVisitDict(true), this.cpViewCtx.getVisitAddEditLayout()]);
        this.visitSchema = this.filterSchema(formUtil.getFormSchema(visitFields, visitLayout), field => field.name != 'visit.status');
        formUtil.setDefaultValues(this.visitSchema, this.visitCtx, true);
      }
      const lineages = [...new Set(this.rows.filter(row => row.editable || row.receiptEditable).map(row => row.specimen.lineage))];
      const schemas = {};
      await Promise.all(lineages.map(async lineage => {
        const [dict, layout] = await Promise.all([
          this.cpViewCtx.getSpecimenDict(true, lineage), this.cpViewCtx.getSpecimenAddEditLayout(lineage)
        ]);
        schemas[lineage] = {dict, schema: formUtil.getFormSchema(dict, layout)};
      }));
      for (const row of this.rows) {
        if (!row.editable && !row.receiptEditable) continue;
        row.userRole = this.cpViewCtx.getRole();
        row.objName = 'specimen';
        row.objCustomFields = 'specimen.extensionDetail.attrsMap';
        const specimen = row.specimen;
        formUtil.createCustomFieldsMap(specimen);
        const {dict, schema: baseSchema} = schemas[specimen.lineage];
        const schema = util.clone(baseSchema);
        if (row.editable) formUtil.setDefaultValues(schema, row, true);
        // Requirement defaults take precedence over common data.
        if (row.editable && specimen.lineage == 'New') {
          specimen.collectionEvent = Object.assign({time: new Date(), user: specimen.collector,
            container: specimen.collectionContainer, procedure: specimen.collectionProcedure}, specimen.collectionEvent);
          specimen.receivedEvent = Object.assign({user: specimen.receiver}, specimen.receivedEvent);
        } else if (row.editable) {
          specimen.createdOn = specimen.createdOn || new Date();
        }
        contentSvc.stripRestrictedData(specimen, this.contentAccess);
        if (row.editable) specimen.availableQty = specimen.initialQty;
        row.matrixFields = dict.filter(field => MATRIX_FIELDS.includes(field.name));
        row.detailSchema = this.filterSchema(schema, field => !MATRIX_FIELDS.includes(field.name) &&
          !['specimen.availableQty', 'specimen.processAllParent'].includes(field.name) &&
          !field.name.startsWith('specimen.receivedEvent.'));
        if (!row.editable) row.detailSchema = {rows: []};
        row.receiptSchema = (row.editable || row.receiptEditable) && contentSvc.allows(this.contentAccess, 'receipt') ? specimenSvc.getReceivedEventAddEditFs(dict) : {rows: []};
        if (row.receiptEditable) {
          specimen.receivedEvent = specimen.receivedEvent || {};
          specimen.receivedEvent.receivedQuality = specimen.receivedEvent.receivedQuality || 'To be Received';
          row.receiptSchema = this.filterSchema(row.receiptSchema, field => field.name !== 'specimen.receivedEvent.newLabel');
          row.receiptSchema.rows.forEach(schemaRow => schemaRow.fields.forEach(field => {
            if (['specimen.receivedEvent.user', 'specimen.receivedEvent.time'].includes(field.name)) {
              field.validations = {...field.validations, required: {messageCode: 'specimens.receipt_matrix.required'}};
            }
          }));
        }
        if (!this.commonSchema.rows.length && specimen.lineage == 'New') {
          const commonFields = COMMON_FIELDS.filter(name => row.editable || name.startsWith('specimen.receivedEvent.')).map(name => dict.find(field => field.name == name)).filter(Boolean)
            .map(field => ({...util.clone(field), showWhen: 'true', validations: {}}));
          this.commonSchema = {rows: commonFields.map(field => ({fields: [field]}))};
        }
      }
      this.processingGroups = this.collectionMode ? processingSvc.collectionGroups(this.rows) : [];
      if (this.processingGroups.length) {
        this.processingForms = (await processingSvc.getForms(this.cp.id)).filter(form =>
          contentSvc.allows(this.contentAccess, 'form:' + form.formId));
      }
    } catch (error) {
      this.loadError = 'specimens.collection_matrix.load_failed';
    } finally {
      this.loaded = true;
    }
  },

  methods: {
    async processingFormChanged(target, key) {
      const request = ++target.request;
      target.form = this.processingForms.find(form => String(form.formCtxtId) == String(key)) || null;
      target.schema = {rows: []}; target.data = {}; target.error = false; target.loading = !!target.form;
      if (target.rows) target.rows.forEach(row => row.processing.data = {});
      if (!target.form) return;
      try {
        const definition = await formSvc.getDefinition(target.form.formId);
        if (request !== target.request) return;
        const {schema, defaultValues} = formUtil.fromDeToStdSchema(definition);
        target.schema = schema; target.data = util.clone(defaultValues || {});
        if (target.rows) {
          target.rows.forEach(row => row.processing.data = util.clone(target.data));
          this.expandProcessingRows(target);
        }
      } catch (error) {
        if (request === target.request) target.error = true;
      } finally {
        if (request === target.request) target.loading = false;
      }
    },

    processingShare(group) {
      processingSvc.share(group);
      this.expandProcessingRows(group);
    },

    expandProcessingRows(group) {
      if (group.shared || !group.form) return;
      this.expanded = [...new Set([...this.expanded,
        ...group.rows.filter(row => processingSvc.active(row))])];
    },

    contentSvcAllowed(key) {
      return contentSvc.allows(this.contentAccess, key);
    },
    filterSchema(schema, predicate) {
      return {...schema, rows: (schema.rows || []).map(row => ({...row, fields: row.fields.filter(predicate)}))
        .filter(row => row.fields.length)};
    },

    description(row) {
      return (row.specimen.reqLabel || row.specimen.type || '') + ' · ' +
        this.$t('specimens.' + ({New: 'new', Derived: 'derived', Aliquot: 'aliquot'}[row.specimen.lineage]));
    },

    statusOptions() {
      return [['Collected', 'collected'], ['Pending', 'pending'], ['Missed Collection', 'missed_collection'], ['Not Collected', 'not_collected']]
        .map(([value, key]) => ({value, code: 'specimens.collection_status_values.' + key,
          label: this.$t('specimens.collection_status_values.' + key)}));
    },

    routeParams(visit = this.visit) {
      return {cpId: this.cp.id, cprId: this.cpr.id, visitId: visit.id > 0 ? visit.id : -1};
    },

    toggleDetails(row) {
      this.expanded = this.expanded.includes(row) ? this.expanded.filter(item => item != row) : [...this.expanded, row];
    },

    rowInput({item, field}) {
      if (field.name == 'specimen.initialQty') item.specimen.availableQty = item.specimen.initialQty;
      if (field.name == 'specimen.status' && item.specimen.status != 'Collected') {
        const descendants = new Set([item.uid]);
        for (const row of this.rows) {
          if (descendants.has(row.parentUid)) {
            descendants.add(row.uid);
            if (row.editable) row.specimen.status = item.specimen.status;
          }
        }
      }
    },

    detailInput(row) {
      row.specimen.availableQty = row.specimen.initialQty;
    },

    applyCommon() {
      for (const row of this.rows.filter(row => (row.editable || row.receiptEditable) && row.specimen.lineage == 'New' && row.specimen.status == 'Collected')) {
        for (const name of COMMON_FIELDS.filter(name => row.editable || name.startsWith('specimen.receivedEvent.'))) {
          const value = exprUtil.getValue(this.commonCtx, name);
          if (value !== undefined && value !== null && value !== '') exprUtil.setValue(row, name, util.clone(value));
        }
      }
      alertsSvc.success({code: 'specimens.collection_matrix.common_applied'});
    },

    validateCoreFields() {
      for (const row of this.rows.filter(row => row.editable && row.specimen.status == 'Collected')) {
        for (const field of row.matrixFields) {
          if (field.name == 'specimen.label' && !collectionSvc.manualLabel(row)) continue;
          if (field.showWhen && !exprUtil.eval(row, field.showWhen)) continue;
          const rules = field.validations || {};
          const value = exprUtil.getValue(row, field.name);
          if ((rules.required || (rules.requiredIf && exprUtil.eval(row, rules.requiredIf.expr))) &&
            (value === null || value === undefined || value === '' || (typeof value == 'string' && !value.trim()))) {
            const rule = rules.required || rules.requiredIf;
            alertsSvc.error(rule.messageCode ? {code: rule.messageCode} : {code: 'common.form_validation_error'});
            return false;
          }
        }
      }
      return true;
    },

    async save() {
      if (this.saving || this.loadError) return;
      this.saving = true;
      try {
        const error = collectionSvc.validate(this.rows);
        if (error) { alertsSvc.error({code: error}); return; }
        const processing = this.rows.filter(row => processingSvc.active(row));
        if (processing.some(row => {
          const settings = processingSvc.effective(row);
          return settings.loading || settings.error;
        })) { alertsSvc.error({code: 'specimens.collection_matrix.processing_load_failed'}); return; }
        // Mount the detail forms even for descendants hidden by a collapsed tree node.
        for (const row of this.rows) {
          row.show = true;
          row.expanded = true;
        }
        this.expanded = this.rows.filter(row => (row.editable || row.receiptEditable) && row.specimen.status == 'Collected');
        await this.$nextTick();
        let valid = !this.needsVisitSave || this.$refs.visitForm.validate();
        valid = this.validateCoreFields() && valid;
        for (const row of this.expanded) {
          for (const prefix of ['details-', 'receipt-', 'processing-']) {
            const refs = this.$refs[prefix + row.uid];
            for (const form of refs ? (Array.isArray(refs) ? refs : [refs]) : []) valid = form.validate() && valid;
          }
        }
        for (const group of this.processingGroups.filter(group => group.shared && group.form &&
          group.rows.some(row => processingSvc.active(row)))) {
          const refs = this.$refs['processing-group-' + group.uid];
          for (const form of refs ? (Array.isArray(refs) ? refs : [refs]) : []) valid = form.validate() && valid;
        }
        if (!valid) return;
        const specimens = collectionSvc.buildSpecimens(this.rows);
        specimens.forEach(specimen => contentSvc.stripRestrictedData(specimen, this.contentAccess));
        const receipts = collectionSvc.buildReceipts(this.rows);
        let savedVisit;
        if (!this.needsVisitSave) {
          await collectionSvc.saveMatrix(this.cp.id, specimens, receipts);
        } else {
          savedVisit = await collectionSvc.collect({...this.visitCtx.visit, status: this.visit.status}, specimens);
        }
        if (this.cpr) visitSvc.clearVisits(this.cpr);
        if (this.visit) delete this.visit.$specimens;
        this.returnToOrigin(savedVisit || this.visit);
        alertsSvc.success({code: this.collectionMode === false ? 'specimens.receipt_matrix.saved' : 'specimens.collection_matrix.saved'});
      } finally {
        this.saving = false;
      }
    },

    returnToOrigin(visit = this.visit) {
      const origin = matrixReturnRoute(this.cp.id, this.$route?.query?.returnTo);
      if (origin) {
        // An anticipated visit (-1) becomes a real visit after its first collection.
        if (origin.name == 'ParticipantsListItemVisitDetail.Overview' && !(origin.params.visitId > 0) && visit?.id > 0) {
          origin.params.visitId = visit.id;
        }
        routerSvc.replace(origin.name, origin.params, origin.query);
      } else if (visit?.id > 0 && this.cpr?.id > 0) {
        routerSvc.replace('ParticipantsListItemVisitDetail.Overview', this.routeParams(visit),
          {eventId: visit.eventId || this.visit?.eventId});
      } else {
        routerSvc.replace('ParticipantsList', {cpId: this.cp.id}, {reload: true});
      }
    },

    cancel() {
      this.returnToOrigin();
    }
  }
};
</script>

<style scoped>
.collection-matrix { padding: 1rem; }
.processing-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 0.75rem; }
.processing-controls .os-dropdown { width: 19rem; max-width: 100%; }
.processing-controls label { margin: 0; }
.shared-processing-fields { padding-top: 1rem; }
.processing-detail { margin-top: 1rem; }
.collection-matrix section, .common-details { margin-bottom: 1.5rem; padding: 1rem; border: 1px solid #ddd; border-radius: 4px; }
.common-details summary { cursor: pointer; margin-bottom: 1rem; }
.matrix-table { min-width: 0; }
.matrix-table :deep(.os-table-form) { height: auto; overflow: visible; }
fieldset { border: 0; padding: 0; min-width: 0; }
.row-details { padding: 1rem; max-width: 1100px; }
.actions { display: flex; gap: .5rem; margin-top: 1rem; }
</style>
