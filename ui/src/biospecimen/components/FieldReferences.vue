<template>
  <Teleport v-for="(entry, index) in inlineFields" :key="index + ':' + anchorVersion" :to="'#' + entry.anchorId">
    <div class="inline-reference">
      <small>{{ titleFor(entry.field) }}</small>
      <strong>{{ entry.field.caption }}</strong>
      <div v-if="Array.isArray(entry.field.value)">
        <div v-for="(value, row) in valueLines(entry.field)" :key="row">{{ value }}</div>
      </div>
      <span v-else>{{ displayValue(entry.field, entry.field.value) }}</span>
    </div>
  </Teleport>
  <section class="os-field-references" v-if="sectionFields.length || loadError || page > 1 || hasMore">
    <os-message type="error" v-if="loadError">{{ $t('cps.reference_load_error') }}</os-message>
    <div class="source-group" v-for="source in sourceGroups" :key="source.key">
      <h4>{{ source.title }}</h4>
      <div v-for="group in source.groups" :key="group.name">
        <h5 v-if="group.name">{{ group.name }}</h5>
        <dl>
          <template v-for="(field, index) in group.fields" :key="index">
            <dt>{{ field.caption }}</dt>
            <dd v-if="Array.isArray(field.value)">
              <div v-for="(value, row) in valueLines(field)" :key="row">{{ value }}</div>
            </dd>
            <dd v-else>{{ displayValue(field, field.value) }}</dd>
          </template>
        </dl>
      </div>
    </div>
    <div class="pager" v-if="page > 1 || hasMore">
      <span>{{ $t('cps.reference_page', {page}) }}</span>
      <os-button :label="$t('common.buttons.previous')" :disabled="page == 1" @click="page--" />
      <os-button :label="$t('common.buttons.next')" :disabled="!hasMore" @click="page++" />
    </div>
  </section>
</template>

<script>
import http from '@/common/services/HttpClient.js';

export default {
  props: ['cpId', 'target', 'targetFormId', 'cprId', 'visitId', 'specimenId', 'parentId', 'targetPrefix'],

  data() {
    return {fields: [], requestNo: 0, page: 1, loadError: false, anchorVersion: 0};
  },

  computed: {
    inlineFields() {
      this.anchorVersion;
      return this.fields.map(field => ({field, anchorId: this.anchorFor(field)}))
        .filter(entry => !!entry.anchorId);
    },

    sectionFields() {
      const inline = new Set(this.inlineFields.map(entry => entry.field));
      return this.fields.filter(field => !inline.has(field));
    },

    sourceGroups() {
      const sources = new Map();
      this.sectionFields.forEach(field => {
        const key = field.sourceFormId ? `form:${field.sourceFormId}` : `level:${field.sourceLevel}`;
        if (!sources.has(key)) sources.set(key, {key, title: this.titleFor(field), groups: new Map()});
        const source = sources.get(key);
        const name = field.group || '';
        if (!source.groups.has(name)) source.groups.set(name, {name, fields: []});
        source.groups.get(name).fields.push(field);
      });
      return Array.from(sources.values()).map(source => ({...source, groups: Array.from(source.groups.values())}));
    },

    hasMore() {
      return this.fields.some(field => field.hasMore);
    }
  },

  created() {
    this.load();
  },

  mounted() {
    this.anchorObserver = new MutationObserver(mutations => {
      if (mutations.some(mutation => Array.from(mutation.addedNodes).some(node => node.nodeType === 1 &&
        (node.hasAttribute?.('data-ref-prefix') || node.querySelector?.('[data-ref-prefix]'))))) {
        this.anchorVersion++;
      }
    });
    this.anchorObserver.observe(document.body, {childList: true, subtree: true});
  },

  unmounted() {
    this.anchorObserver?.disconnect();
  },

  watch: {
    cpId: 'reload', target: 'reload', targetFormId: 'reload', cprId: 'reload',
    visitId: 'reload', specimenId: 'reload', parentId: 'reload', page: 'load'
  },

  methods: {
    titleFor(field) {
      if (field.sourceFormCaption) return field.sourceFormCaption;
      const level = ['participant', 'visit', 'specimen'].includes(field.sourceLevel) ? field.sourceLevel : 'specimen';
      return this.$t('cps.reference_origin_' + level);
    },

    anchorFor(field) {
      if (!this.targetPrefix || !field.afterField) return null;
      const markers = document.querySelectorAll('[data-ref-field]');
      for (const marker of markers) {
        if (marker.dataset.refPrefix !== this.targetPrefix) continue;
        const name = marker.dataset.refField;
        if (field.afterField === '__form_start__' && name === '__form_start__') return marker.id;
        if (name === field.afterField || name.endsWith('.' + field.afterField)) return marker.id;
      }
      return null;
    },

    valueLines(field) {
      return field.value.flatMap((value, index) => Array.isArray(value)
        ? value.map((nested, row) => (index + 1) + '.' + (row + 1) + ": " + this.displayValue(field, nested))
        : [(index + 1) + ": " + this.displayValue(field, value)]);
    },

    displayValue(field, value) {
      if (field.sourceField != 'participant.gender' || typeof value != 'string') return value;
      const gender = {female: 'female', male: 'male', undifferentiated: 'undifferentiated', unknown: 'unknown'}[value.toLowerCase()];
      return gender ? this.$t('pvs.gender.' + gender) : value;
    },

    reload() {
      if (this.page !== 1) this.page = 1;
      else this.load();
    },

    async load() {
      const requestNo = ++this.requestNo;
      this.fields = [];
      this.loadError = false;
      if (!this.cpId || !(this.cprId > 0 || this.visitId > 0 || this.specimenId > 0 || this.parentId > 0)) return;

      try {
        const fields = await http.get('field-references', {
          cpId: this.cpId, target: this.target, formId: this.targetFormId || undefined,
          cprId: this.cprId, visitId: this.visitId > 0 ? this.visitId : undefined,
          specimenId: this.specimenId > 0 ? this.specimenId : undefined,
          parentId: this.parentId > 0 ? this.parentId : undefined, page: this.page
        });
        if (requestNo === this.requestNo) this.fields = fields || [];
      } catch (error) {
        if (requestNo === this.requestNo) {
          this.fields = [];
          this.loadError = true;
        }
      }
    }
  }
};
</script>

<style scoped>
.os-field-references { margin: 1rem 0; }
.source-group { margin: .6rem 0; padding: .6rem .75rem; border-left: 3px solid #6c849e; background: #f6f8fa; }
.source-group + .source-group { margin-top: .75rem; }
.os-field-references h4 { margin: 0 0 .4rem; font-size: .75rem; font-weight: normal; color: #626b75; }
.os-field-references h5 { margin: .6rem 0 .35rem; }
.os-field-references dl { display: grid; grid-template-columns: 1fr; gap: .2rem; margin: 0; }
.os-field-references dt { font-weight: 600; }
.os-field-references dd { margin: 0; overflow-wrap: anywhere; }
.pager { display: flex; align-items: center; gap: .5rem; margin-top: 1rem; }
.inline-reference { margin: .6rem 0; padding: .6rem .75rem; border-left: 3px solid #6c849e; background: #f6f8fa; }
.inline-reference strong { display: block; margin-bottom: .2rem; }
.inline-reference small, .os-field-references small { display: block; color: #626b75; margin-bottom: .4rem; }
</style>
