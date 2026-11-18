<template>
  <section class="content-permissions">
    <h4>{{ $t('roles.content.title') }}</h4>
    <p>{{ $t('roles.content.help') }}</p>
    <p class="dependency-notice" v-if="!dependencies.content">{{ $t('roles.content.requires_content_permission') }}</p>
    <os-message type="error" v-if="error">{{ $t('roles.content.load_error') }}</os-message>
    <p v-if="!modelValue.length">{{ $t('roles.content.legacy') }}</p>
    <fieldset v-for="(rule, index) in modelValue" :key="index" :disabled="readOnly || !dependencies.content">
      <label :for="'content-cp-' + index">{{ $t('roles.content.protocol') }}</label>
      <select :id="'content-cp-' + index" :value="rule.cpId" :disabled="readOnly || !dependencies.content" @change="changeProtocol(index, $event.target.value)">
        <option :value="-1">{{ $t('roles.content.all_protocols') }}</option>
        <option v-for="cp in protocols" :key="cp.id" :value="cp.id">{{ cp.shortTitle || cp.title }}</option>
        <option v-if="rule.cpId > 0 && !protocols.some(cp => cp.id == rule.cpId)" :value="rule.cpId">#{{ rule.cpId }}</option>
      </select>
      <div class="content-actions" v-if="!readOnly">
        <os-button text :label="$t('roles.content.select_all')" :disabled="!dependencies.content || !options[rule.cpId]" @click="selectAll(index)" />
        <os-button text :label="$t('roles.content.select_none')" :disabled="!dependencies.content" @click="clearAllowed(index)" />
        <os-button text :label="$t('common.buttons.remove')" :disabled="!dependencies.content" @click="remove(index)" />
      </div>
      <div class="content-options">
        <label v-for="option in options[rule.cpId] || []" :key="option.key" :class="{inactive: !canConfigureOption(option)}">
          <input type="checkbox" :checked="rule.allowed.includes(option.key)" :disabled="readOnly || !canConfigureOption(option)"
            @change="toggle(index, option.key, $event.target.checked)" />
          {{ option.labelCode ? $t(option.labelCode) : option.label }}
          <small v-if="option.formId">(#{{ option.formId }})</small>
        </label>
      </div>
      <p v-if="!rule.allowed.length">{{ $t('roles.content.none') }}</p>
      <p v-if="unknownKeys(rule).length">{{ $t('roles.content.unavailable') }} {{ unknownKeys(rule).join(', ') }}</p>
      <fieldset class="creation-permissions" :disabled="readOnly || !dependencies.creation">
      <legend>{{ $t('roles.content.creation_title') }}</legend>
      <p>{{ $t('roles.content.creation_help') }}</p>
      <p class="dependency-notice" v-if="!dependencies.creation">{{ $t('roles.content.requires_create_permission') }}</p>
      <p class="dependency-notice" v-else-if="!dependencies.lineages.Derived">{{ $t('roles.content.primary_create_only') }}</p>
      <div class="content-options">
        <label v-for="lineage in ['New', 'Derived', 'Aliquot']" :key="lineage" :class="{inactive: !dependencies.lineages[lineage]}">
          <input type="checkbox" :checked="!rule.createLineages || rule.createLineages.includes(lineage)"
            :disabled="readOnly || !dependencies.lineages[lineage]"
            @change="toggleCreation(index, lineage, $event.target.checked)" />
          {{ $t('roles.content.create_' + lineage) }}
        </label>
      </div>
      </fieldset>
    </fieldset>
    <os-button v-if="!readOnly" :label="$t('roles.content.add')" :disabled="!dependencies.content" @click="add" />
  </section>
</template>

<script>
import http from '@/common/services/HttpClient.js';
import cpSvc from '@/biospecimen/services/CollectionProtocol.js';
import {roleContentDependencies, canConfigureContentOption} from './role-content-dependencies.mjs';

export default {
  props: {modelValue: {type: Array, default: () => []}, acl: {type: Array, default: () => []}, readOnly: Boolean},
  emits: ['update:modelValue'],
  data() { return {protocols: [], options: {}, error: false}; },
  computed: {
    dependencies() { return roleContentDependencies(this.acl); }
  },
  async created() {
    try { this.protocols = await cpSvc.getCps({maxResults: 10000}); }
    catch { this.error = true; }
  },
  watch: {
    modelValue: {
      immediate: true, deep: true,
      async handler(rules) {
        for (const {cpId} of rules) {
          if (this.options[cpId]) continue;
          try { this.options[cpId] = await http.get('protocol-content/options', {cpId}); }
          catch { this.error = true; }
        }
      }
    }
  },
  methods: {
    canConfigureOption(option) { return canConfigureContentOption(this.acl, option); },
    update(rules) { if (!this.readOnly && this.dependencies.content) this.$emit('update:modelValue', rules); },
    setAllowed(index, allowed) { this.update(this.modelValue.map((rule, i) => i == index ? {...rule, allowed} : rule)); },
    changeProtocol(index, value) {
      this.update(this.modelValue.map((rule, i) => i == index ? {...rule, cpId: +value, allowed: []} : rule));
    },
    toggle(index, key, checked) {
      const option = (this.options[this.modelValue[index].cpId] || []).find(option => option.key === key);
      if (!option || !this.canConfigureOption(option)) return;
      const allowed = this.modelValue[index].allowed.filter(value => value != key);
      if (checked) allowed.push(key);
      this.setAllowed(index, allowed);
    },
    toggleCreation(index, lineage, checked) {
      if (this.readOnly || !this.dependencies.lineages[lineage]) return;
      const current = this.modelValue[index].createLineages || ['New', 'Derived', 'Aliquot'];
      const createLineages = current.filter(value => value != lineage);
      if (checked) createLineages.push(lineage);
      this.update(this.modelValue.map((rule, i) => i == index ? {...rule, createLineages} : rule));
    },
    selectAll(index) { this.setActiveOptions(index, true); },
    clearAllowed(index) { this.setActiveOptions(index, false); },
    setActiveOptions(index, checked) {
      const activeKeys = (this.options[this.modelValue[index].cpId] || []).filter(option => this.canConfigureOption(option)).map(option => option.key);
      const allowed = this.modelValue[index].allowed.filter(key => !activeKeys.includes(key));
      this.setAllowed(index, checked ? [...allowed, ...activeKeys] : allowed);
    },
    remove(index) { this.update(this.modelValue.filter((_, i) => i != index)); },
    add() { this.update([...this.modelValue, {cpId: -1, allowed: []}]); },
    unknownKeys(rule) { return rule.allowed.filter(key => this.options[rule.cpId] && !this.options[rule.cpId].some(option => option.key == key)); }
  }
};
</script>

<style scoped>
.content-permissions { margin: 1.5rem 0; }
fieldset { border: 1px solid #ddd; padding: 1rem; margin-bottom: 1rem; }
select { display: block; min-width: 20rem; max-width: 100%; margin: .5rem 0; }
.content-options { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: .75rem; margin: 1rem 0; }
.content-options label { display: flex; align-items: center; gap: .5rem; }
.content-actions { display: flex; flex-wrap: wrap; gap: .5rem; }
.creation-permissions { border: 0; padding: 0; margin: 1.5rem 0 0; }
.creation-permissions legend { font-weight: bold; font-size: .9rem; }
.inactive { opacity: .6; }
.dependency-notice { color: #666; }
</style>
