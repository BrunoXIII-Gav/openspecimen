<template>
  <os-page>
    <os-page-head>
      <template #breadcrumb>
        <os-breadcrumb :items="bcrumb" />
      </template>

      <span class="os-title">
        <h3>{{ $t('specimens.create_multiple_primary') }}</h3>
      </span>
    </os-page-head>

    <os-page-body>
      <div class="multiple-specimens" v-if="loaded">
        <section class="batch-setup">
          <div class="count-field">
            <label>{{ $t('specimens.specimen_count') }}</label>
            <os-input-number v-model="count" :max-fraction-digits="0" />
          </div>

          <div class="sharing-options">
            <label v-if="hasFields(TYPE_FIELDS)">
              <input type="checkbox" v-model="sharing.type" @change="sharingChanged('type')" />
              {{ $t('specimens.same_specimen_type') }}
            </label>
            <label v-if="hasFields(CLASSIFICATION_FIELDS)">
              <input type="checkbox" v-model="sharing.classification" @change="sharingChanged('classification')" />
              {{ $t('specimens.same_classification') }}
            </label>
            <label v-if="hasFields(COLLECTION_FIELDS)">
              <input type="checkbox" v-model="sharing.collection" @change="sharingChanged('collection')" />
              {{ $t('specimens.same_collection_details') }}
            </label>
            <label v-if="hasFields(CONTAINER_FIELDS)">
              <input type="checkbox" v-model="sharing.container" @change="sharingChanged('container')" />
              {{ $t('specimens.same_collection_container') }}
            </label>
            <label v-if="hasFields(RECEPTION_FIELDS)">
              <input type="checkbox" v-model="sharing.reception" @change="sharingChanged('reception')" />
              {{ $t('specimens.same_reception_details') }}
            </label>
          </div>
        </section>

        <section class="common-fields" v-if="commonSchema.rows.length > 0 || effectiveGeneralForms.length > 0">
          <h4>{{ $t('specimens.common_data') }}</h4>
          <p>{{ $t('specimens.common_data_help') }}</p>
          <os-form ref="commonForm" :schema="commonSchema" :data="commonCtx" @input="commonInput"
            v-if="commonSchema.rows.length > 0" />
          <os-form v-for="form in effectiveGeneralForms" :key="form.formCtxtId"
            :ref="generalFormRef(form)" :schema="form.schema" :data="generalFormData[formKey(form)]"
            :reference-prefix="'specimen-batch-general-' + cpr.id + '-' + formKey(form)" />
        </section>

        <section class="specimen-card" v-for="(row, index) in rows" :key="row.uid">
          <div class="specimen-card-header">
            <h4>{{ $t('specimens.primary_specimen_number', {number: index + 1}) }}</h4>
            <os-button text left-icon="copy" :label="$t('specimens.copy_previous')"
              @click="copyPrevious(index)" v-if="index > 0" />
          </div>

          <os-form ref="specimenForms" :schema="individualSchema" :data="row.dataCtx"
            :reference-prefix="'specimen-batch-' + cpr.id + '-' + row.uid"
            @input="specimenInput(row, $event)">
            <template #static-fields>
              <os-field-references :cp-id="row.dataCtx.cp.id" target="specimen" :cpr-id="cpr.id"
                :visit-id="visit.id" :target-prefix="'specimen-batch-' + cpr.id + '-' + row.uid" />
            </template>
          </os-form>

          <os-form v-for="form in individualForms(row)" :key="form.formCtxtId"
            :ref="rowFormRef(row, form)" :schema="form.schema" :data="row.formData[formKey(form)]"
            :reference-prefix="'specimen-batch-form-' + cpr.id + '-' + row.uid + '-' + formKey(form)" />
        </section>

        <div class="actions">
          <os-button primary :label="$t('specimens.create_primary_specimens', {count: rows.length})"
            :disabled="saving" @click="createAll" />
          <os-button text :label="$t('common.buttons.cancel')" :disabled="saving" @click="cancel" />
        </div>
      </div>
    </os-page-body>
  </os-page>
</template>

<script>
import alertsSvc   from '@/common/services/Alerts.js';
import cpSvc       from '@/biospecimen/services/CollectionProtocol.js';
import exprUtil    from '@/common/services/ExpressionUtil.js';
import formSvc     from '@/forms/services/Form.js';
import formUtil    from '@/common/services/FormUtil.js';
import routerSvc   from '@/common/services/Router.js';
import specimenSvc from '@/biospecimen/services/Specimen.js';
import util        from '@/common/services/Util.js';
import FieldReferences from '@/biospecimen/components/FieldReferences.vue';

let nextUid = 1;

const ALWAYS_COMMON_FIELDS = [
  'specimen.status',
  'specimen.createdOn'
];

const TYPE_FIELDS = [
  'specimen.type'
];

const CLASSIFICATION_FIELDS = [
  'specimen.biohazards',
  'specimen.pathology',
  'specimen.anatomicSite',
  'specimen.laterality'
];

const COLLECTION_FIELDS = [
  'specimen.collectionEvent.time',
  'specimen.collectionEvent.user',
  'specimen.collectionEvent.procedure',
  'specimen.collectionEvent.comments'
];

const CONTAINER_FIELDS = [
  'specimen.collectionEvent.container'
];

const RECEPTION_FIELDS = [
  'specimen.receivedEvent.time',
  'specimen.receivedEvent.user',
  'specimen.receivedEvent.receivedQuality',
  'specimen.receivedEvent.comments'
];

export default {
  components: {'os-field-references': FieldReferences},

  props: ['cpr', 'visit', 'specimen'],

  inject: ['cpViewCtx'],

  data() {
    return {
      TYPE_FIELDS,
      CLASSIFICATION_FIELDS,
      COLLECTION_FIELDS,
      CONTAINER_FIELDS,
      RECEPTION_FIELDS,
      count: 2,
      rows: [],
      formSchema: {rows: []},
      associatedForms: [],
      generalFormData: {},
      formRules: [],
      batchFormSettings: {
        generalFormIds: [],
        generalExtension: false
      },
      extensionGeneral: false,
      commonCtx: null,
      loaded: false,
      saving: false,
      sharing: {
        type: false,
        classification: true,
        collection: true,
        container: false,
        reception: true
      }
    };
  },

  computed: {
    sharedFields() {
      const fields = [...ALWAYS_COMMON_FIELDS];
      if (this.sharing.type) fields.push(...TYPE_FIELDS);
      if (this.sharing.classification) fields.push(...CLASSIFICATION_FIELDS);
      if (this.sharing.collection) fields.push(...COLLECTION_FIELDS);
      if (this.sharing.container) fields.push(...CONTAINER_FIELDS);
      if (this.sharing.reception) fields.push(...RECEPTION_FIELDS);
      if (this.extensionGeneral) fields.push(...this.extensionFieldNames);
      return fields;
    },

    extensionFieldNames() {
      return (this.formSchema.rows || []).flatMap(row => row.fields || [])
        .map(field => field.name)
        .filter(name => name && name.startsWith('specimen.extensionDetail.attrsMap.'));
    },

    commonSchema() {
      return this.filterSchema(this.formSchema, field => this.sharedFields.includes(field.name));
    },

    individualSchema() {
      return this.filterSchema(this.formSchema, field => !this.sharedFields.includes(field.name));
    },

    effectiveGeneralForms() {
      return this.associatedForms.filter(form => this.isEffectivelyGeneral(form));
    },

    bcrumb() {
      const cp = this.cpViewCtx.getCp() || {};
      const {cpId, cprId, visitId, eventId} = this.specimen;
      return [
        {url: routerSvc.getUrl('ParticipantsList', {cpId, cprId: -1}), label: cp.shortTitle},
        {url: routerSvc.getUrl('ParticipantsListItemDetail.Overview', {cpId, cprId}), label: this.cpr.ppid},
        {
          url: routerSvc.getUrl('ParticipantsListItemVisitDetail.Overview', {cpId, cprId, visitId, eventId}),
          label: cpSvc.getEventDescription(this.visit)
        }
      ];
    }
  },

  watch: {
    count() {
      this.resizeRows();
    }
  },

  async created() {
    const cpId = this.cpViewCtx.getCp().id;
    const [fields, layout, forms, settings, rules, orderSpec, extensionForm] = await Promise.all([
      this.cpViewCtx.getSpecimenDict(true, 'New'),
      this.cpViewCtx.getSpecimenAddEditLayout('New'),
      cpSvc.getForms(cpId, ['Specimen']),
      cpSvc.getWorkflow(cpId, 'multipleSpecimenForms'),
      specimenSvc.getFormDataEntryRules(cpId),
      specimenSvc.getFormsOrderSpec(cpId),
      specimenSvc.getCustomFieldsForm(cpId, 'New')
    ]);

    this.batchFormSettings = {
      generalFormIds: ((settings && settings.generalFormIds) || []).map(id => +id),
      generalExtension: !!(settings && settings.generalExtension)
    };
    this.formRules = rules || [];
    this.extensionGeneral = this.batchFormSettings.generalExtension &&
      !this.isUnsafeToShare(extensionForm);

    const standaloneForms = forms.filter(form =>
      !extensionForm || +form.formId != +extensionForm.id
    );
    standaloneForms.forEach(form => form.formName = form.formName || form.name);
    const sortedForms = this.cpViewCtx._sortForms(standaloneForms, orderSpec);
    this.associatedForms = await Promise.all(sortedForms.map(async form => {
      const formDef = await formSvc.getDefinition(form.formId);
      const {schema, defaultValues} = formUtil.fromDeToStdSchema(formDef);
      return {
        ...form,
        formDef,
        schema,
        defaultValues,
        $batchGeneralBlocked: this.isUnsafeToShare(formDef)
      };
    }));

    this.generalFormData = this.associatedForms.reduce((result, form) => {
      result[this.formKey(form)] = util.clone(form.defaultValues || {});
      return result;
    }, {});

    this.formSchema = formUtil.getFormSchema(fields, layout);
    this.commonCtx = this.newDataContext();
    formUtil.setDefaultValues(this.formSchema, this.commonCtx);
    this.resizeRows();
    this.syncSharedFields();
    this.loaded = true;
  },

  methods: {
    isUnsafeToShare(formDef) {
      return !!formDef && (formDef.rows || []).some(row => (row || []).some(field =>
        ['fileUpload', 'signature', 'subForm'].includes(field.type) ||
        field.unique == true || field.uniqueConstraint == true
      ));
    },

    formKey(form) {
      return form.formCtxtId || form.formId;
    },

    generalFormRef(form) {
      return 'general-form-' + this.formKey(form);
    },

    rowFormRef(row, form) {
      return 'specimen-form-' + row.uid + '-' + this.formKey(form);
    },

    isConfiguredGeneral(form) {
      return !form.$batchGeneralBlocked &&
        this.batchFormSettings.generalFormIds.includes(+form.formId);
    },

    isFormMatching(form, row) {
      if (!row) return false;
      return this.cpViewCtx._getMatchingForms(
        [form], this.formRules, row.dataCtx.item
      ).length > 0;
    },

    isEffectivelyGeneral(form) {
      return this.isConfiguredGeneral(form) && this.rows.length > 0 &&
        this.rows.every(row => this.isFormMatching(form, row));
    },

    individualForms(row) {
      return this.associatedForms.filter(form =>
        this.isFormMatching(form, row) && !this.isEffectivelyGeneral(form)
      );
    },

    newDataContext(specimen) {
      specimen = specimen || this.newSpecimen();
      formUtil.createCustomFieldsMap(specimen);
      const cp = this.cpViewCtx.getCp();
      const userRole = this.cpViewCtx.getRole();
      return {
        specimen,
        objName: 'specimen',
        objCustomFields: 'specimen.extensionDetail.attrsMap',
        cp,
        item: {cp, cpr: this.cpr, visit: this.visit, specimen, userRole},
        userRole
      };
    },

    newSpecimen() {
      const specimen = util.clone(this.specimen || {});
      delete specimen.id;
      specimen.lineage = 'New';
      specimen.status = specimen.status || 'Collected';
      specimen.parentId = null;
      specimen.children = [];
      if (specimen.initialQty != null) {
        specimen.availableQty = specimen.initialQty;
      }
      if (specimen.extensionDetail) {
        delete specimen.extensionDetail.id;
        delete specimen.extensionDetail.recordId;
      }
      return specimen;
    },

    newRow() {
      const dataCtx = this.newDataContext();
      const formData = this.associatedForms.reduce((result, form) => {
        result[this.formKey(form)] = util.clone(form.defaultValues || {});
        return result;
      }, {});

      formUtil.setDefaultValues(this.formSchema, dataCtx);
      return {uid: nextUid++, dataCtx, formData};
    },

    resizeRows() {
      let count = Math.floor(+this.count || 0);
      if (count < 1) count = 1;
      if (count > 25) count = 25;
      while (this.rows.length < count) this.rows.push(this.newRow());
      if (this.rows.length > count) this.rows.splice(count);
      this.syncSharedFields();
    },

    hasFields(names) {
      return this.formSchema.rows.some(row =>
        (row.fields || []).some(field => names.includes(field.name))
      );
    },

    filterSchema(schema, predicate) {
      const filtered = util.clone(schema || {rows: []});
      filtered.rows = (filtered.rows || []).map(row => ({
        ...row,
        fields: (row.fields || []).filter(predicate)
      })).filter(row => row.fields.length > 0);
      return filtered;
    },

    commonInput() {
      this.syncSharedFields();
    },

    specimenInput(row, {field, value}) {
      if (field.name == 'specimen.initialQty') {
        row.dataCtx.specimen.availableQty = value;
      }
    },

    sharingChanged(group) {

      const names = this.groupFields(group);
      if (this.sharing[group]) {
        const first = this.rows[0] && this.rows[0].dataCtx;
        if (first) {
          names.forEach(name => this.copyValue(first, this.commonCtx, name));
        }
        this.syncSharedFields();
      } else {
        this.rows.forEach(row => names.forEach(name => this.copyValue(this.commonCtx, row.dataCtx, name)));
      }
    },

    groupFields(group) {
      return {
        type: TYPE_FIELDS,
        classification: CLASSIFICATION_FIELDS,
        collection: COLLECTION_FIELDS,
        container: CONTAINER_FIELDS,
        reception: RECEPTION_FIELDS
      }[group] || [];
    },

    syncSharedFields() {
      if (!this.commonCtx) return;
      this.rows.forEach(row => {
        this.sharedFields.forEach(name => this.copyValue(this.commonCtx, row.dataCtx, name));
      });
    },

    copyValue(source, target, name) {
      const value = exprUtil.eval(source, name);
      exprUtil.setValue(target, name, util.clone(value));
    },

    copyPrevious(index) {
      if (index <= 0) return;
      const copy = util.clone(this.rows[index - 1].dataCtx.specimen);
      delete copy.id;
      copy.label = null;
      copy.barcode = null;
      copy.additionalLabel = null;
      copy.storageLocation = null;
      if (copy.extensionDetail) {
        delete copy.extensionDetail.id;
        delete copy.extensionDetail.recordId;
      }
      this.rows[index].dataCtx = this.newDataContext(copy);

      this.associatedForms.forEach(form => {
        const key = this.formKey(form);
        this.rows[index].formData[key] = form.$batchGeneralBlocked ?
          util.clone(form.defaultValues || {}) :
          util.clone(this.rows[index - 1].formData[key] || form.defaultValues || {});
      });

      this.syncSharedFields();
    },

    validateRef(refName) {
      const refs = this.$refs[refName];
      if (!refs) return true;
      return (Array.isArray(refs) ? refs : [refs]).every(form => form.validate());
    },

    validateAssociatedForms() {
      const generalValid = this.effectiveGeneralForms.every(form =>
        this.validateRef(this.generalFormRef(form))
      );

      const individualValid = this.rows.every(row =>
        this.individualForms(row).every(form =>
          this.validateRef(this.rowFormRef(row, form))
        )
      );
      return generalValid && individualValid;
    },

    formRecord(specimenIndex, form, data) {
      const record = util.clone(data || {});
      delete record.id;
      delete record.recordId;
      delete record.appData;
      return {
        specimenIndex,
        formId: form.formId,
        formCtxtId: form.formCtxtId,
        data: record
      };
    },

    getFormRecords() {
      const records = [];
      this.effectiveGeneralForms.forEach(form => {
        this.rows.forEach((row, specimenIndex) => {
          records.push(this.formRecord(
            specimenIndex, form, this.generalFormData[this.formKey(form)]
          ));
        });
      });

      this.rows.forEach((row, specimenIndex) => {
        this.individualForms(row).forEach(form => {
          records.push(this.formRecord(
            specimenIndex, form, row.formData[this.formKey(form)]
          ));
        });
      });
      return records;
    },

    async createAll() {
      if (this.saving) return;
      this.syncSharedFields();

      const commonValid = !this.$refs.commonForm || this.$refs.commonForm.validate();
      const specimenForms = this.$refs.specimenForms || [];
      const specimensValid = (Array.isArray(specimenForms) ? specimenForms : [specimenForms])
        .every(form => form.validate());
      if (!commonValid || !specimensValid || !this.validateAssociatedForms()) return;

      const count = +this.count;
      if (!Number.isInteger(count) || count < 1 || count > 25) {
        alertsSvc.error({code: 'specimens.invalid_primary_specimen_count'});
        return;
      }

      this.saving = true;
      try {
        const specimens = this.rows.map(row => {
          const specimen = util.clone(row.dataCtx.specimen);
          delete specimen.id;
          return specimen;
        });
        const formRecords = this.getFormRecords();
        const saved = await specimenSvc.createMultiple({specimens, formRecords});
        specimenSvc.clearSpecimens(this.visit);
        alertsSvc.success({code: 'specimens.primary_specimens_created', args: {count: saved.length}});
        this.cancel();
      } finally {
        this.saving = false;
      }
    },

    cancel() {
      const {cpId, cprId, visitId, eventId} = this.specimen;
      routerSvc.goto('ParticipantsListItemVisitDetail.Overview', {cpId, cprId, visitId, eventId});
    }
  }
};
</script>

<style scoped>
.multiple-specimens {
  max-width: 1500px;
  padding: 1rem;
}

.batch-setup, .common-fields, .specimen-card {
  border: 1px solid #ddd;
  border-radius: 4px;
  margin-bottom: 1rem;
  padding: 1rem;
}

.batch-setup {
  display: grid;
  grid-template-columns: minmax(180px, 260px) 1fr;
  gap: 1.5rem;
}

.count-field label {
  display: block;
  font-weight: 600;
  margin-bottom: 0.4rem;
}

.sharing-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(220px, 1fr));
  gap: 0.75rem 1.5rem;
  align-content: center;
}

.sharing-options label {
  display: flex;
  align-items: center;
  gap: 0.45rem;
}

.common-fields h4, .specimen-card h4 {
  margin: 0;
}

.common-fields p {
  color: #666;
  margin: 0.4rem 0 1rem;
}

.specimen-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #ddd;
  margin-bottom: 1rem;
  padding-bottom: 0.5rem;
}

.actions {
  display: flex;
  gap: 0.5rem;
  margin-top: 1rem;
}

@media (max-width: 800px) {
  .batch-setup, .sharing-options {
    grid-template-columns: 1fr;
  }
}
</style>
