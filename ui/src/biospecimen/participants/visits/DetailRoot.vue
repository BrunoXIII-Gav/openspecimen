
<template>
  <os-message type="error" v-if="contextError">{{ $t('specimens.context_unavailable') }}</os-message>
  <router-view :cpr="cpr" :visit="visit" :key="viewKey" v-if="viewKey && loaded" />
</template>

<script>

import {provide, ref} from 'vue';

import cpSvc     from '@/biospecimen/services/CollectionProtocol.js';
import visitSvc  from '@/biospecimen/services/Visit.js';

import formUtil  from '@/common/services/FormUtil.js';
import navigationSvc from '@/biospecimen/services/SpecimenNavigation.js';

export default {
  props: ['cpr', 'visitId', 'eventId', 'specimenNavigation'],
  inject: ['cpViewCtx'],
  data() { return {contextError: false, requestId: 0}; },

  setup(props) {
    const visit = ref({id: props.visitId, eventId: props.eventId});
    provide('visit', visit);

    const loaded = ref(false);
    return { visit, loaded };
  },

  created() {
    if (this.visitId > 0) {
      this._loadVisit(this.visitId);
    } else if (this.eventId > 0) {
      this._loadEvent(this.eventId);
    } else {
      this._loadBlank();
    }
  },

  computed: {
    viewKey: function() {
      if (this.visitId > 0) {
        return 'v-' + this.visitId;
      } else if (this.eventId > 0) {
        return 'e-' + this.cpr.cpId + '-' + this.eventId;
      }

      return 'new-' + this.cpr.cpId + '-' + (this.cpr.id || -1);
    }
  },

  watch: {
    visitId: function(newVal, oldVal) {
      if (newVal == oldVal || newVal == this.visit.id) {
        return;
      }

      this._loadVisit(newVal);
    },

    eventId: function(newVal, oldVal) {
      if (this.visitId > 0 || newVal == oldVal || newVal == this.visit.eventId) {
        return;
      }

      this._loadEvent(newVal);
    }
  },

  methods: {
    _loadVisit: async function(visitId) {
      const requestId = ++this.requestId;
      this.loaded = false; this.contextError = false;
      try {
      const specimenId = Number(this.$route?.params?.specimenId);
      if (specimenId > 0 && !this.cpViewCtx.isReadVisitAllowed(this.cpr)) {
        const context = this.specimenNavigation;
        if (!context || Number(context.specimenId) !== specimenId || Number(context.visit.id) !== Number(visitId) ||
          Number(context.cpr.id) !== Number(this.cpr.id)) throw new Error('Invalid specimen context');
        this.visit = context.visit;
        this.loaded = true;
        return;
      }
      if (!this.cpViewCtx.isReadVisitAllowed(this.cpr)) {
        const name = this.$route?.name || '';
        if (!(specimenId > 0) && name && !name.endsWith('.Overview') && name !== 'VisitCollectSpecimens') throw new Error('Visit content is restricted');
        const visit = await navigationSvc.visit(this.cpr.cpId, this.cpr.id, visitId);
        if (requestId !== this.requestId) return;
        this.visit = visit; this.loaded = true; return;
      }
      const visit = await visitSvc.getVisit(visitId);
      if (requestId !== this.requestId) return;
      this.visit = visit;
      formUtil.createCustomFieldsMap(this.visit, true);
      this.loaded = true;
      } catch { if (requestId === this.requestId) this.contextError = true; }
    },

    _loadEvent: function(eventId) {
      cpSvc.getCpe(eventId).then(
        event => {
          event.eventCode = event.code;
          event.eventId = event.id;
          if (event.clinicalDiagnosis) {
            event.clinicalDiagnoses = [event.clinicalDiagnosis];
          }

          if (event.defaultSite) {
            event.site = event.defaultSite
          }

          delete event.id;
          this.visit = event;
          this.loaded = true;
        }
      );
    },

    _loadBlank: function() {
      this.visit = {
        id: null,
        cpId: this.cpr.cpId,
        cprId: this.cpr.id,
        eventId: null,
        name: null,
        status: null,
        visitDate: null,
        eventLabel: null
      };
      this.loaded = true;
    }
  }
}
</script>
