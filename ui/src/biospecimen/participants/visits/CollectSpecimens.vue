<template>
  <os-page>
    <os-page-head>
      <template #breadcrumb><os-breadcrumb :items="bcrumb" /></template>
      <span class="os-title"><h3>{{ $t('specimens.collection_matrix.title') }}</h3></span>
    </os-page-head>
    <os-page-body>
      <div class="collection-matrix" v-if="loaded">
        <os-message type="error" v-if="loadError">{{ $t(loadError) }}</os-message>
        <template v-else>
          <p>{{ $t('specimens.collection_matrix.help') }}</p>
          <fieldset :disabled="saving">
            <section v-if="needsVisitSave">
              <h4>{{ $t('specimens.collection_matrix.visit_details') }}</h4>
              <os-form ref="visitForm" :schema="visitSchema" :data="visitCtx" />
            </section>
            <details class="common-details" v-if="hasPrimaries">
              <summary>{{ $t('specimens.collection_matrix.common_details') }}</summary>
              <os-form ref="commonForm" :schema="commonSchema" :data="commonCtx" />
              <os-button :label="$t('specimens.collection_matrix.apply_common')" @click="applyCommon" :disabled="saving" />
            </details>
            <div class="matrix-table">
              <os-table-form ref="matrix" :schema="matrixSchema" :data="{cp}" :items="rows"
                :tree-layout="true" :show-row-actions="true" :expanded="expanded" @input="rowInput">
                <template #row-actions="{rowItem}">
                  <os-button text left-icon="edit" :label="$t('specimens.collection_matrix.details')"
                    @click="toggleDetails(rowItem.item)" v-if="rowItem.item.editable && rowItem.item.specimen.status == 'Collected'" />
                </template>
                <template #expanded-row="{rowItem}">
                  <div class="row-details" v-if="rowItem.item.editable && rowItem.item.specimen.status == 'Collected'">
                    <h4>{{ description(rowItem.item) }}</h4>
                    <os-form :ref="'details-' + rowItem.item.uid" :schema="rowItem.item.detailSchema" :data="rowItem.item"
                      :reference-prefix="'collection-' + rowItem.item.uid" @input="detailInput(rowItem.item)" />
                    <os-form :ref="'receipt-' + rowItem.item.uid" :schema="rowItem.item.receiptSchema" :data="rowItem.item"
                      v-if="rowItem.item.specimen.lineage == 'New'" />
                  </div>
                </template>
              </os-table-form>
            </div>
          </fieldset>
        </template>
        <div class="actions">
          <os-button primary :label="$t('common.buttons.submit')" :disabled="saving || !!loadError" @click="save" />
          <os-button text :label="$t('common.buttons.cancel')" :disabled="saving" @click="cancel" />
        </div>
      </div>
    </os-page-body>
  </os-page>
</template>

<script>
import alertsSvc from '@/common/services/Alerts.js';
import exprUtil from '@/common/services/ExpressionUtil.js';
import formUtil from '@/common/services/FormUtil.js';
import routerSvc from '@/common/services/Router.js';
import util from '@/common/services/Util.js';
import cpSvc from '@/biospecimen/services/CollectionProtocol.js';
import collectionSvc from '@/biospecimen/services/SpecimenCollection.js';
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
    Object.assign(visit, {cpId: cp.id, cprId: this.cpr.id});
    if (!(visit.id > 0) || visit.status != 'Complete') {
      visit.status = 'Complete';
      visit.visitDate = visit.visitDate || new Date();
      if (!visit.site && cp.cpSites && cp.cpSites.length == 1) visit.site = cp.cpSites[0].siteName;
    }
    formUtil.createCustomFieldsMap(visit);
    return {
      cp, rows: [], expanded: [], loaded: false, saving: false, loadError: null,
      visitSchema: {rows: []}, commonSchema: {rows: []},
      visitCtx: {cp, cpr: this.cpr, visit, userRole: this.cpViewCtx.getRole(),
        objName: 'visit', objCustomFields: 'visit.extensionDetail.attrsMap'},
      commonCtx: {cp, specimen: {lineage: 'New', status: 'Collected', collectionEvent: {}, receivedEvent: {}}}
    };
  },

  computed: {
    needsVisitSave() {
      return !(this.visit.id > 0) || this.visit.status != 'Complete';
    },

    hasPrimaries() {
      return this.rows.some(row => row.editable && row.specimen.lineage == 'New');
    },

    bcrumb() {
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
      const query = this.$route.query;
      const parseIds = value => String(value || '').split(',').filter(Boolean).map(Number);
      const selection = {specimenIds: parseIds(query.specimenIds), reqIds: parseIds(query.reqIds),
        explicit: query.specimenIds !== undefined || query.reqIds !== undefined};
      if ([...selection.specimenIds, ...selection.reqIds].some(id => !Number.isSafeInteger(id) || id <= 0)) {
        this.loadError = 'specimens.collection_matrix.invalid_selection';
        return;
      }
      const [specimens, visitFields, visitLayout] = await Promise.all([
        visitSvc.getSpecimens(this.cpr.id, this.visit.eventId, this.visit.id > 0 ? this.visit.id : undefined),
        this.cpViewCtx.getVisitDict(true), this.cpViewCtx.getVisitAddEditLayout()
      ]);
      if (!selection.explicit && !specimens.length) {
        routerSvc.replace('VisitAddEdit', this.routeParams(), {eventId: this.visit.eventId});
        return;
      }
      this.rows = collectionSvc.prepareRows(specimens, selection, this.cp, this.cpr, this.visitCtx.visit);
      if (!this.rows.some(row => row.editable)) {
        this.loadError = 'specimens.collection_matrix.nothing_to_collect';
        return;
      }
      this.visitSchema = this.filterSchema(formUtil.getFormSchema(visitFields, visitLayout), field => field.name != 'visit.status');
      formUtil.setDefaultValues(this.visitSchema, this.visitCtx, true);
      const lineages = [...new Set(this.rows.filter(row => row.editable).map(row => row.specimen.lineage))];
      const schemas = {};
      await Promise.all(lineages.map(async lineage => {
        const [dict, layout] = await Promise.all([
          this.cpViewCtx.getSpecimenDict(true, lineage), this.cpViewCtx.getSpecimenAddEditLayout(lineage)
        ]);
        schemas[lineage] = {dict, schema: formUtil.getFormSchema(dict, layout)};
      }));
      for (const row of this.rows) {
        if (!row.editable) continue;
        row.userRole = this.cpViewCtx.getRole();
        row.objName = 'specimen';
        row.objCustomFields = 'specimen.extensionDetail.attrsMap';
        const specimen = row.specimen;
        formUtil.createCustomFieldsMap(specimen);
        const {dict, schema: baseSchema} = schemas[specimen.lineage];
        const schema = util.clone(baseSchema);
        formUtil.setDefaultValues(schema, row, true);
        // Requirement defaults take precedence over common data.
        if (specimen.lineage == 'New') {
          specimen.collectionEvent = Object.assign({time: new Date(), user: specimen.collector,
            container: specimen.collectionContainer, procedure: specimen.collectionProcedure}, specimen.collectionEvent);
          specimen.receivedEvent = Object.assign({user: specimen.receiver}, specimen.receivedEvent);
        } else {
          specimen.createdOn = specimen.createdOn || new Date();
        }
        specimen.availableQty = specimen.initialQty;
        row.matrixFields = dict.filter(field => MATRIX_FIELDS.includes(field.name));
        row.detailSchema = this.filterSchema(schema, field => !MATRIX_FIELDS.includes(field.name) &&
          !['specimen.availableQty', 'specimen.processAllParent'].includes(field.name));
        row.receiptSchema = specimenSvc.getReceivedEventAddEditFs(dict);
        if (!this.commonSchema.rows.length && specimen.lineage == 'New') {
          const commonFields = COMMON_FIELDS.map(name => dict.find(field => field.name == name)).filter(Boolean)
            .map(field => ({...util.clone(field), showWhen: 'true', validations: {}}));
          this.commonSchema = {rows: commonFields.map(field => ({fields: [field]}))};
        }
      }
    } catch (error) {
      this.loadError = 'specimens.collection_matrix.load_failed';
    } finally {
      this.loaded = true;
    }
  },

  methods: {
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
      for (const row of this.rows.filter(row => row.editable && row.specimen.lineage == 'New' && row.specimen.status == 'Collected')) {
        for (const name of COMMON_FIELDS) {
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
        // Mount the detail forms even for descendants hidden by a collapsed tree node.
        for (const row of this.rows) {
          row.show = true;
          row.expanded = true;
        }
        this.expanded = this.rows.filter(row => row.editable && row.specimen.status == 'Collected');
        await this.$nextTick();
        let valid = !this.needsVisitSave || this.$refs.visitForm.validate();
        valid = this.validateCoreFields() && valid;
        for (const row of this.expanded) {
          for (const prefix of ['details-', 'receipt-']) {
            const refs = this.$refs[prefix + row.uid];
            for (const form of refs ? (Array.isArray(refs) ? refs : [refs]) : []) valid = form.validate() && valid;
          }
        }
        if (!valid) return;
        const visit = await collectionSvc.collect({...this.visitCtx.visit, status: this.visit.status}, collectionSvc.buildSpecimens(this.rows));
        visitSvc.clearVisits(this.cpr);
        delete this.visit.$specimens;
        routerSvc.replace('ParticipantsListItemVisitDetail.Overview', this.routeParams(visit), {eventId: visit.eventId || this.visit.eventId});
        alertsSvc.success({code: 'specimens.collection_matrix.saved'});
      } finally {
        this.saving = false;
      }
    },

    cancel() {
      routerSvc.goto('ParticipantsListItemVisitDetail.Overview', this.routeParams(), {eventId: this.visit.eventId});
    }
  }
};
</script>

<style scoped>
.collection-matrix { padding: 1rem; }
.collection-matrix section, .common-details { margin-bottom: 1.5rem; padding: 1rem; border: 1px solid #ddd; border-radius: 4px; }
.common-details summary { cursor: pointer; margin-bottom: 1rem; }
.matrix-table { overflow-x: auto; }
fieldset { border: 0; padding: 0; min-width: 0; }
.row-details { padding: 1rem; max-width: 1100px; }
.actions { display: flex; gap: .5rem; margin-top: 1rem; }
</style>
