<template>
  <os-page>
    <os-page-head>
      <template #breadcrumb><os-breadcrumb :items="ctx.bcrumb" /></template>
      <span>
        <h3 v-if="!dataCtx.unit.id" v-t="'specimen_type_units.create'">Create Specimen Type Unit</h3>
        <h3 v-else v-t="'specimen_type_units.update'">Update Specimen Type Unit</h3>
      </span>
    </os-page-head>

    <os-page-body>
      <div v-if="ctx.loading" v-t="'common.loading'">Loading...</div>
      <template v-else>
        <os-message type="info"><span v-t="'specimen_type_units.priority_help'">
          The most specific matching rule is used: protocol and type, protocol and class, global type, then global class.
        </span></os-message>
        <os-message v-if="dataCtx.unit.id" type="warn"><span v-t="'specimen_type_units.history_warning'">
          Changing a unit does not convert quantities already stored for existing specimens.
        </span></os-message>
        <os-message v-if="ctx.units.length === 0" type="warn"><span v-t="'specimen_type_units.no_units_warning'">
          Create permissible values for specimen units before defining this rule.
        </span></os-message>

        <os-form ref="unitForm" :schema="formSchema" :data="dataCtx" @input="handleInput($event)">
          <div>
            <os-button primary :label="$t(!dataCtx.unit.id ? 'common.buttons.create' : 'common.buttons.update')"
              @click="saveOrUpdate" />
            <os-button text :label="$t('common.buttons.cancel')" @click="cancel" />
          </div>
        </os-form>
      </template>
    </os-page-body>
  </os-page>
</template>

<script>
import { reactive } from 'vue';

import alertSvc from '@/common/services/Alerts.js';
import cpSvc from '@/biospecimen/services/CollectionProtocol.js';
import i18n from '@/common/services/I18n.js';
import pvAdminSvc from '@/administrative/services/PermissibleValueAdmin.js';
import routerSvc from '@/common/services/Router.js';
import util from '@/common/services/Util.js';
import unitSvc from '@/administrative/services/SpecimenTypeUnit.js';

export default {
  props: ['unitId'],

  setup() {
    const ctx = reactive({
      bcrumb: [
        {url: routerSvc.getUrl('PvsListItemValues', {attribute: 'specimen_unit'}), label: i18n.msg('pvs.attribute_names.specimen_unit')},
        {url: routerSvc.getUrl('SpecimenTypeUnitsList'), label: i18n.msg('specimen_type_units.list')}
      ],
      loading: true,
      protocols: [],
      specimenClasses: [],
      specimenTypes: [],
      units: []
    });
    const dataCtx = reactive({unit: {cpId: null}});

    const formSchema = {
      rows: [
        {fields: [{
          name: 'unit.cpId', labelCode: 'specimen_type_units.scope', type: 'dropdown',
          listSource: {options: ctx.protocols, displayProp: 'name', selectProp: 'value'}
        }]},
        {fields: [{
          name: 'unit.specimenClass', labelCode: 'specimen_type_units.specimen_class', type: 'dropdown',
          listSource: {options: ctx.specimenClasses, displayProp: 'name', selectProp: 'value'},
          validations: {required: {messageCode: 'specimen_type_units.class_req'}}
        }]},
        {fields: [{
          name: 'unit.type', labelCode: 'specimen_type_units.specimen_type', type: 'dropdown',
          listSource: {options: ctx.specimenTypes, displayProp: 'name', selectProp: 'value'}
        }]},
        {fields: [{
          name: 'unit.quantityUnit', labelCode: 'specimen_type_units.quantity_unit', type: 'dropdown',
          listSource: {options: ctx.units, displayProp: 'name', selectProp: 'value'}
        }]},
        {fields: [{
          name: 'unit.concentrationUnit', labelCode: 'specimen_type_units.concentration_unit', type: 'dropdown',
          listSource: {options: ctx.units, displayProp: 'name', selectProp: 'value'}
        }]}
      ]
    };

    return {ctx, dataCtx, formSchema};
  },

  async created() {
    await this.loadOptions();
    if (+this.unitId > 0) {
      this.dataCtx.unit = await unitSvc.getUnit(+this.unitId);
      this.loadTypes(this.dataCtx.unit.specimenClass);
    }
    this.ctx.loading = false;
  },

  methods: {
    async loadOptions() {
      const [cps, specimenTypes, units] = await Promise.all([
        cpSvc.getCps({maxResults: 1000}),
        pvAdminSvc.getPvs({attribute: 'specimen_type', includeParentValue: true, activityStatus: 'Active', maxResults: 1000}),
        pvAdminSvc.getPvs({attribute: 'specimen_unit', activityStatus: 'Active', maxResults: 1000})
      ]);

      this.ctx.protocols.splice(0, this.ctx.protocols.length,
        {name: this.$t('specimen_type_units.global'), value: null},
        ...cps.map(cp => ({name: cp.shortTitle + ' - ' + cp.title, value: cp.id})));
      this.ctx.specimenClasses.splice(0, this.ctx.specimenClasses.length,
        ...specimenTypes.filter(pv => !pv.parentValue).map(pv => ({name: pv.value, value: pv.value})));
      this._allSpecimenTypes = specimenTypes;
      this.ctx.units.splice(0, this.ctx.units.length,
        ...units.map(pv => ({name: pv.value, value: pv.value})));
    },

    loadTypes(specimenClass) {
      const types = this._allSpecimenTypes || [];
      this.ctx.specimenTypes.splice(0, this.ctx.specimenTypes.length,
        {name: this.$t('specimen_type_units.all_types'), value: null},
        ...types.filter(pv => pv.parentValue === specimenClass).map(pv => ({name: pv.value, value: pv.value})));
    },

    handleInput({data}) {
      const oldClass = this.dataCtx.unit.specimenClass;
      Object.assign(this.dataCtx, data);
      if (oldClass !== this.dataCtx.unit.specimenClass) {
        this.dataCtx.unit.type = null;
        this.loadTypes(this.dataCtx.unit.specimenClass);
      }
    },

    async saveOrUpdate() {
      if (!this.$refs.unitForm.validate()) {
        return;
      }

      const unit = this.dataCtx.unit;
      if (!unit.quantityUnit && !unit.concentrationUnit) {
        alertSvc.error({code: 'specimen_type_units.unit_req'});
        return;
      }

      await unitSvc.saveOrUpdate(unit);
      await util.loadSpecimenUnits();
      alertSvc.success({code: 'specimen_type_units.saved'});
      routerSvc.goto('SpecimenTypeUnitsList');
    },

    cancel() {
      routerSvc.back();
    }
  }
}
</script>
