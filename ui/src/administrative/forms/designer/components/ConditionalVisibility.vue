<template>
  <Divider />

  <div class="p-fluid p-grid conditional-visibility">
    <div class="p-field p-col-12">
      <div class="p-field-checkbox">
        <InputSwitch v-model="ctx.enabled" @change="updateExpression" />
        <label>{{ $t("forms.designer.show_when") }}</label>
      </div>
      <small>{{ $t("forms.designer.show_when_hint") }}</small>
    </div>

    <template v-if="ctx.enabled">
      <InlineMessage class="p-col-12" severity="warn" v-if="ctx.unrecognised">
        {{ $t("forms.designer.show_when_existing_rule") }}
      </InlineMessage>

      <template v-else>
        <div class="p-field p-col-12" v-if="rules.length > 1">
          <label>{{ $t("forms.designer.match_rules") }}</label>
          <Dropdown v-model="ctx.join" :options="joinOptions" option-label="label" option-value="value"
            @change="updateExpression" />
        </div>

        <div class="rule p-col-12" :class="{ betweenRule: rule.operator == 'between' }"
          v-for="(rule, index) in rules" :key="index">
          <Dropdown v-model="rule.field" :options="controllerFields" option-label="label" option-value="value"
            :placeholder="$t('forms.designer.select_field')" @change="onControllerChange(rule)" />

          <Dropdown v-model="rule.operator" :options="getOperators(rule)" option-label="label" option-value="value"
            @change="updateExpression" />

          <Dropdown v-if="getValues(rule).length > 0" v-model="rule.value" :options="getValues(rule)"
            option-label="label" option-value="value" :placeholder="$t('forms.designer.select_value')"
            @change="updateExpression" />
          <template v-else-if="rule.operator == 'between'">
            <InputNumber v-model="rule.min" :placeholder="$t('forms.designer.min')"
              @update:model-value="updateExpression" />
            <InputNumber v-model="rule.max" :placeholder="$t('forms.designer.max')"
              @update:model-value="updateExpression" />
          </template>
          <InputNumber v-else-if="isNumeric(rule)" v-model="rule.value"
            :placeholder="$t('forms.designer.condition_value')" @update:model-value="updateExpression" />
          <InputText v-else v-model="rule.value" :placeholder="$t('forms.designer.condition_value')"
            @input="updateExpression" />

          <Button icon="pi pi-times" class="p-button-text p-button-danger" type="button"
            v-tooltip.bottom="$t('common.buttons.delete')" @click="removeRule(index)" />
        </div>

        <div class="p-col-12">
          <Button type="button" icon="pi pi-plus" class="p-button-text"
            :label="$t('forms.designer.add_condition')" @click="addRule" />
        </div>
      </template>
    </template>
  </div>
</template>

<script>
import { computed, inject, reactive, toRef } from "vue";
import Button from "primevue/button";
import Divider from "primevue/divider";
import Dropdown from "primevue/dropdown";
import InlineMessage from "primevue/inlinemessage";
import InputNumber from "primevue/inputnumber";
import InputSwitch from "primevue/inputswitch";
import InputText from "primevue/inputtext";
import i18n from "@/common/services/I18n.js";

const MULTI_SELECT_TYPES = ["checkbox", "multiSelectListbox"];
const BOOLEAN_TYPE = "booleanCheckbox";
const SELECT_TYPES = ["radiobutton", "combobox", ...MULTI_SELECT_TYPES];

export default {
  name: "ConditionalVisibility",

  components: {
    Button,
    Divider,
    Dropdown,
    InlineMessage,
    InputNumber,
    InputSwitch,
    InputText
  },

  props: {
    field: Object
  },

  setup(props) {
    const form = inject("designerForm");
    const field = toRef(props, "field");

    const fieldList = computed(() => {
      const result = [];
      for (const row of form.rows || []) {
        for (const candidate of row) {
          if (candidate !== field.value && candidate.name && candidate.type != "label" && candidate.type != "subForm") {
            result.push(candidate);
          }
        }
      }

      return result;
    });

    const controllerFields = computed(() =>
      fieldList.value.map(candidate => ({
        value: candidate.name,
        label: candidate.caption + " (" + candidate.udn + ")"
      }))
    );

    const parsed = parseExpression(field.value.showWhen);
    const rules = reactive(parsed.rules);
    const ctx = reactive({
      enabled: !!field.value.showWhen,
      join: parsed.join,
      unrecognised: parsed.unrecognised
    });

    const joinOptions = [
      {value: "&&", label: i18n.msg("forms.designer.all_conditions")},
      {value: "||", label: i18n.msg("forms.designer.any_condition")}
    ];

    function getField(rule) {
      return fieldList.value.find(candidate => candidate.name == rule.field);
    }

    function isMulti(rule) {
      const controller = getField(rule);
      return controller && MULTI_SELECT_TYPES.indexOf(controller.type) >= 0;
    }

    function isNumeric(rule) {
      return getField(rule)?.type == "numberField";
    }

    function getOperators(rule) {
      if (isMulti(rule)) {
        return [{value: "contains", label: i18n.msg("forms.designer.includes")}];
      }

      if (isNumeric(rule)) {
        return [
          {value: "equals", label: i18n.msg("forms.designer.equals")},
          {value: "notEquals", label: i18n.msg("forms.designer.not_equals")},
          {value: "greaterThan", label: i18n.msg("forms.designer.greater_than")},
          {value: "greaterOrEqual", label: i18n.msg("forms.designer.greater_or_equal")},
          {value: "lessThan", label: i18n.msg("forms.designer.less_than")},
          {value: "lessOrEqual", label: i18n.msg("forms.designer.less_or_equal")},
          {value: "between", label: i18n.msg("forms.designer.between")}
        ];
      }

      return [
        {value: "equals", label: i18n.msg("forms.designer.equals")},
        {value: "notEquals", label: i18n.msg("forms.designer.not_equals")}
      ];
    }

    function getValues(rule) {
      const controller = getField(rule);
      if (!controller) {
        return [];
      }

      if (controller.type == BOOLEAN_TYPE) {
        return [
          {value: true, label: i18n.msg("common.buttons.yes")},
          {value: false, label: i18n.msg("common.buttons.no")}
        ];
      }

      if (SELECT_TYPES.indexOf(controller.type) < 0) {
        return [];
      }

      return (controller.pvs || [])
        .filter(pv => pv.value != null && pv.value !== "")
        .map(pv => ({value: pv.value, label: pv.value}));
    }

    function onControllerChange(rule) {
      rule.value = null;
      rule.min = null;
      rule.max = null;
      rule.operator = isMulti(rule) ? "contains" : "equals";
      updateExpression();
    }

    function addRule() {
      rules.push({field: null, operator: "equals", value: null, min: null, max: null});
    }

    function removeRule(index) {
      rules.splice(index, 1);
      updateExpression();
    }

    function updateExpression() {
      if (!ctx.enabled) {
        field.value.showWhen = null;
        ctx.unrecognised = false;
        return;
      }

      if (ctx.unrecognised) {
        return;
      }

      const expressions = rules
        .filter(rule => rule.field && isRuleComplete(rule))
        .map(rule => getRuleExpression(rule));
      field.value.showWhen = expressions.length > 0 ? expressions.map(expr => "(" + expr + ")").join(" " + ctx.join + " ") : null;
    }

    function isRuleComplete(rule) {
      if (rule.operator == "between") {
        return rule.min !== null && rule.min !== undefined && rule.min !== "" &&
          rule.max !== null && rule.max !== undefined && rule.max !== "" && Number(rule.min) <= Number(rule.max);
      }

      return rule.value !== null && rule.value !== undefined && rule.value !== "";
    }

    function getRuleExpression(rule) {
      const controller = getField(rule);
      const value = controller && controller.type == BOOLEAN_TYPE ?
        (rule.value ? "true" : "false") : JSON.stringify(rule.value);

      if (rule.operator == "contains") {
        return rule.field + ".includes(" + value + ")";
      }

      if (rule.operator == "between") {
        return rule.field + " >= " + rule.min + " && " + rule.field + " <= " + rule.max;
      }

      const operators = {
        notEquals: " != ",
        greaterThan: " > ",
        greaterOrEqual: " >= ",
        lessThan: " < ",
        lessOrEqual: " <= "
      };
      return rule.field + (operators[rule.operator] || " == ") + value;
    }

    return {
      ctx,
      rules,
      joinOptions,
      controllerFields,
      getOperators,
      getValues,
      isNumeric,
      onControllerChange,
      addRule,
      removeRule,
      updateExpression
    };
  }
};

function parseExpression(expression) {
  if (!expression) {
    return {join: "&&", rules: [], unrecognised: false};
  }

  const join = expression.indexOf("||") >= 0 ? "||" : "&&";
  const parts = splitTopLevel(expression, join).map(part => unwrapParentheses(part.trim()));
  const rules = [];
  for (const part of parts) {
    const contains = part.match(/^([A-Za-z_][A-Za-z0-9_.]*)\.includes\((.+)\)$/);
    const between = part.match(/^([A-Za-z_][A-Za-z0-9_.]*)\s*>=\s*(-?(?:\d+(?:\.\d*)?|\.\d+))\s*&&\s*\1\s*<=\s*(-?(?:\d+(?:\.\d*)?|\.\d+))$/);
    const comparison = part.match(/^([A-Za-z_][A-Za-z0-9_.]*)\s*(==|!=|>=|>|<=|<)\s*(.+)$/);
    const match = contains || between || comparison;
    if (!match) {
      return {join, rules: [], unrecognised: true};
    }

    if (between) {
      rules.push({field: between[1], operator: "between", min: Number(between[2]), max: Number(between[3])});
      continue;
    }

    let value;
    try {
      value = JSON.parse(contains ? match[2] : match[3]);
    } catch (e) {
      return {join, rules: [], unrecognised: true};
    }

    const op = contains ? "contains" : match[2];
    const operators = {"!=": "notEquals", ">": "greaterThan", ">=": "greaterOrEqual", "<": "lessThan", "<=": "lessOrEqual"};
    rules.push({field: match[1], operator: operators[op] || "equals", value});
  }

  return {join, rules, unrecognised: false};
}

function splitTopLevel(expression, join) {
  const parts = [];
  let start = 0;
  let depth = 0;
  for (let i = 0; i < expression.length; ++i) {
    if (expression[i] == "(") ++depth;
    else if (expression[i] == ")") --depth;
    else if (depth == 0 && expression.substring(i, i + join.length) == join) {
      parts.push(expression.substring(start, i));
      start = i + join.length;
      i += join.length - 1;
    }
  }

  parts.push(expression.substring(start));
  return parts;
}

function unwrapParentheses(expression) {
  while (expression.startsWith("(") && expression.endsWith(")")) {
    let depth = 0;
    let wrapsAll = true;
    for (let i = 0; i < expression.length - 1; ++i) {
      if (expression[i] == "(") ++depth;
      else if (expression[i] == ")") --depth;
      if (depth == 0) {
        wrapsAll = false;
        break;
      }
    }

    if (!wrapsAll) break;
    expression = expression.substring(1, expression.length - 1).trim();
  }

  return expression;
}
</script>

<style scoped>
.conditional-visibility small {
  display: block;
  margin-top: 0.25rem;
}

.rule {
  display: grid;
  grid-template-columns: minmax(12rem, 2fr) minmax(9rem, 1fr) minmax(10rem, 1.5fr) auto;
  gap: 0.5rem;
  align-items: center;
}

.rule.betweenRule {
  grid-template-columns: minmax(12rem, 2fr) minmax(9rem, 1fr) minmax(7rem, 1fr) minmax(7rem, 1fr) auto;
}

@media (max-width: 640px) {
  .rule,
  .rule.betweenRule {
    grid-template-columns: 1fr;
  }
}
</style>
