<template>
  <div class="os-app-body">
    <Toast class="os-app-toast" />
    <ExportFormatDialog />
    <router-view />
  </div>
</template>

<script>
import Toast from "primevue/toast";
import ExportFormatDialog from './ExportFormatDialog.vue';

export default {
  components: {
    Toast, ExportFormatDialog
  }
}

</script>

<style scoped>

.os-app-body {
  position: relative;
  height: calc(100% - 40px);
}

</style>

<style>
.os-app-toast {
  z-index: 1200!important;
  background: transparent;
}

.os-app-toast .p-toast-message {
  margin: 0rem;
}

.os-app-toast .p-toast-message .p-toast-message-content {
  word-break: break-word;
}
</style>
