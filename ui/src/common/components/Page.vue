<template>
  <div class="os-page">
    <slot></slot>

    <os-ask-os class="os-floating-ask-os" v-if="floatingAskOsEnabled" />
  </div>
</template>

<script>
export default {
  computed: {
    appProps: function() {
      return (this.$ui && this.$ui.global && this.$ui.global.appProps) || {};
    },

    floatingAskOsEnabled: function() {
      return this.appProps.floating_ask_os_enabled != false;
    }
  }
}
</script>

<style scoped>
.os-page {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.os-page:not(:last-child) {
  border-right: 1px solid #ddd;
}

.os-page :deep(.os-page-body) {
  flex: 1;
  overflow-y: auto;
  padding-top: 0.5rem;
  padding-bottom: 0.5rem;
}

.os-floating-ask-os {
  position: absolute;
  bottom: 0.5rem;
  right: 3rem;
}
</style>
