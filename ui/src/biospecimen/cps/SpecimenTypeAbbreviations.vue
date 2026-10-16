<template>
  <os-section class="specimen-type-abbreviations" :initial-collapsed="true">
    <template #title>
      <span v-t="'cps.specimen_type_abbreviations'" />
    </template>

    <template #content>
      <div class="abbreviations-content">
        <p v-t="'cps.specimen_type_abbreviations_help'" />
        <p v-t="'cps.intermediate_derivatives_help'" />
        <p v-if="loading" v-t="'common.loading'" />
        <div v-else-if="loadFailed">
          <p v-t="'cps.specimen_type_abbreviations_load_error'" />
          <os-button :label="$t('cps.specimen_type_abbreviations_retry')" @click="load" />
        </div>
        <div v-else>
          <div class="abbreviations-table">
            <table class="os-table">
              <thead>
                <tr>
                  <th scope="col" v-t="'pvs.attribute_names.specimen_type'" />
                  <th scope="col" v-t="'cps.global_abbreviation'" />
                  <th scope="col" v-t="'cps.protocol_abbreviation'" />
                  <th scope="col" v-t="'cps.intermediate_derivative'" />
                </tr>
              </thead>
              <tbody>
                <tr v-for="type in types" :key="type.id">
                  <th scope="row">{{ typeLabel(type) }}</th>
                  <td>{{ (type.props && type.props.abbreviation) || '—' }}</td>
                  <td>
                    <InputText v-if="editMode" v-model="draft[type.id]" :maxlength="255"
                      :disabled="saving" :placeholder="(type.props && type.props.abbreviation) || '—'"
                      :aria-label="$t('cps.abbreviation_for_type', {type: typeLabel(type)})" />
                    <span v-else>{{ abbreviations[type.id] || $t('cps.use_global_abbreviation') }}</span>
                  </td>
                  <td>
                    <input v-if="editMode" type="checkbox" v-model="draftIntermediateTypeIds"
                      :value="String(type.id)" :disabled="saving"
                      :aria-label="$t('cps.intermediate_derivative_for_type', {type: typeLabel(type)})" />
                    <span v-else>{{ $t(intermediateTypeIds.includes(String(type.id)) ? 'common.yes' : 'common.no') }}</span>
                  </td>
                </tr>
                <tr v-if="types.length == 0">
                  <td colspan="4" v-t="'common.lists.no_records'" />
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </template>
  </os-section>
</template>

<script>
import InputText from 'primevue/inputtext';

import cpSvc from '@/biospecimen/services/CollectionProtocol.js';
import pvAdminSvc from '@/administrative/services/PermissibleValueAdmin.js';

const workflowName = 'specimenTypeAbbreviations';

export default {
  components: {InputText},

  props: ['cp', 'editMode', 'saving'],

  emits: ['ready'],

  data() {
    return {
      types: [],
      config: {},
      abbreviations: {},
      draft: {},
      intermediateTypeIds: [],
      draftIntermediateTypeIds: [],
      loading: true,
      loadFailed: false
    };
  },

  created() {
    this.load();
  },

  methods: {
    async load() {
      this.loading = true;
      this.loadFailed = false;
      this.$emit('ready', false);
      try {
        const [workflow, types] = await Promise.all([
          cpSvc.loadWorkflows(this.cp.id, workflowName),
          this.loadTypes()
        ]);
        this.config = (workflow && workflow.data) || {};
        this.abbreviations = {...this.config.abbreviations};
        this.intermediateTypeIds = [...(this.config.intermediateTypeIds || [])];
        this.types = types.filter(type => type.parentId || type.parentValue);
      } catch (e) {
        this.loadFailed = true;
      } finally {
        this.loading = false;
        this.$emit('ready', !this.loadFailed);
      }
    },

    async loadTypes() {
      const types = [];
      const pageSize = 1000;
      let page;
      do {
        page = await pvAdminSvc.getPvs({
          attribute: 'specimen_type', includeParentValue: true, includeProps: true,
          activityStatus: 'all', startAt: types.length, maxResults: pageSize
        });
        types.push(...page);
      } while (page.length == pageSize);
      return types;
    },

    typeLabel(type) {
      return type.labelCode && this.$te(type.labelCode) ? this.$t(type.labelCode) : type.value;
    },

    edit() {
      this.draft = {...this.abbreviations};
      this.draftIntermediateTypeIds = [...this.intermediateTypeIds];
    },

    async save() {
      const abbreviations = {};
      for (const type of this.types) {
        const abbreviation = (this.draft[type.id] || '').trim();
        if (abbreviation) {
          abbreviations[type.id] = abbreviation;
        }
      }

      const intermediateTypeIds = this.types
        .filter(type => this.draftIntermediateTypeIds.includes(String(type.id)))
        .map(type => String(type.id));
      const sameAbbreviations = Object.keys(abbreviations).length == Object.keys(this.abbreviations).length &&
        Object.entries(abbreviations).every(([id, value]) => this.abbreviations[id] === value);
      const sameIntermediateTypes = intermediateTypeIds.length == this.intermediateTypeIds.length &&
        intermediateTypeIds.every(id => this.intermediateTypeIds.includes(id));
      if (sameAbbreviations && sameIntermediateTypes) {
        return;
      }

      const config = await cpSvc.saveWorkflow(this.cp.id, workflowName,
        {...this.config, version: 1, abbreviations, intermediateTypeIds});
      this.config = config;
      this.abbreviations = {...config.abbreviations};
      this.intermediateTypeIds = [...(config.intermediateTypeIds || [])];
    }
  }
};
</script>

<style scoped>
.specimen-type-abbreviations :deep(.title-wrapper .title) {
  flex: 1;
}

.abbreviations-content {
  white-space: normal;
}

.abbreviations-table {
  overflow-x: auto;
}

.abbreviations-table table {
  width: 100%;
  border-collapse: collapse;
}

.abbreviations-table th,
.abbreviations-table td {
  padding: 0.65rem 1rem;
  border-bottom: 1px solid #dee2e6;
  text-align: left;
  vertical-align: middle;
}

.abbreviations-table thead {
  background: #f8f9fa;
}

.abbreviations-table tbody th {
  font-weight: normal;
}

.abbreviations-table :deep(.p-inputtext) {
  width: 100%;
  min-width: 8rem;
  max-width: 16rem;
}

</style>
