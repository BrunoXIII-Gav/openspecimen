<template>
  <os-page>
    <os-page-head>
      <template #breadcrumb>
        <os-breadcrumb :items="ctx.bcrumb" />
      </template>

      <span>
        <h3 v-if="!ctx.pv.id">
          <span v-t="'pvs.create'"></span>
        </h3>
        <h3 v-else>
          <span v-t="{path: 'common.update', args: ctx.pv}"></span>
        </h3>
      </span>
    </os-page-head>

    <os-page-body>
      <os-form ref="pvForm" :schema="formSchema" :data="ctx" @input="handleInput($event)">
        <div>
          <os-button primary
            :label="$t(!ctx.pv.id ? 'common.buttons.create' : 'common.buttons.update')"
            @click="saveOrUpdate" />
          <os-button text :label="$t('common.buttons.cancel')" @click="cancel" />
        </div>
      </os-form>
    </os-page-body>
  </os-page>
</template>

<script>
import { reactive } from 'vue';

import alertSvc   from '@/common/services/Alerts.js';
import i18n       from '@/common/services/I18n.js';
import routerSvc  from '@/common/services/Router.js';
import pvAdminSvc from '@/administrative/services/PermissibleValueAdmin.js';

export default {
  props: ['pvId', 'attribute'],

  setup(props) {
    const ctx = reactive({
      bcrumb: [
        {url: routerSvc.getUrl('PvsList', {attribute: '-'}), label: i18n.msg('pvs.list')}
      ],
      pv: {activityStatus: 'Active', attribute: props.attribute || undefined},
      specimenClasses: []
    });

    pvAdminSvc.getPvs({
      attribute: 'specimen_type',
      includeOnlyRootValue: true,
      activityStatus: 'all',
      maxResults: 1000
    }).then(
      pvs => {
        ctx.specimenClasses.splice(
          0,
          ctx.specimenClasses.length,
          {name: 'No Parent (Root Class)', value: null},
          ...pvs.map(pv => ({name: pv.value, value: pv.value}))
        );
      }
    );

    if (props.pvId && +props.pvId > 0) {
      pvAdminSvc.getPv(+props.pvId, {includeProps: true}).then(pv => {
        ctx.pv = pv;
      });
    }

    const knownAttributes = pvAdminSvc.getKnownAttributes().map(a => ({name: a, value: a}));

    const formSchema = {
      rows: [
        {
          fields: [
            {
              name: 'pv.attribute',
              labelCode: 'pvs.attribute',
              type: 'dropdown',
              listSource: {
                options: knownAttributes,
                displayProp: 'name',
                selectProp: 'value'
              },
              validations: {required: {messageCode: 'pvs.attribute_req'}}
            }
          ]
        },
        {
          fields: [
            {
              name: 'pv.value',
              labelCode: 'pvs.value',
              type: 'text',
              validations: {required: {messageCode: 'pvs.value_req'}}
            }
          ]
        },
        {
          fields: [
            {
              name: 'pv.parentValue',
              label: 'Parent Specimen Class',
              type: 'dropdown',
              showWhen: "pv.attribute == 'specimen_type'",
              listSource: {
                options: ctx.specimenClasses,
                displayProp: 'name',
                selectProp: 'value'
              }
            }
          ]
        },
        {
          fields: [
            {
              name: 'pv.labelCode',
              labelCode: 'pvs.label_code',
              type: 'text'
            }
          ]
        },
        {
          fields: [
            {
              name: 'pv.conceptCode',
              labelCode: 'pvs.concept_code',
              type: 'text'
            }
          ]
        },
        {
          fields: [
            {
              name: 'pv.activityStatus',
              labelCode: 'pvs.status',
              type: 'dropdown',
              listSource: {
                options: [
                  {name: i18n.msg('common.active'), value: 'Active'},
                  {name: i18n.msg('common.disabled'), value: 'Disabled'}
                ],
                displayProp: 'name',
                selectProp: 'value'
              }
            }
          ]
        }
      ]
    };

    return {ctx, formSchema};
  },

  methods: {
    handleInput(event) {
      Object.assign(this.ctx, event.data);
      if (this.ctx.pv.attribute != 'specimen_type') {
        this.ctx.pv.parentValue = null;
      }
    },

    async saveOrUpdate() {
      if (!this.$refs.pvForm.validate()) {
        return;
      }

      const saved = await pvAdminSvc.saveOrUpdate(this.ctx.pv);
      alertSvc.success({code: 'pvs.saved', args: saved});
      if (this.attribute) {
        routerSvc.goto('PvsListItemValues', {attribute: this.attribute});
      } else {
        routerSvc.goto('PvsList', {attribute: '-'});
      }
    },

    cancel() {
      routerSvc.back();
    }
  }
}
</script>
