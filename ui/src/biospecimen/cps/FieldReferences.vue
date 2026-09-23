<template>
  <os-panel>
    <template #header>{{ $t('cps.field_references') }}</template>
    <os-message type="info">{{ $t('cps.field_references_help') }}</os-message>
    <label class="activation"><input type="checkbox" v-model="enabled" /> {{ $t('cps.reference_enabled') }}</label>
    <os-message type="info" v-if="!enabled">{{ $t('cps.reference_disabled_help') }}</os-message>

    <div class="reference-rule" v-for="(rule, index) in rules" :key="rule.id || index">
      <label>{{ $t('cps.reference_destination') }}
        <select v-model="rule.target" @change="resetRule(rule)">
          <option value="visit">{{ $t('cps.reference_visit') }}</option>
          <option value="specimen">{{ $t('cps.reference_specimen') }}</option>
        </select>
      </label>
      <label>{{ $t('cps.reference_destination_form') }}
        <select v-model="rule.targetFormId" @change="onTargetFormChanged(rule)">
          <option :value="null">{{ $t('cps.reference_main_fields') }}</option>
          <option v-for="form in formsFor(rule.target)" :key="form.formId" :value="form.formId">{{ form.caption }}</option>
        </select>
      </label>
      <label>{{ $t('cps.reference_source') }}
        <select v-model="rule.source" @change="resetSource(rule)">
          <option v-for="source in sourcesFor(rule.target)" :key="source.value" :value="source.value">{{ source.label }}</option>
        </select>
      </label>
      <label>{{ $t('cps.reference_source_form') }}
        <select v-model="rule.sourceFormId" @change="onSourceFormChanged(rule)">
          <option :value="null">{{ $t('cps.reference_main_fields') }}</option>
          <option v-for="form in formsFor(rule.source == 'parent' || rule.source == 'primary' ? 'specimen' : rule.source)"
            :key="form.formId" :value="form.formId">{{ form.caption }}</option>
        </select>
      </label>
      <label>{{ $t('cps.reference_field') }}
        <select v-model="rule.field">
          <option value=""></option>
          <option v-for="field in fieldsFor(rule)" :key="field.name" :value="field.name">
            {{ field.caption }} ({{ field.name }}){{ field.sensitive ? ' ⚠' : '' }}{{ field.visible === false ? ` — ${$t('cps.reference_field_hidden')}` : '' }}
          </option>
        </select>
      </label>
      <os-message class="hidden-field-warning" type="warn" v-if="isSourceFieldHidden(rule)">
        {{ $t('cps.reference_hidden_source_field') }}
      </os-message>
      <label>{{ $t('cps.reference_caption') }}
        <input type="text" v-model.trim="rule.caption" maxlength="120" />
      </label>
      <label>{{ $t('cps.reference_group') }}
        <input type="text" v-model.trim="rule.group" maxlength="120" />
      </label>
      <label>{{ $t('cps.reference_placement') }}
        <select v-model="rule.afterField">
          <option value="__form_start__">{{ $t('cps.reference_at_start') }}</option>
          <option value="">{{ $t('cps.reference_at_end') }}</option>
          <option v-for="field in targetFieldsFor(rule)" :key="field.name" :value="field.name">
            {{ $t('cps.reference_after_field', {field: field.caption}) }}
          </option>
        </select>
      </label>
      <label v-if="rule.sourceFormId">{{ $t('cps.reference_record_policy') }}
        <select v-model="rule.recordPolicy">
          <option value="latest">{{ $t('cps.reference_latest') }}</option>
          <option value="latestComplete">{{ $t('cps.reference_latest_complete') }}</option>
          <option value="all">{{ $t('cps.reference_all') }}</option>
        </select>
      </label>
      <os-button text :label="$t('common.buttons.remove')" @click="rules.splice(index, 1)"
        v-show-if-allowed="cpResources.updateOpts" />
      <div class="rule-order" v-show-if-allowed="cpResources.updateOpts">
        <os-button text :label="$t('cps.reference_move_up')" :disabled="index == 0" @click="moveRule(index, -1)" />
        <os-button text :label="$t('cps.reference_move_down')" :disabled="index == rules.length - 1" @click="moveRule(index, 1)" />
      </div>
    </div>

    <div class="actions">
      <os-button :label="$t('common.buttons.add')" @click="addRule" v-show-if-allowed="cpResources.updateOpts" />
      <os-button primary :label="$t('common.buttons.save')" @click="save" v-show-if-allowed="cpResources.updateOpts" />
    </div>
    <div class="preview" v-if="rules.length">
      <h4>{{ $t('cps.reference_preview') }}</h4>
      <div v-for="(rule, index) in rules" :key="index">
        <strong v-if="rule.group && (index == 0 || rules[index - 1].group != rule.group)">{{ rule.group }}</strong>
        <div>{{ rule.caption || $t('cps.reference_caption') }}: {{ $t('cps.reference_sample_value') }}</div>
      </div>
    </div>
  </os-panel>
</template>

<script>
import cpSvc from '@/biospecimen/services/CollectionProtocol.js';
import alertsSvc from '@/common/services/Alerts.js';
import http from '@/common/services/HttpClient.js';
import cpResources from './Resources.js';

export default {
  props: ['cp'],

  data() {
    return {rules: [], forms: [], catalog: {}, enabled: true, cpResources};
  },

  async created() {
    const [config, forms] = await Promise.all([
      cpSvc.getWorkflow(this.cp.id, 'fieldReferences'),
      cpSvc.getForms(this.cp.id, ['CommonParticipant', 'Participant', 'SpecimenCollectionGroup', 'Specimen'])
    ]);
    this.rules = (config?.rules || []).map(rule => ({...rule, targetFormId: rule.targetFormId || null,
      sourceFormId: rule.sourceFormId || null, recordPolicy: rule.recordPolicy || 'latest'}));
    this.enabled = config?.enabled !== false;
    this.forms = forms || [];
    await Promise.all(this.rules.flatMap(rule => [this.loadCatalog(rule), this.loadTargetCatalog(rule)]));
  },

  methods: {
    formsFor(level) {
      const entity = {participant: 'Participant', visit: 'SpecimenCollectionGroup', specimen: 'Specimen'}[level];
      return this.forms.filter(form => (form.entityType == entity ||
        (level == 'participant' && form.entityType == 'CommonParticipant')) && !form.sysForm);
    },

    sourcesFor(target) {
      const values = target == 'participant' ? [] : target == 'visit' ? ['participant'] :
        ['participant', 'visit', 'parent', 'primary'];
      return values.map(value => ({value, label: this.$t('cps.reference_' + value)}));
    },

    sourceLevel(source) {
      return source == 'parent' || source == 'primary' ? 'specimen' : source;
    },

    catalogKey(rule) {
      return this.sourceLevel(rule.source) + ':' + (rule.sourceFormId || 'main');
    },

    fieldsFor(rule) {
      return (this.catalog[this.catalogKey(rule)] || []).filter(field =>
        field.visible !== false || field.name == rule.field
      );
    },

    targetFieldsFor(rule) {
      return (this.catalog[rule.target + ':' + (rule.targetFormId || 'main')] || []).filter(
        field => field.visible !== false
      );
    },

    isSourceFieldHidden(rule) {
      return !rule.sourceFormId && (this.catalog[this.catalogKey(rule)] || []).some(field =>
        field.name == rule.field && field.visible === false
      );
    },

    addRule() {
      const rule = {target: 'visit', targetFormId: null, source: 'participant', sourceFormId: null,
        field: '', caption: '', group: '', afterField: '', recordPolicy: 'latest'};
      this.rules.push(rule);
      this.loadCatalog(rule);
      this.loadTargetCatalog(rule);
    },

    resetRule(rule) {
      rule.targetFormId = null;
      rule.afterField = '';
      rule.source = this.sourcesFor(rule.target)[0]?.value || '';
      this.resetSource(rule);
      this.loadTargetCatalog(rule);
    },

    async onTargetFormChanged(rule) {
      rule.afterField = '';
      await this.loadTargetCatalog(rule);
    },

    resetSource(rule) {
      rule.sourceFormId = null;
      rule.field = '';
      this.loadCatalog(rule);
    },

    async onSourceFormChanged(rule) {
      rule.field = '';
      await this.loadCatalog(rule);
    },

    async loadCatalog(rule) {
      const key = this.catalogKey(rule);
      if (this.catalog[key]) return;
      const fields = await http.get('field-references/catalog', {
        cpId: this.cp.id, level: this.sourceLevel(rule.source), formId: rule.sourceFormId || undefined
      });
      this.catalog[key] = fields || [];
    },

    async loadTargetCatalog(rule) {
      await this.loadCatalog({source: rule.target, sourceFormId: rule.targetFormId});
    },

    moveRule(index, direction) {
      const next = index + direction;
      if (next < 0 || next >= this.rules.length) return;
      const [rule] = this.rules.splice(index, 1);
      this.rules.splice(next, 0, rule);
    },

    async save() {
      if (this.rules.some(rule => !rule.source || !rule.field || !rule.caption ||
          !this.sourcesFor(rule.target).some(source => source.value == rule.source))) {
        alertsSvc.error({code: 'cps.reference_invalid_rule'});
        return;
      }
      const rules = this.rules.map(rule => ({...rule,
        targetFormName: this.forms.find(form => form.formId == rule.targetFormId)?.name || null,
        sourceFormName: this.forms.find(form => form.formId == rule.sourceFormId)?.name || null
      }));
      const saved = await cpSvc.saveWorkflow(this.cp.id, 'fieldReferences', {version: 1, enabled: this.enabled, rules});
      this.rules = saved.rules || rules;
      alertsSvc.success({code: 'cps.reference_saved'});
    }
  }
};
</script>

<style scoped>
.reference-rule { display: grid; grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr)); gap: .75rem; padding: 1rem; margin: 1rem 0; border: 1px solid #d9dee5; }
.reference-rule label { display: flex; flex-direction: column; gap: .3rem; }
.reference-rule input, .reference-rule select { min-height: 2.3rem; border: 1px solid #cbd5e1; border-radius: 4px; padding: .35rem; }
.hidden-field-warning { grid-column: 1 / -1; margin: 0; }
.actions { display: flex; gap: .75rem; }
.rule-order { display: flex; gap: .5rem; align-items: end; }
.preview { margin-top: 1.5rem; padding: 1rem; border: 1px solid #d9dee5; }
.activation { display: flex; gap: .4rem; align-items: center; margin: 1rem 0; }
</style>
