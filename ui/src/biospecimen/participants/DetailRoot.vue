
<template>
  <os-message type="error" v-if="contextError">{{ $t('specimens.context_unavailable') }}</os-message>
  <router-view :cpr="cpr" :specimen-navigation="specimenNavigation" :key="key" v-if="loaded" />
</template>

<script>

import {provide, ref} from 'vue';

import formUtil from '@/common/services/FormUtil.js';
import cprSvc   from '@/biospecimen/services/Cpr.js';
import specimenSvc from '@/biospecimen/services/Specimen.js';
import {matchesSpecimenContext} from '@/biospecimen/services/specimen-context.mjs';
import navigationSvc from '@/biospecimen/services/SpecimenNavigation.js';

export default {
  props: ['cprId', 'op'],

  inject: ['cpViewCtx'],
  data() { return {loaded: false, contextError: false, specimenNavigation: null, requestId: 0}; },

  setup() {
    const cpr = ref({participant: {pmis: [], source: 'OpenSpecimen'}});
    provide('cpr', cpr);
    return { cpr };
  },

  created() {
    this._setupCpr();
  },

  watch: {
    key: async function(newVal, oldVal) {
      if (newVal == oldVal) {
        return;
      }

      this._setupCpr();
    }
  },

  computed: {
    key: function() {
      return this.cprId + '_' + (this.op || 'view') + '_' + (this.$route?.params?.specimenId || '');
    }
  },

  methods: {
    _setupCpr: async function() {
      this.loaded = false; this.contextError = false; this.specimenNavigation = null;
      const requestId = ++this.requestId;
      try {
      if (!this.cprId || this.cprId <= 0) {
        const cp = this.cpViewCtx.getCp();
        this.cpr = {
          id: null,
          cpId: cp.id,
          cpShortTitle: cp.shortTitle,
          ppid: null,
          hasConsented: true,
          participant: {pmis: [], source: 'OpenSpecimen'}
        };
        this.loaded = true; return;
      }

      const specimenId = Number(this.$route?.params?.specimenId);
      if (specimenId > 0) {
        const navigation = await specimenSvc.getAuthorizedContext(specimenId);
        if (requestId !== this.requestId) return;
        if (!matchesSpecimenContext(navigation, {cpId: this.cpViewCtx.getCp().id, cprId: this.cprId,
          visitId: this.$route.params.visitId, specimenId})) throw new Error('Invalid specimen context');
        this.specimenNavigation = navigation;
        if (!this.cpViewCtx.isReadParticipantAllowed()) {
          this.cpr = navigation.cpr;
          this.loaded = true; return;
        }
      }

      if (!this.cpViewCtx.isReadParticipantAllowed() && !(specimenId > 0)) {
        if (!this.cpViewCtx.isReadSpecimenAllowed()) throw new Error('No specimen navigation access');
        const name = this.$route.name || '';
        if (!(Number(this.$route.params?.visitId) > 0) && name && !/\.(Overview|Specimens)$/.test(name)) throw new Error('Participant content is restricted');
        const navigation = await navigationSvc.registration(this.cpViewCtx.getCp().id, this.cprId);
        if (requestId !== this.requestId) return;
        if (Number(navigation.cpr.id) !== Number(this.cprId) || Number(navigation.cpr.cpId) !== Number(this.cpViewCtx.getCp().id)) throw new Error('Invalid registration context');
        this.cpr = navigation.cpr; this.loaded = true; return;
      }

      const cpr = await cprSvc.getCpr(this.cprId);
      if (requestId !== this.requestId) return;
      this.cpr = cpr;
      formUtil.createCustomFieldsMap(this.cpr.participant, true);

      if (this.$osSvc.ecValidationSvc) {
        const cp = this.cpViewCtx.getCp();
        if (cp.visitLevelConsents) {
          this.cpr.hasConsented = true;
        } else {
          const {status} = await this.$osSvc.ecValidationSvc.getParticipantStatus(this.cprId);
          this.cpr.hasConsented = status;
        }
      } else {
        this.cpr.hasConsented = true;
      }
      this.loaded = true;
      } catch {
        if (requestId === this.requestId) this.contextError = true;
      }
    }
  }
}
</script>
