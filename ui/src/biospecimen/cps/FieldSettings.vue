<template>
  <os-panel>
    <template #header>
      <span class="title" v-t="'cps.field_settings'">Field Settings</span>
    </template>

    <template #default>
      <os-message type="info">
        <span v-t="'cps.field_settings_help'">
          Configure which fixed fields are visible, required, and how they are ordered for this collection protocol.
        </span>
      </os-message>

      <os-message type="warn">
        <span v-t="'cps.field_settings_required_locked_help'">
          Fields required by the system stay visible and required here to avoid breaking participant, visit, and specimen workflows.
        </span>
      </os-message>

      <div class="os-field-settings-tabs">
        <button v-for="module in modulesWithFields" :key="module.name"
          class="os-field-settings-tab" :class="{active: activeModule == module.name}"
          @click="activeModule = module.name">
          {{ $t(module.title) }}
        </button>
      </div>

      <div v-if="activeFields.length > 0" class="os-field-settings-table">
        <div class="header row">
          <div class="col order"></div>
          <div class="col visible" v-t="'cps.visible'">Visible</div>
          <div class="col required" v-t="'cps.required'">Required</div>
          <div class="col field" v-t="'cps.field'">Field</div>
          <div class="col name" v-t="'cps.technical_name'">Technical Name</div>
        </div>

        <div class="body">
          <div class="row" v-for="(item, index) in activeFields" :key="item.name" :class="{muted: !item.visible}">
            <div class="col order">
              <button type="button" class="order-btn" @click="moveField(index, -1)" :disabled="index === 0">
                <os-icon name="arrow-up" />
              </button>
              <button type="button" class="order-btn" @click="moveField(index, 1)" :disabled="index === activeFields.length - 1">
                <os-icon name="arrow-down" />
              </button>
            </div>
            <div class="col visible">
              <input type="checkbox" v-model="item.visible" :disabled="!item.canHide" @change="markModified">
            </div>
            <div class="col required">
              <input type="checkbox" v-model="item.required" :disabled="!item.canToggleRequired" @change="markModified">
              <span class="locked" v-if="item.requiredBySystem" v-t="'cps.locked'">Locked</span>
            </div>
            <div class="col field">
              <strong>{{ item.caption }}</strong>
              <div class="meta">{{ item.type }}</div>
            </div>
            <div class="col name">
              <code>{{ item.name }}</code>
            </div>
          </div>
        </div>
      </div>

      <os-message type="info" v-else>
        <span v-t="'cps.no_field_settings'">No fixed fields available for configuration.</span>
      </os-message>

      <div class="os-field-settings-actions">
        <os-button primary :label="$t('common.buttons.save')" @click="save"
          :disabled="!modified" v-show-if-allowed="cpResources.updateOpts" />
        <os-button text :label="$t('common.buttons.cancel')" @click="reset" :disabled="!modified" />
      </div>
    </template>
  </os-panel>
</template>

<script>

import alertsSvc from '@/common/services/Alerts.js';
import i18n      from '@/common/services/I18n.js';
import util      from '@/common/services/Util.js';

import cpSvc from '@/biospecimen/services/CollectionProtocol.js';
import cprSchema from '@/biospecimen/schemas/participants/cpr.js';
import visitSchema from '@/biospecimen/schemas/visits/visit.js';
import specimenSchema from '@/biospecimen/schemas/specimens/specimen.js';

import cpResources from './Resources.js';

const EDITOR_META_KEY = 'osFieldsEditor';
const MODULES = [
  {name: 'participant', title: 'participant.title', prefixes: ['cpr.']},
  {name: 'visit',       title: 'visits.title',      prefixes: ['visit.']},
  {name: 'specimen',    title: 'specimen.title',    prefixes: ['specimen.']}
];

export default {
  props: ['cp'],

  emits: ['cp-saved'],

  data() {
    return {
      activeModule: null,
      cpResources,
      cpWorkflowData: {},
      systemWorkflowData: {},
      modules: [],
      pristineModules: [],
      modified: false
    }
  },

  computed: {
    modulesWithFields: function() {
      return this.modules.filter(module => module.fields.length > 0);
    },

    activeFields: {
      get: function() {
        const module = this.modules.find(module => module.name == this.activeModule);
        return (module && module.fields) || [];
      },

      set: function(fields) {
        const module = this.modules.find(module => module.name == this.activeModule);
        if (module) {
          module.fields = fields;
        }
      }
    }
  },

  created() {
    this._loadSettings();
  },

  methods: {
    markModified: function() {
      this.modified = true;
    },

    moveField: function(index, delta) {
      const fields = [...this.activeFields];
      const targetIndex = index + delta;
      if (targetIndex < 0 || targetIndex >= fields.length) {
        return;
      }

      const [field] = fields.splice(index, 1);
      fields.splice(targetIndex, 0, field);
      this.activeFields = fields;
      this.markModified();
    },

    reset: function() {
      this.modules = util.clone(this.pristineModules);
      this.modified = false;
      if (!this.modules.some(module => module.name == this.activeModule && module.fields.length > 0)) {
        this.activeModule = (this.modulesWithFields[0] && this.modulesWithFields[0].name) || null;
      }
    },

    save: function() {
      const workflow = util.clone(this.cpWorkflowData || {});
      workflow.fields = this._buildVisibleFields().concat(this._getUnmanagedCpFields());
      workflow[EDITOR_META_KEY] = {version: 1};

      cpSvc.saveWorkflow(this.cp.id, 'dictionary', workflow).then(
        () => {
          this.cpWorkflowData = util.clone(workflow);
          this.modules = this._buildModules();
          this.pristineModules = util.clone(this.modules);
          this.modified = false;
          this.activeModule = (this.modulesWithFields[0] && this.modulesWithFields[0].name) || null;
          this.$emit('cp-saved', {...this.cp, draftMode: true});
          alertsSvc.success({code: 'cps.field_settings_saved'});
        }
      );
    },

    _loadSettings: async function() {
      this.systemWorkflowData = util.clone(await cpSvc.getWorkflow(-1, 'dictionary') || {});
      if (!this.systemWorkflowData.fields || this.systemWorkflowData.fields.length == 0) {
        this.systemWorkflowData = this._getDefaultWorkflowData();
      }

      let cpWorkflow = null;
      try {
        cpWorkflow = await cpSvc.loadWorkflows(this.cp.id, 'dictionary');
      } catch (error) {
        cpWorkflow = null;
      }

      this.cpWorkflowData = util.clone((cpWorkflow && cpWorkflow.data) || {});
      this.modules = this._buildModules();
      this.pristineModules = util.clone(this.modules);
      this.activeModule = (this.modulesWithFields[0] && this.modulesWithFields[0].name) || null;
    },

    _buildModules: function() {
      const orderedManagedFields = this._getOrderedManagedFields();
      const currentFields = this._getCurrentFieldsByName();
      return MODULES.map(
        (module) => ({
          name: module.name,
          title: module.title,
          fields: orderedManagedFields
            .filter(field => this._belongsToModule(field, module))
            .map(field => this._toEditableField(field, currentFields[field.name]))
        })
      );
    },

    _getDefaultWorkflowData: function() {
      const fields = [];
      if (!this.cp?.specimenCentric) {
        fields.push(...this._schemaFieldsToWorkflow(cprSchema.fields));
        fields.push(...this._schemaFieldsToWorkflow(visitSchema.fields));
      }

      fields.push(...this._schemaFieldsToWorkflow(specimenSchema.fields));
      return {fields};
    },

    _schemaFieldsToWorkflow: function(fields) {
      return (fields || [])
        .filter(field => this._isManagedField(field))
        .map(
          (field) => {
            const captionCode = field.labelCode || field.captionCode || field.inlineLabelCode;
            const caption = field.caption || (captionCode ? i18n.msg(captionCode) : field.name);
            return {
              ...util.clone(field),
              caption,
              type: field.type || 'text',
              optional: !(field.validations && field.validations.required)
            };
          }
        );
    },

    _getOrderedManagedFields: function() {
      const sysFields = (this.systemWorkflowData.fields || []).filter(field => this._isManagedField(field));
      const cpFields  = (this.cpWorkflowData.fields || []).filter(field => this._isManagedField(field));
      const byName = {};
      const order = [];
      const authoritative = this._isAuthoritativeOverride(this.cpWorkflowData);

      const addField = (field) => {
        if (!byName[field.name]) {
          byName[field.name] = field;
          order.push(field.name);
        }
      };

      if (authoritative) {
        cpFields.forEach(addField);
      } else {
        sysFields.forEach(addField);
        cpFields.forEach(addField);
      }

      sysFields.forEach(addField);
      cpFields.forEach(addField);
      return order.map(name => byName[name]);
    },

    _getCurrentFieldsByName: function() {
      const result = {};
      const authoritative = this._isAuthoritativeOverride(this.cpWorkflowData);
      const sysFields = (this.systemWorkflowData.fields || []).filter(field => this._isManagedField(field));
      const cpFields  = (this.cpWorkflowData.fields || []).filter(field => this._isManagedField(field));

      sysFields.forEach(
        (field) => result[field.name] = {field: util.clone(field), visible: !authoritative}
      );

      cpFields.forEach(
        (field) => result[field.name] = {field: util.clone(field), visible: true}
      );

      return result;
    },

    _toEditableField: function(field, current) {
      const sysField = (this.systemWorkflowData.fields || []).find(sysField => sysField.name == field.name);
      const currentField = current ? current.field : field;
      const requiredBySystem = !!(sysField && sysField.optional === false);

      return {
        name: field.name,
        caption: currentField.caption || field.caption || field.name,
        type: currentField.type || field.type,
        visible: requiredBySystem || !current || current.visible !== false,
        required: requiredBySystem || currentField.optional === false,
        requiredBySystem,
        canHide: !requiredBySystem,
        canToggleRequired: !requiredBySystem,
        original: util.clone(currentField)
      };
    },

    _buildVisibleFields: function() {
      const visibleFields = [];
      this.modules.forEach(
        (module) => module.fields.forEach(
          (field) => {
            if (!field.visible) {
              return;
            }

            const wfField = util.clone(field.original);
            wfField.caption = field.caption;
            wfField.optional = !field.required;
            visibleFields.push(wfField);
          }
        )
      );

      return visibleFields;
    },

    _getUnmanagedCpFields: function() {
      return (this.cpWorkflowData.fields || []).filter(field => !this._isManagedField(field));
    },

    _belongsToModule: function(field, module) {
      return module.prefixes.some(prefix => field.name.indexOf(prefix) == 0);
    },

    _isAuthoritativeOverride: function(data) {
      return !!(data && data[EDITOR_META_KEY] && data[EDITOR_META_KEY].version == 1);
    },

    _isManagedField: function(field) {
      return !!(field && field.name && field.name.indexOf('.extensionDetail.attrsMap') == -1 &&
        (field.name.indexOf('cpr.') == 0 || field.name.indexOf('visit.') == 0 || field.name.indexOf('specimen.') == 0));
    }
  }
}
</script>

<style scoped>
.os-field-settings-tabs {
  display: flex;
  gap: 0.5rem;
  margin: 1rem 0;
}

.os-field-settings-tab {
  padding: 0.5rem 0.75rem;
  border: 1px solid #d9d9d9;
  background: #fff;
  border-radius: 4px;
  cursor: pointer;
}

.os-field-settings-tab.active {
  background: #f2f7ff;
  border-color: #7aa7e3;
}

.os-field-settings-table {
  margin-top: 1rem;
}

.os-field-settings-table .row {
  display: grid;
  grid-template-columns: 72px 90px 140px 1fr 320px;
  gap: 0.75rem;
  align-items: center;
  padding: 0.625rem 0.75rem;
  border-bottom: 1px solid #eee;
}

.os-field-settings-table .row.header {
  font-weight: 600;
  border-top: 1px solid #eee;
  background: #fafafa;
}

.os-field-settings-table .row.muted {
  opacity: 0.65;
}

.os-field-settings-table .body {
  border-top: 1px solid #eee;
}

.os-field-settings-table .order {
  display: flex;
  gap: 0.25rem;
  align-items: center;
}

.order-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
}

.order-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.os-field-settings-table .meta {
  color: #777;
  font-size: 0.85rem;
}

.locked {
  margin-left: 0.5rem;
  color: #777;
  font-size: 0.85rem;
}

.os-field-settings-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  margin-top: 1rem;
}
</style>
