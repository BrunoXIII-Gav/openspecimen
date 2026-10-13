<template>
  <os-page>
    <os-page-head>
      <template #breadcrumb>
        <os-breadcrumb :items="bcrumb" />
      </template>

      <span class="os-title">
        <h3>
          <span v-if="isPooling" v-t="'specimens.create_pooled_specimen'">Create Pooled Specimen</span>
          <span v-else-if="!dataCtx.specimen.id" v-t="'participants.add_specimen'">Add Specimen</span>
          <span v-else-if="!ctx.header.leftTitle" v-t="{path: 'common.update', args: {name: specimenLabel}}"></span>
          <span class="custom-title" v-else>
            <span v-t="{path: 'common.buttons.update'}">Update</span>
            <os-dynamic-template v-if="ctx.header.leftTitle" :template="ctx.header.leftTitle"
              :cp="dataCtx.cp" :cpr="cpr" :visit="visit" :specimen="specimen" :hasPhiAccess="hasPhiAccess" />
          </span>
        </h3>
      </span>

      <template #right v-if="ctx.header.rightTitle">
        <h3>
          <os-dynamic-template :template="ctx.header.rightTitle" :cp="dataCtx.cp" :cpr="cpr" :visit="visit"
            :specimen="specimen" :hasPhiAccess="hasPhiAccess" />
        </h3>
      </template>
    </os-page-head>

    <os-page-body>
      <section class="pool-sources" v-if="isPooling">
        <h4>{{ $t('specimens.pool_sources') }}</h4>
        <p>{{ $t('specimens.pool_sources_help') }}</p>
        <div class="pool-table">
          <table class="os-table">
            <thead>
              <tr>
                <th>{{ $t('specimens.label') }}</th>
                <th>{{ $t('specimens.type') }}</th>
                <th>{{ $t('specimens.available_quantity') }}</th>
                <th>{{ $t('specimens.consume_pool_source') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in dataCtx.specimen.specimensPool" :key="item.id">
                <td>{{ item.label || item.barcode || item.id }}</td>
                <td>{{ item.type }}</td>
                <td>
                  <os-specimen-measure :model-value="item.availableQty" :context="{specimen: item}"
                    entity="specimen" :read-only="true" />
                </td>
                <td>
                  <input type="checkbox" v-model="item.closeAfterPooledCreation"
                    :aria-label="$t('specimens.consume_pool_source') + ': ' + (item.label || item.id)" />
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
      <os-form ref="spmnForm" :schema="ctx.addEditFs" :data="dataCtx"
        :reference-prefix="'specimen-edit-' + cpr.id + '-' + dataCtx.specimen.id" @input="handleInput($event)">
        <template #static-fields>
          <os-field-references :cp-id="dataCtx.cp.id" target="specimen" :cpr-id="cpr.id" :visit-id="visit.id"
            :specimen-id="dataCtx.specimen.id" :parent-id="dataCtx.specimen.parentId"
            :target-prefix="'specimen-edit-' + cpr.id + '-' + dataCtx.specimen.id" />
        </template>
        <div>
          <os-button primary :label="$t(isPooling ? 'specimens.create_pooled_specimen' :
            (!dataCtx.specimen.id ? 'common.buttons.add' : 'common.buttons.update'))" :disabled="ctx.saving"
            @click="saveOrUpdate()" />

          <os-button text :label="$t('common.buttons.cancel')" @click="cancel" />
        </div>
      </os-form>
    </os-page-body>
  </os-page>
</template>

<script>

import cpSvc        from '@/biospecimen/services/CollectionProtocol.js';
import specimenSvc  from '@/biospecimen/services/Specimen.js';

import authSvc    from '@/common/services/Authorization.js';
import alertsSvc  from '@/common/services/Alerts.js';
import formUtil   from '@/common/services/FormUtil.js';
import routerSvc  from '@/common/services/Router.js';
import util       from '@/common/services/Util.js';
import FieldReferences from '@/biospecimen/components/FieldReferences.vue';

export default {
  components: {'os-field-references': FieldReferences},
  props: ['cpr', 'visit', 'specimen'],

  inject: ['cpViewCtx'],

  data() {
    const specimen = this.specimen ? util.clone(this.specimen) : {};
    if (!specimen.id) {
      Object.assign(specimen, {
        lineage: specimen.lineage || 'New',
        status: specimen.status || 'Collected'
      });
    }

    if (specimen.extensionDetail) {
      //
      // attrs map created might be suitable for display mode
      // therefore clean it up. create the map suitable for data entry
      //
      delete specimen.extensionDetail.attrsMap;
    }

    formUtil.createCustomFieldsMap(specimen);

    const cp = this.cpViewCtx.getCp();
    const userRole = this.cpViewCtx.getRole();
    return {
      dataCtx: {
        specimen,

        objName: 'specimen',

        objCustomFields: 'specimen.extensionDetail.attrsMap',

        cp,

        item: {cpr: this.cpr, visit: this.visit, specimen, userRole},

        userRole
      },

      ctx: {
        addEditFs: {rows: []},
        saving: false,
        quantityBaseline: {
          initialQty: specimen.initialQty,
          availableQty: specimen.availableQty
        },


        header: {}
      }
    };
  },

  created() {
    const cpCtx = this.cpViewCtx;
    const promises = [
      cpCtx.getSpecimenDict(true, this.dataCtx.specimen.lineage),
      cpCtx.getSpecimenAddEditLayout(this.dataCtx.specimen.lineage)
    ];
    Promise.all(promises).then(
      ([fields, layout]) => {
        const formSchema = this.ctx.addEditFs = formUtil.getFormSchema(fields, layout);
        if (!this.specimen.id || this.specimen.id <= 0) {
          formUtil.setDefaultValues(formSchema, this.dataCtx);
          this.dataCtx.specimen.availableQty = this.dataCtx.specimen.initialQty;
        }
      }
    );

    cpSvc.getWorkflowProperty(this.dataCtx.cp.id, 'common', 'specimenHeader').then(
      header => {
        if (header) {
          this.ctx.header = header;
        }
      }
    );
  },

  computed: {
    isPooling: function() {
      return !this.specimen.id && Array.isArray(this.specimen.specimensPool);
    },

    specimenLabel: function() {
      const {label, barcode, specimenClass, type} = this.specimen;
      let result = label || '';
      if (barcode) {
        result += result ? ' (' + barcode + ') ' : barcode;
      }

      if (!result && specimenClass) {
        result = type + ' (' + specimenClass + ')';
      }

      return result || 'Specimen';
    },

    bcrumb: function() {
      const cp = this.dataCtx.cp;
      if (!cp) {
        return [];
      }

      const {cpId, cprId, visitId, eventId} = this.specimen;
      const bcrumb = [
        {
          url: routerSvc.getUrl('ParticipantsList', {cpId, cprId: -1}),
          label: cp.shortTitle
        }
      ];

      if (!cp.specimenCentric) {
        bcrumb.push(
          {
            url: routerSvc.getUrl('ParticipantsListItemDetail.Overview', {cpId, cprId}),
            label: this.cpr.ppid
          }
        );

        bcrumb.push(
          {
            url: routerSvc.getUrl('ParticipantsListItemVisitDetail.Overview', {cpId, cprId, visitId, eventId}),
            label: cpSvc.getEventDescription(this.visit)
          }
        );
      }

      return bcrumb;
    },

    hasPhiAccess: function() {
      return authSvc.isAllowed({resources: ['ParticipantPhi'], cp: this.dataCtx.cp.shortTitle, operations: ['Read']});
    }
  },

  methods: {
    handleInput: function({field, value}) {
      if (field.name != 'specimen.initialQty') {
        return;
      }

      const specimen = this.dataCtx.specimen;
      if (!specimen.id || specimen.id <= 0) {
        specimen.availableQty = value;
      } else if (value == null || value === '') {
        specimen.availableQty = null;
      } else {
        const {initialQty, availableQty} = this.ctx.quantityBaseline;
        if (initialQty != null && availableQty != null) {
          specimen.availableQty = +availableQty + (+value - +initialQty);
        } else {
          specimen.availableQty = value;
        }
      }

    },
    saveOrUpdate: async function() {
      if (this.ctx.saving || !this.$refs.spmnForm.validate()) {
        return;
      }

      if (this.isPooling && !this._validatePool()) {
        return;
      }

      const toSave = this._getSavePayload();
      this.ctx.saving = true;
      try {
        const saved = this.isPooling ? await specimenSvc.createPooled(toSave) : await specimenSvc.saveOrUpdate(toSave);
        this._navToOverview(saved);
      } finally {
        this.ctx.saving = false;
      }
    },

    _validatePool: function() {
      const items = this.dataCtx.specimen.specimensPool || [];
      if (new Set(items.map(item => item.id)).size < 2) {
        alertsSvc.error({code: 'common.specimen_actions.select_for_pooling'});
        return false;
      }

      if (items.some(item => item.cpId != this.dataCtx.cp.id || item.status != 'Collected' || item.activityStatus != 'Active')) {
        alertsSvc.error({code: 'specimens.invalid_pool_sources'});
        return false;
      }

      return true;
    },

    cancel: function() {
      this._navToOverview(this.dataCtx.specimen);
    },

    _navToOverview: function({cpId, cprId, visitId, id}) {
      if (id > 0) {
        routerSvc.goto('ParticipantsListItemSpecimenDetail.Overview', {cpId, cprId, visitId, specimenId: id});
      } else {
        routerSvc.back();
      }
    },

    _getSavePayload: function() {
      const toSave = util.clone(this.dataCtx.specimen);
      const cp = this.dataCtx.cp || {};
      if (this.isPooling) {
        toSave.lineage = 'New';
        toSave.status = 'Collected';
        delete toSave.parentId;
        delete toSave.parentLabel;
        toSave.specimensPool = toSave.specimensPool.map(item => ({
          id: item.id,
          closeAfterPooledCreation: item.closeAfterPooledCreation !== false
        }));
      }
      if (!toSave.id && cp.specimenCentric) {
        if (!toSave.cprId || toSave.cprId <= 0) {
          delete toSave.cprId;
          delete toSave.ppid;
        }

        if (!toSave.visitId || toSave.visitId <= 0) {
          delete toSave.visitId;
          delete toSave.visitName;
          delete toSave.visitStatus;
          delete toSave.visitDate;
          delete toSave.eventId;
          delete toSave.eventLabel;
        }
      }

      return toSave;
    }
  }
}
</script>

<style scoped>
.pool-sources {
  margin-bottom: 1.5rem;
}

.pool-table {
  overflow-x: auto;
}

.pool-table th,
.pool-table td {
  padding: 0.5rem 1rem;
}
</style>
