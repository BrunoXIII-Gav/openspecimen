<template>
  <os-dialog ref="dialog" style="width: 28rem; max-width: 95vw" @opened="loadFormats" @closed="cancel">
    <template #header><span>{{ $t('export.download_format') }}</span></template>
    <template #content>
      <os-dropdown ref="formats" v-model="selection.format" :list-source="options" :optional="false" />
    </template>
    <template #footer>
      <os-button text :label="$t('common.buttons.cancel')" @click="cancel" />
      <os-button primary :label="$t('common.buttons.download')" @click="download" />
    </template>
  </os-dialog>
</template>

<script>
import exportFormat from '@/common/services/ExportFormat.js';

export default {
  data() {
    return {selection: exportFormat.state, options: {selectProp: 'value', displayProp: 'label', options: [
      {value: 'CSV', label: 'CSV'}, {value: 'XLSX', label: 'Excel (.xlsx)'}
    ]}};
  },

  watch: {
    'selection.visible'(visible) {
      if (visible) this.$refs.dialog.open();
      else this.$refs.dialog.close();
    }
  },

  unmounted() { exportFormat.finish(); },

  methods: {
    loadFormats() { this.$nextTick(() => this.$refs.formats.loadOptions()); },
    cancel() { exportFormat.finish(); },
    download() { exportFormat.finish(this.selection.format); }
  }
};
</script>
