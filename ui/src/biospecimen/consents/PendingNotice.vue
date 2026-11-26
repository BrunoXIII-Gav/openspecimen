<template>
  <aside class="os-consent-pending-notice" v-if="allowed && (status?.pending || unavailable)">
    <os-message type="warn" v-if="status?.pending">
      {{ $t('consent_tracking.messages.' + status.status) }}
    </os-message>
    <os-message type="info" v-else>
      {{ $t('consent_tracking.unavailable') }}
    </os-message>
  </aside>
</template>

<script>
import trackingSvc from '@/biospecimen/services/ConsentTracking.js';

export default {
  props: ['cpr'],
  inject: ['cpViewCtx'],
  data() { return {status: null, unavailable: false, request: 0}; },
  computed: {
    allowed() {
      const cp = this.cpViewCtx.getCp();
      return this.cpr?.id > 0 && !this.cpr.contextOnly && !cp.consentsWaived &&
        !cp.visitLevelConsents && !this.$osSvc?.ecDocSvc && this.cpViewCtx.isReadConsentAllowed(this.cpr);
    }
  },
  watch: {
    cpr: {immediate: true, handler() { this.refresh(); }},
    allowed() { this.refresh(); },
    '$route.fullPath'() { this.refresh(); }
  },
  mounted() {
    this.unsubscribe = trackingSvc.subscribe(cprId => {
      if (Number(cprId) === Number(this.cpr?.id)) this.refresh();
    });
  },
  beforeUnmount() {
    ++this.request;
    this.unsubscribe?.();
  },
  methods: {
    async refresh() {
      const request = ++this.request;
      this.status = null;
      this.unavailable = false;
      if (!this.allowed) return;
      try {
        const status = await trackingSvc.getStatus(this.cpr.id);
        if (request === this.request) this.status = status;
      } catch {
        // A follow-up warning must never prevent visit or specimen entry.
        if (request === this.request) this.unavailable = true;
      }
    }
  }
};
</script>

<style scoped>
.os-consent-pending-notice {
  flex: 0 0 auto;
  min-width: 0;
  margin: 0 0 0.75rem;
}

.os-consent-pending-notice :deep(.os-message) {
  margin: 0;
  border-radius: 4px;
  border-width: 1px;
}

.os-consent-pending-notice :deep(.p-message-warn) {
  background: #fffbf0;
  border-color: #e9dfc8;
  color: #755c30;
}

.os-consent-pending-notice :deep(.p-message-wrapper) {
  padding: 0.65rem 0.85rem;
  align-items: center;
}

.os-consent-pending-notice :deep(.p-message-icon) {
  width: 1rem;
  height: 1rem;
  flex-shrink: 0;
  margin-right: 0.6rem;
  color: inherit;
}

.os-consent-pending-notice :deep(.p-message-text) {
  font-size: 13px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
</style>
