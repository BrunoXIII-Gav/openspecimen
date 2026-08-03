<template>
  <div class="p-fluid p-grid">
    <div class="p-field p-col-12">
      <label>{{ $t('forms.designer.type') }}</label>
      <InputText type="text" disabled v-model="fieldTitle" />
    </div>

    <div class="p-field p-col-12">
      <label>{{ $t('forms.designer.display_label') }}</label>
      <InputText type="text" v-model="fm.caption" ref="labelRef" />
    </div>

    <div class="p-field p-col-12">
      <label>{{ $t('forms.designer.variable_name') }}</label>
      <InputText type="text" v-model="fm.udn" disabled v-if="fm.$saved" />
      <InputText type="text" v-model="fm.udn" v-else />
      <span class="hint" v-if="!fm.$saved">{{ $t('forms.designer.variable_name_hint') }}</span>
    </div>

    <div class="p-field p-col-12">
      <label>{{ $t('forms.designer.tooltip') }}</label>
      <InputText type="text" v-model="fm.toolTip" />
    </div>

    <div class="p-field p-col-4">
      <label>{{ $t('forms.designer.phi') }}</label>
      <br />
      <InputSwitch v-model="fm.phi" />
    </div>
    <div class="p-field p-col-4">
      <label>{{ $t('forms.designer.required') }}</label>
      <br />
      <InputSwitch v-model="fm.mandatory" />
    </div>
    <div class="p-field p-col-4">
      <label>{{ $t('forms.designer.show_in_grid') }}</label>
      <br />
      <InputSwitch v-model="fm.showInGrid" />
    </div>

    <div class="p-field p-col-12" v-if="showDefaultValue">
      <label>{{ $t('forms.designer.default_value') }}</label>
      <InputText type="text" v-model="fm.defaultValue" />
    </div>
  </div>

  <slot></slot>

  <div class="p-fluid p-grid" v-show="!fm.$sfField">
    <div class="p-field p-col-12">
      <div class="p-field-checkbox">
        <Checkbox
          v-model="fm.$sameRowAsLastField"
          :binary="true"
        />
        <label>{{ $t('forms.designer.same_row_as_last') }}</label>
      </div>
    </div>
  </div>
</template>

<script>
import { computed, onMounted, reactive, ref, watch } from "vue";
import InputText from "primevue/inputtext";
import InputSwitch from "primevue/inputswitch";
import Checkbox from "primevue/checkbox";
import utility from "../services/Utility.js";
import fieldsRegistry from "../services/FieldsRegistry.js";

export default {
  name: "CommonFieldProps",

  components: {
    InputText,
    InputSwitch,
    Checkbox
  },

  props: {
    field: Object,
    showDefaultValue: Boolean,
  },

  setup(props) {
    let fm = reactive(props.field);
    let fieldTitle = computed(() => fieldsRegistry.getDisplayLabel(fm.type));
    if (!fm.$saved) {
      watch(
        () => fm.caption,
        () => {
          fm.udn = fm.name = utility.toSnakeCase(fm.caption).substring(0, 64);
        }
      );

      watch(
        () => fm.udn,
        () => {
          fm.name = fm.udn = fm.udn.substring(0, 64);
        }
      );
    }

    let labelRef = ref(null);
    onMounted(() => {
      setTimeout(() => labelRef.value.$el.focus(), 100);
    });

    return {
      fm,
      fieldTitle,
      labelRef,
    };
  },
};
</script>

<style scoped>
.hint {
  font-size: 14px;
  font-style: italic;
}
</style>
