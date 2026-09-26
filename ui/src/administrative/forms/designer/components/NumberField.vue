<template>
  <div v-if="!preview">
    <CommonFieldProps :field="fm" :showDefaultValue="!fm.calculated">
      <div class="p-fluid p-grid">
        <div class="p-field p-col-12">
          <label>{{ $t("forms.designer.number_of_fraction_digits") }}</label>
          <InputNumber type="text" v-model="fm.noOfDigitsAfterDecimal" />
        </div>

        <div class="p-field p-col-12">
          <label>{{ $t("forms.designer.range") }}</label>
          <div class="p-formgroup-inline">
            <div class="p-field">
              <label class="p-sr-only">{{ $t("forms.designer.min") }}</label>
              <InputNumber type="text" :placeholder="$t('forms.designer.min')" v-model="fm.minValue" />
            </div>
            <div class="p-field">
              <label class="p-sr-only">{{ $t("forms.designer.max") }}</label>
              <InputNumber type="text" :placeholder="$t('forms.designer.max')" v-model="fm.maxValue" />
            </div>
          </div>
        </div>
      </div>

      <div class="calculation-builder p-field p-col-12" v-if="fm.calculated">
        <label>{{ $t("forms.designer.calculation") }}</label>
        <small class="p-d-block p-mb-2">{{ $t("forms.designer.calculation_help") }}</small>

        <div class="p-field p-mb-3">
          <label>{{ $t("forms.designer.calculation_missing_values") }}</label>
          <Dropdown v-model="missingValuePolicy" :options="missingValuePolicies" optionLabel="caption" optionValue="value" />
        </div>

        <div class="p-inputgroup p-mb-2">
          <Dropdown v-model="selectedSource" :options="sourceFields" optionLabel="caption"
            :placeholder="$t('forms.designer.calculation_select_field')" />
          <Button type="button" icon="pi pi-plus" :label="$t('forms.designer.calculation_add_field')"
            :disabled="!selectedSource" @click="addSource" />
        </div>

        <div class="calculation-actions p-mb-2">
          <Button v-for="operator of operators" :key="operator" type="button" :label="operator"
            class="p-button-outlined p-button-secondary" @click="addToken(operator)" />
          <Button type="button" :label="$t('forms.designer.calculation_clear')"
            class="p-button-text p-button-secondary" @click="clearFormula" />
        </div>

        <div class="formula-preview">
          {{ displayFormula || $t("forms.designer.calculation_empty") }}
        </div>
        <small class="p-d-block p-mt-2" v-if="sourceFields.length == 0">
          {{ $t("forms.designer.calculation_no_fields") }}
        </small>
      </div>
    </CommonFieldProps>
  </div>

  <div class="p-fluid p-grid" v-else>
    <div class="p-field p-col-12">
      <label v-if="!noLabel"> {{ fm.caption }} </label>
      <InputNumber type="text" :min="fm.minValue" :max="fm.maxValue"
        :minFractionDigits="fm.noOfDigitsAfterDecimal" :maxFractionDigits="fm.noOfDigitsAfterDecimal"
        v-tooltip.bottom="fm.toolTip" v-model="fm.$unused" :disabled="fm.calculated || fm.disabled" />
    </div>
  </div>
</template>

<script>
import { computed, inject, reactive, ref } from "vue";
import InputNumber from "primevue/inputnumber";
import Dropdown from "primevue/dropdown";
import Button from "primevue/button";
import CommonFieldProps from "./CommonFieldProps.vue";
import i18n from "@/common/services/I18n.js";

const ZERO_MISSING_PREFIX = /^\s*0\s*\+\s*\(([\s\S]*)\)\s*$/;

export default {
  name: "NumberField",

  components: {
    InputNumber,
    Dropdown,
    Button,
    CommonFieldProps
  },

  props: {
    field: Object,
    preview: Boolean,
    noLabel: Boolean
  },

  setup(props) {
    const fm = reactive(props.field);
    if (fm.defaultValue != undefined && fm.defaultValue != null && fm.defaultValue != "") {
      fm["$unused"] = +fm.defaultValue;
    } else {
      fm["$unused"] = undefined;
    }

    const designerForm = inject("designerForm", null);
    const selectedSource = ref(null);
    const operators = ["+", "-", "×", "÷", "(", ")"];
    const missingValuePolicies = [
      {value: "empty", caption: i18n.msg("forms.designer.calculation_missing_empty")},
      {value: "zero", caption: i18n.msg("forms.designer.calculation_missing_zero")}
    ];

    const sourceFields = computed(() => {
      const result = [];
      for (const row of designerForm?.rows || []) {
        for (const field of row) {
          if (field !== fm && field.type == "numberField" && !field.calculated && field.name) {
            result.push({name: field.name, caption: field.caption || field.name});
          }
        }
      }

      return result;
    });

    const missingValuePolicy = computed({
      get: () => isZeroMissingFormula(fm.formula) ? "zero" : "empty",
      set: (policy) => setFormulaExpression(getFormulaExpression(), policy == "zero")
    });

    const addSource = () => {
      if (!selectedSource.value) {
        return;
      }

      const formula = getFormulaExpression().trim();
      setFormulaExpression(formula && /[A-Za-z0-9_)]\s*$/.test(formula)
        ? formula + " + " + selectedSource.value.name
        : formula + selectedSource.value.name);
      selectedSource.value = null;
    };

    const addToken = (operator) => {
      const token = operator == "×" ? "*" : operator == "÷" ? "/" : operator;
      setFormulaExpression((getFormulaExpression().trim() + " " + token).trim() + " ");
    };

    const clearFormula = () => fm.formula = "";

    const displayFormula = computed(() => {
      const captions = sourceFields.value.reduce((map, field) => {
        map[field.name] = field.caption;
        return map;
      }, {});
      return getFormulaExpression().replace(/\b[A-Za-z_][A-Za-z0-9_]*\b/g, name => captions[name] || name);
    });

    function getFormulaExpression() {
      return unwrapZeroMissingFormula(fm.formula);
    }

    function setFormulaExpression(expression, useZeroForMissing = missingValuePolicy.value == "zero") {
      fm.formula = useZeroForMissing ? "0 + (" + expression + ")" : expression;
    }

    return {
      fm,
      selectedSource,
      sourceFields,
      operators,
      missingValuePolicies,
      missingValuePolicy,
      addSource,
      addToken,
      clearFormula,
      displayFormula
    };
  }
};

function isZeroMissingFormula(formula) {
  return ZERO_MISSING_PREFIX.test(formula || "");
}

function unwrapZeroMissingFormula(formula) {
  const match = (formula || "").match(ZERO_MISSING_PREFIX);
  return match ? match[1] : (formula || "");
}
</script>

<style scoped>
.calculation-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
}

.formula-preview {
  min-height: 2.5rem;
  padding: 0.6rem 0.75rem;
  border: 1px solid var(--surface-border, rgb(206, 212, 218));
  border-radius: 4px;
  background: var(--surface-ground, rgb(248, 249, 250));
  font-family: monospace;
  white-space: pre-wrap;
}
</style>
