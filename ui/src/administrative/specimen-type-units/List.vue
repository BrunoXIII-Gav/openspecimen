<template>
  <os-page>
    <os-page-head>
      <template #breadcrumb><os-breadcrumb :items="ctx.bcrumb" /></template>
      <span><h3 v-t="'specimen_type_units.list'">Specimen Type Units</h3></span>
      <template #right>
        <os-button left-icon="plus" :label="$t('common.buttons.create')" @click="createUnit" />
      </template>
    </os-page-head>

    <os-page-body>
      <div class="units-page">
        <os-message type="info" class="help-message">
          <span v-t="'specimen_type_units.help'">
            Define the unit used to enter quantities. Protocol-specific rules take precedence over global rules.
          </span>
        </os-message>

        <os-panel class="units-panel">
          <template #header>
            <span v-t="'specimen_type_units.rules'">Unit assignment rules</span>
          </template>
          <template #default>
            <div v-if="ctx.loading" class="loading" v-t="'common.loading'">Loading...</div>
            <div v-else-if="ctx.units.length === 0" class="empty" v-t="'specimen_type_units.none'">
              No specimen type unit rules have been configured.
            </div>
            <div v-else class="table-responsive">
              <table class="units-table">
                <thead>
                  <tr>
                    <th v-t="'specimen_type_units.scope'">Scope</th>
                    <th v-t="'specimen_type_units.specimen_class'">Specimen Class</th>
                    <th v-t="'specimen_type_units.specimen_type'">Specimen Type</th>
                    <th v-t="'specimen_type_units.quantity_unit'">Quantity Unit</th>
                    <th v-t="'specimen_type_units.concentration_unit'">Concentration Unit</th>
                    <th class="actions-heading" v-t="'specimen_type_units.actions'">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="unit in ctx.units" :key="unit.id">
                    <td><span class="scope">{{ scope(unit) }}</span></td>
                    <td>{{ unit.specimenClass }}</td>
                    <td>{{ unit.type || $t('specimen_type_units.all_types') }}</td>
                    <td><span class="unit-value">{{ unit.quantityUnit || '-' }}</span></td>
                    <td><span class="unit-value">{{ unit.concentrationUnit || '-' }}</span></td>
                    <td class="actions">
                      <os-button size="small" left-icon="edit" :label="$t('common.buttons.edit')" @click="editUnit(unit)" />
                      <os-button size="small" left-icon="trash" :label="$t('common.buttons.delete')" @click="confirmDelete(unit)" />
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </template>
        </os-panel>
      </div>
    </os-page-body>

    <os-confirm-delete ref="deleteDialog" :captcha="false">
      <template #message><span v-t="'specimen_type_units.confirm_delete'">Delete this unit rule?</span></template>
    </os-confirm-delete>
  </os-page>
</template>

<script>
import alertSvc from '@/common/services/Alerts.js';
import i18n from '@/common/services/I18n.js';
import routerSvc from '@/common/services/Router.js';
import util from '@/common/services/Util.js';
import unitSvc from '@/administrative/services/SpecimenTypeUnit.js';

export default {
  data() {
    return {
      ctx: {
        bcrumb: [{
          url: routerSvc.getUrl('PvsListItemValues', {attribute: 'specimen_unit'}),
          label: i18n.msg('pvs.attribute_names.specimen_unit')
        }],
        units: [],
        loading: true
      }
    };
  },

  created() {
    this.loadUnits();
  },

  methods: {
    async loadUnits() {
      this.ctx.loading = true;
      this.ctx.units = await unitSvc.getUnits({maxResults: 1000});
      this.ctx.units.sort((left, right) => this.scope(left).localeCompare(this.scope(right)) ||
        left.specimenClass.localeCompare(right.specimenClass) || (left.type || '').localeCompare(right.type || ''));
      this.ctx.loading = false;
    },

    scope(unit) {
      return unit.cpShortTitle || this.$t('specimen_type_units.global');
    },

    createUnit() {
      routerSvc.goto('SpecimenTypeUnitAddEdit', {unitId: -1});
    },

    editUnit(unit) {
      routerSvc.goto('SpecimenTypeUnitAddEdit', {unitId: unit.id});
    },

    confirmDelete(unit) {
      this.$refs.deleteDialog.open().then(async result => {
        if (result !== 'proceed') {
          return;
        }

        await unitSvc.delete(unit.id);
        await util.loadSpecimenUnits();
        alertSvc.success({code: 'specimen_type_units.deleted'});
        this.loadUnits();
      });
    }
  }
}
</script>

<style scoped>
.loading, .empty {
  padding: 1.5rem;
  text-align: center;
  color: #666;
}

.units-page {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.help-message {
  margin: 0;
}

.units-panel {
  overflow: hidden;
}

.units-table {
  width: 100%;
  border-collapse: collapse;
}

.units-table th {
  padding: 0.75rem 0.9rem;
  background: #f5f5f5;
  border-bottom: 1px solid #d9d9d9;
  color: #444;
  font-weight: 600;
  text-align: left;
  white-space: nowrap;
}

.units-table td {
  padding: 0.7rem 0.9rem;
  border-bottom: 1px solid #e7e7e7;
  vertical-align: middle;
}

.units-table tbody tr:last-child td {
  border-bottom: 0;
}

.units-table tbody tr:hover {
  background: #fafafa;
}

.scope, .unit-value {
  display: inline-block;
  padding: 0.18rem 0.5rem;
  border-radius: 1rem;
  background: #eef5fb;
  color: #245b87;
  font-size: 0.9rem;
}

.unit-value {
  min-width: 2.5rem;
  background: #f4f4f4;
  color: #444;
  text-align: center;
}

.actions {
  white-space: nowrap;
  text-align: right;
}

.actions-heading {
  text-align: right !important;
}

.actions > * + * {
  margin-left: 0.5rem;
}
</style>
