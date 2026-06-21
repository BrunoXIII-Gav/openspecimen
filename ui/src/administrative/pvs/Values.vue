<template>
  <os-page>
    <os-page-head>
      <span>
        <h3>{{ attribute }}</h3>
      </span>

      <template #right>
        <os-list-size
          :list="ctx.pvs"
          :page-size="1000"
          :list-size="ctx.totalCount"
        />
      </template>
    </os-page-head>

    <os-page-body>
      <os-page-toolbar>
        <template #default>
          <span v-if="!ctx.selectedPvs || ctx.selectedPvs.length == 0">
            <os-button left-icon="plus" :label="$t('common.buttons.create')"
              @click="createPv" />
          </span>
          <span v-else>
            <os-button left-icon="trash" :label="$t('common.buttons.delete')" @click="deletePvs" />
          </span>
        </template>
      </os-page-toolbar>

      <os-list-view
        :context="ctx.ui"
        :data="ctx.pvs"
        :schema="valuesSchema"
        :allow-selection="true"
        :loading="ctx.loading"
        @selectedRows="onPvsSelection"
        @rowClicked="onPvRowClick"
        ref="listView"
      />

      <os-confirm-delete ref="deleteDialog">
        <template #message>
          <span v-t="'pvs.confirm_delete_selected'"></span>
        </template>
      </os-confirm-delete>
    </os-page-body>
  </os-page>
</template>

<script>
import valuesSchema from '@/administrative/schemas/pvs/values.js';

import alertSvc  from '@/common/services/Alerts.js';
import routerSvc from '@/common/services/Router.js';
import pvAdminSvc from '@/administrative/services/PermissibleValueAdmin.js';

export default {
  props: ['attribute'],

  data() {
    return {
      ctx: {
        ui: this.$ui,
        pvs: [],
        totalCount: 0,
        loading: true,
        selectedPvs: []
      },

      valuesSchema
    };
  },

  watch: {
    'attribute': function(newValue, oldValue) {
      if (newValue !== oldValue) {
        this.loadPvs();
      }
    }
  },

  mounted() {
    this.loadPvs();
  },

  methods: {
    async loadPvs() {
      this.ctx.loading = true;
      const opts = {attribute: this.attribute, activityStatus: 'all'};
      const [pvs, countResp] = await Promise.all([
        pvAdminSvc.getPvs({...opts, maxResults: 1000}),
        pvAdminSvc.getPvsCount(opts)
      ]);
      this.ctx.pvs = pvs.map(pv => ({pv}));
      this.ctx.totalCount = countResp.count;
      this.ctx.loading = false;
    },

    onPvsSelection(selection) {
      this.ctx.selectedPvs = (selection || []).map(row => row.rowObject.pv);
    },

    onPvRowClick(rowObject) {
      routerSvc.goto('PvAddEdit', {pvId: rowObject.pv.id}, {attribute: this.attribute});
    },

    createPv() {
      routerSvc.goto('PvAddEdit', {pvId: -1}, {attribute: this.attribute});
    },

    deletePvs() {
      const pvIds = this.ctx.selectedPvs.map(pv => pv.id);
      if (pvIds.length === 0) {
        return;
      }

      this.$refs.deleteDialog.open().then(async () => {
        let deleted = 0;
        for (const id of pvIds) {
          await pvAdminSvc.delete(id);
          deleted++;
        }
        alertSvc.success({code: 'pvs.deleted', args: {count: deleted}});
        await this.loadPvs();
        this.$emit('pvChanged');
      });
    }
  }
}
</script>
