<template>
  <os-page>
    <os-page-head>
      <template #breadcrumb>
        <os-breadcrumb :items="bcrumb" />
      </template>

      <span>
        <h3>
          <span v-show="!recordId" v-t="'common.add_record'">Add Record</span>
          <span v-show="!!recordId" v-t="'common.update_record'">Update Record</span>
          <span>: {{ctx.formDef.caption}}</span>
        </h3>
      </span>
    </os-page-head>

    <os-page-body>
      <os-message type="error" v-if="ctx.accessChecked && !ctx.formAllowed">{{ $t('roles.content.denied') }}</os-message>
      <os-addedit-form-record :entity="object" :form-def="ctx.formDef" :form-id="formId"
        :form-ctxt-id="formCtxtId" :record-id="recordId" :hide-panel="true"
        :show-draft="allowDraftRecords" :field-reference-context="api.getFieldReferenceContext && api.getFieldReferenceContext()"
        @saved="saved" @cancelled="saveCancelled" v-if="ctx.formAllowed" />
    </os-page-body>
  </os-page>
</template>

<script>

import alertsSvc  from '@/common/services/Alerts.js';
import contentSvc from '@/common/services/ProtocolContent.js';
import formSvc    from '@/forms/services/Form.js';

export default {
  props: ['object', 'formId', 'formCtxtId', 'recordId', 'api'],

  data() {
    return {
      ctx: {
        formDef: {},
        formAllowed: false,
        accessChecked: false
      },

      bcrumb: this.api.getBreadcrumb()
    };
  },

  async created() {
    this._setupForm();
  },

  computed: {
    allowDraftRecords: function() {
      return this.api && typeof this.api.isDraftDataEntryEnabled == 'function' && this.api.isDraftDataEntryEnabled();
    }
  },

  watch: {
    '$props.formId': function() {
      this._setupForm();
    }
  },

  methods: {
    saved: function(record) {
      alertsSvc.success({
        code: 'common.form_record_saved',
        args: {recordId: record.id, formCaption: this.ctx.formDef.caption}
      });

      this._navToOverview(record);
    },

    saveCancelled: function() {
      this._navToOverview({});
    },

    _setupForm: async function() {
      this.ctx.formAllowed = false;
      this.ctx.accessChecked = false;
      const access = await contentSvc.getAccess(this.object.cpId);
      this.ctx.formAllowed = contentSvc.allows(access, 'form:' + this.formId);
      this.ctx.accessChecked = true;
      if (!this.ctx.formAllowed) return;
      formSvc.getDefinition(this.formId).then(formDef => this.ctx.formDef = formDef);
    },

    _navToOverview: function({containerId: formId, id: recordId}) {
      this.api.gotoOverview(formId, recordId);
    }
  }
}
</script>
