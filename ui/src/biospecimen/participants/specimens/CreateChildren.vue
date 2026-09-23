<template>
  <os-page>
    <os-page-head>
      <template #breadcrumb>
        <os-breadcrumb :items="bcrumb" />
      </template>

      <span class="os-title">
        <h3>{{ title }}</h3>
      </span>
    </os-page-head>

    <os-page-body>
      <div class="children-form" v-if="parent.id">
        <div class="parent-summary">
          <div>
            <span class="summary-label">{{ $t('specimens.parent_specimen') }}</span>
            <strong>{{ parent.label }}</strong>
          </div>
          <div>
            <span class="summary-label">{{ $t('specimens.type') }}</span>
            <strong>{{ parent.type }}</strong>
          </div>
          <div>
            <span class="summary-label">{{ $t('specimens.available_quantity') }}</span>
            <strong>{{ quantityDisplay(parent.availableQty, parent) }}</strong>
          </div>
        </div>

        <div class="batch-options">
          <div class="field">
            <label>{{ lineage == 'Aliquot' ? $t('specimens.aliquot_count') : $t('specimens.derivative_count') }}</label>
            <os-input-number v-model="count" :max-fraction-digits="0" />
          </div>

          <div class="field">
            <label>{{ $t('specimens.collection_status') }}</label>
            <select v-model="status">
              <option value="Collected">{{ $t('specimens.collection_status_values.collected') }}</option>
              <option value="Pending">{{ $t('specimens.collection_status_values.pending') }}</option>
            </select>
          </div>

          <div class="field" v-if="lineage == 'Aliquot'">
            <label>{{ $t('specimens.quantity_per_aliquot') }}</label>
            <os-input-number v-model="qtyPerAliquot" :max-fraction-digits="8" :unit="parentUnit" />
          </div>
        </div>

        <div class="help-text">
          {{ $t(status == 'Pending' ? 'specimens.batch_pending_help' : 'specimens.batch_collected_help') }}
        </div>

        <div class="table-wrap">
          <table class="children-table">
            <thead>
              <tr>
                <th>#</th>
                <th v-if="manualLabels">{{ $t('specimens.label') }}</th>
                <th v-if="lineage == 'Derived'">{{ $t('specimens.type') }}</th>
                <th v-if="lineage == 'Derived'">{{ $t('specimens.parent_consumed_quantity') }}</th>
                <th v-if="lineage == 'Derived'">{{ $t('specimens.resulting_quantity') }}</th>
                <th v-if="lineage == 'Aliquot'">{{ $t('specimens.initial_quantity') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in rows" :key="row.uid">
                <td>{{ index + 1 }}</td>
                <td v-if="manualLabels">
                  <os-input-text v-model="row.label" />
                </td>
                <td v-if="lineage == 'Derived'">
                  <os-specimen-type v-model="row.type" :context="{specimen: row}" entity="specimen" />
                </td>
                <td v-if="lineage == 'Derived'">
                  <div v-if="rows.length == 1" class="process-all">
                    <label>
                      <input type="checkbox" v-model="row.processAllParent" />
                      {{ $t('specimens.process_all_parent_short') }}
                    </label>
                  </div>
                  <os-input-number v-if="!row.processAllParent" v-model="row.parentConsumedQty"
                    :max-fraction-digits="8" :unit="parentUnit" />
                </td>
                <td v-if="lineage == 'Derived'">
                  <os-specimen-measure v-model="row.initialQty" :context="{specimen: row}"
                    entity="specimen" measure="quantity" />
                </td>
                <td v-if="lineage == 'Aliquot'">
                  {{ quantityDisplay(qtyPerAliquot, parent) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="total" v-if="status == 'Collected'">
          <span>{{ $t('specimens.total_parent_quantity') }}</span>
          <strong>{{ quantityDisplay(totalParentConsumption, parent) }}</strong>
        </div>

        <section class="custom-fields" v-if="customFieldsSchema.rows.length > 0">
          <h4>{{ $t('specimens.additional_fields') }}</h4>
          <div class="custom-fields-row" v-for="(row, index) in rows" :key="'custom-' + row.uid">
            <h5>{{ lineage == 'Aliquot' ? $t('specimens.aliquot') : $t('specimens.derived') }} #{{ index + 1 }}</h5>
            <os-form ref="customFieldForms" :schema="customFieldsSchema" :data="row.formData" />
          </div>
        </section>

        <div class="actions">
          <os-button primary :label="$t('specimens.create_all')" @click="createAll" />
          <os-button text :label="$t('common.buttons.cancel')" @click="cancel" />
        </div>
      </div>
    </os-page-body>
  </os-page>
</template>

<script>
import alertsSvc  from '@/common/services/Alerts.js';
import cpSvc      from '@/biospecimen/services/CollectionProtocol.js';
import routerSvc  from '@/common/services/Router.js';
import specimenSvc from '@/biospecimen/services/Specimen.js';
import formUtil    from '@/common/services/FormUtil.js';
import util       from '@/common/services/Util.js';

let nextUid = 1;

export default {
  props: ['cpr', 'visit', 'specimen'],

  data() {
    return {
      parent: {},
      count: 1,
      qtyPerAliquot: null,
      status: 'Collected',
      rows: [],
      saving: false,
      customFieldsSchema: {rows: []},
      customFieldsDefaultValues: {},
      customFieldsFormId: null
    };
  },

  computed: {
    lineage() {
      return this.specimen.lineage;
    },

    title() {
      return this.$t(this.lineage == 'Aliquot' ? 'specimens.create_multiple_aliquots' :
        'specimens.create_multiple_derivatives');
    },

    parentUnit() {
      return util.getSpecimenMeasureUnit(this.parent, 'quantity') || '';
    },

    manualLabels() {
      const cp = this.cpViewCtx.getCp() || {};
      return cp.manualSpecLabelEnabled || !this.specimen.labelFmt;
    },

    totalParentConsumption() {
      if (this.lineage == 'Aliquot') {
        return this.roundQuantity(this.qtyPerAliquot > 0 ? this.qtyPerAliquot * this.rows.length : 0);
      }

      if (this.rows.length == 1 && this.rows[0].processAllParent) {
        return this.parent.availableQty;
      }

      return this.roundQuantity(
        this.rows.reduce((total, row) => total + (+row.parentConsumedQty || 0), 0)
      );
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
        },
        {
          url: routerSvc.getUrl('ParticipantsListItemSpecimenDetail.Overview',
            {cpId, cprId, visitId, specimenId: this.parent.id}),
          label: this.parent.label
        }
      ];
    }
  },

  inject: ['cpViewCtx'],

  watch: {
    count() {
      this.resizeRows();
    }
  },

  async created() {
    const [parent, formDef] = await Promise.all([
      specimenSvc.getById(this.specimen.parentId),
      specimenSvc.getCustomFieldsForm(this.specimen.cpId, this.lineage)
    ]);
    this.parent = parent;
    if (formDef) {
      const {schema, defaultValues} = formUtil.fromDeToStdSchema(
        formDef, 'specimen.extensionDetail.attrsMap.'
      );
      this.customFieldsSchema = schema;
      this.customFieldsDefaultValues = defaultValues || {};
      this.customFieldsFormId = formDef.id;
    }

    this.resizeRows();
  },

  methods: {
    newRow() {
      const row = {
        uid: nextUid++,
        label: null,
        specimenClass: null,
        type: null,
        parentConsumedQty: null,
        processAllParent: false,
        initialQty: null,
        extensionDetail: this.customFieldsFormId ? {
          formId: this.customFieldsFormId,
          attrsMap: util.clone(this.customFieldsDefaultValues)
        } : null
      };

      row.formData = {
        specimen: row,
        objName: 'specimen',
        objCustomFields: 'specimen.extensionDetail.attrsMap',
        cp: this.cpViewCtx.getCp()
      };
      return row;
    },

    resizeRows() {
      let count = Math.floor(+this.count || 0);
      if (count < 1) count = 1;
      if (count > 100) count = 100;
      while (this.rows.length < count) this.rows.push(this.newRow());
      if (this.rows.length > count) this.rows.splice(count);
      if (this.rows.length > 1) this.rows.forEach(row => row.processAllParent = false);
    },

    quantityDisplay(qty, specimen) {
      if (qty == null || qty === '') return '-';
      const unit = util.getSpecimenMeasureUnit(specimen, 'quantity');
      return this.roundQuantity(qty) + (unit ? ' ' + unit : '');
    },

    roundQuantity(qty) {
      return Math.round((+qty + Number.EPSILON) * 100000000) / 100000000;
    },

    validate() {
      const count = +this.count;
      if (!Number.isInteger(count) || count < 1 || count > 100) {
        return 'specimens.invalid_children_count';
      }

      if (this.manualLabels && this.rows.some(row => !row.label || !row.label.trim())) {
        return 'specimens.batch_labels_required';
      }

      if (this.lineage == 'Aliquot' && !(this.qtyPerAliquot > 0)) {
        return 'specimens.aliquot_quantity_required';
      }

      if (this.lineage == 'Derived') {
        if (this.rows.some(row => !row.type)) {
          return 'specimens.derivative_type_required';
        }

        if (this.status == 'Collected' && this.rows.some(row => !(row.initialQty > 0))) {
          return 'specimens.resulting_quantity_required';
        }

        if (this.status == 'Collected' && this.rows.some(row =>
          !row.processAllParent && !(row.parentConsumedQty > 0))) {
          return 'specimens.parent_consumed_quantity_required';
        }

        if (this.status == 'Collected' && this.rows.some(row => row.processAllParent) &&
          !(this.parent.availableQty > 0)) {
          return 'specimens.parent_quantity_insufficient';
        }
      }

      if (this.status == 'Collected') {
        if (this.parent.initialQty == null || this.parent.availableQty == null) {
          return 'specimens.parent_quantity_required';
        }

        if (this.totalParentConsumption > this.parent.availableQty) {
          return 'specimens.parent_quantity_insufficient';
        }
      }

      return null;
    },

    async createAll() {
      if (this.saving) return;

      const customFieldForms = this.$refs.customFieldForms || [];
      if (!customFieldForms.every(form => form.validate())) {
        return;
      }

      const error = this.validate();
      if (error) {
        alertsSvc.error({code: error});
        return;
      }

      this.saving = true;
      try {
        const base = {
          cpId: this.specimen.cpId,
          cprId: this.specimen.cprId,
          cpShortTitle: this.specimen.cpShortTitle,
          visitId: this.specimen.visitId,
          lineage: this.lineage,
          parentId: this.parent.id,
          status: this.status,
          createdOn: new Date(),
          printLabel: false
        };

        const children = this.rows.map(row => ({
          ...base,
          label: this.manualLabels ? row.label.trim() : null,
          specimenClass: this.lineage == 'Derived' ? row.specimenClass : null,
          type: this.lineage == 'Derived' ? row.type : null,
          parentConsumedQty: this.lineage == 'Derived' && !row.processAllParent ? row.parentConsumedQty : null,
          processAllParent: this.lineage == 'Derived' ? row.processAllParent : false,
          initialQty: this.lineage == 'Aliquot' ? this.qtyPerAliquot : row.initialQty,
          availableQty: this.lineage == 'Aliquot' ? this.qtyPerAliquot : row.initialQty,
          extensionDetail: row.extensionDetail
        }));
        const saved = await specimenSvc.createChildren(children);

        alertsSvc.success({
          code: this.lineage == 'Aliquot' ? 'specimens.aliquots_created' : 'specimens.derivatives_created',
          args: {count: saved.length}
        });
        this.cancel();
      } finally {
        this.saving = false;
      }
    },

    cancel() {
      const {cpId, cprId, visitId} = this.specimen;
      routerSvc.goto('ParticipantsListItemSpecimenDetail.Overview',
        {cpId, cprId, visitId, specimenId: this.parent.id});
    }
  }
};
</script>

<style scoped>
.children-form {
  max-width: 1500px;
  padding: 1rem;
}

.parent-summary, .batch-options {
  display: grid;
  grid-template-columns: repeat(3, minmax(180px, 1fr));
  gap: 1rem;
  border: 1px solid #ddd;
  border-radius: 4px;
  padding: 1rem;
  margin-bottom: 1rem;
}

.summary-label, .field label {
  display: block;
  color: #666;
  margin-bottom: 0.4rem;
}

.field select {
  width: 100%;
  height: 2.35rem;
  border: 1px solid #ced4da;
  border-radius: 4px;
  padding: 0 0.5rem;
  background: white;
}

.help-text {
  color: #666;
  margin: 0 0 1rem 0;
}

.table-wrap {
  overflow-x: auto;
  border: 1px solid #ddd;
  border-radius: 4px;
}

.children-table {
  width: 100%;
  border-collapse: collapse;
}

.children-table th, .children-table td {
  padding: 0.65rem;
  border-bottom: 1px solid #ddd;
  text-align: left;
  vertical-align: middle;
}

.children-table th:first-child, .children-table td:first-child {
  width: 3rem;
  text-align: center;
}

.children-table tbody tr:last-child td {
  border-bottom: 0;
}

.process-all {
  margin-bottom: 0.35rem;
  white-space: nowrap;
}

.total {
  display: flex;
  justify-content: flex-end;
  gap: 1rem;
  padding: 1rem;
}

.actions {
  display: flex;
  gap: 0.5rem;
  margin-top: 1rem;
}

.custom-fields {
  margin-top: 1rem;
}

.custom-fields h4 {
  border-bottom: 1px solid #ddd;
  margin: 0;
  padding: 0.75rem 0;
}

.custom-fields-row {
  border: 1px solid #ddd;
  border-radius: 4px;
  margin-top: 0.75rem;
  padding: 0.25rem 1rem 1rem;
}

.custom-fields-row h5 {
  margin: 0.75rem 0 0;
}

@media (max-width: 800px) {
  .parent-summary, .batch-options {
    grid-template-columns: 1fr;
  }
}
</style>
