<template>
  <Divider />

  <div class="p-fluid p-grid conditional-visibility">
    <div class="p-field p-col-12">
      <div class="p-field-checkbox">
        <InputSwitch v-model="ctx.enabled" @change="updateExpression" />
        <label>{{ $t('forms.designer.show_when') }}</label>
      </div>
      <small>{{ $t('forms.designer.show_when_hint') }}</small>
    </div>

    <template v-if="ctx.enabled">
      <InlineMessage class="p-col-12" severity="warn" v-if="ctx.unrecognised">
        {{ $t('forms.designer.show_when_existing_rule') }}
      </InlineMessage>

      <template v-else>
        <div class="p-field p-col-12" v-if="rules.length > 1">
          <label>{{ $t('forms.designer.match_rules') }}</label>
          <Dropdown v-model="ctx.join" :options="joinOptions" option-label="label" option-value="value"
            @change="updateExpression" />
        </div>

        <div class="rule p-col-12" v-for="(rule, index) in rules" :key="index">
          <Dropdown v-model="rule.field" :options="controllerFields" option-label="label" option-value="value"
            :placeholder="$t('forms.designer.select_field')" @change="onControllerChange(rule)" />

          <Dropdown v-model="rule.operator" :options="getOperators(rule)" option-label="label" option-value="value"
            @change="updateExpression" />

          <Dropdown v-if="getValues(rule).length > 0" v-model="rule.value" :options="getValues(rule)"
            option-label="label" option-value="value" :placeholder="$t('forms.designer.select_value')"
            @change="updateExpression" />
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
import { computed, inject, reactive, toRef } from 'vue';
import Button from 'primevue/button';
import Divider from 'primevue/divider';
import Dropdown from 'primevue/dropdown';
import InlineMessage from 'primevue/inlinemessage';
import InputSwitch from 'primevue/inputswitch';
import InputText from 'primevue/inputtext';
import i18n from '@/common/services/I18n.js';

const MULTI_SELECT_TYPES = ['checkbox', 'multiSelectListbox'];
const BOOLEAN_TYPE = 'booleanCheckbox';
const SELECT_TYPES = ['radiobutton', 'combobox', ...MULTI_SELECT_TYPES];

export default {
  name: 'ConditionalVisibility',

  components: {
    Button,
    Divider,
    Dropdown,
    InlineMessage,
    InputSwitch,
    InputText
  },

  props: {
    field: Object
  },

  setup(props) {
    const form = inject('designerForm');
    const field = toRef(props, 'field');

    const fieldList = computed(() => {
      const result = [];
      for (const row of form.rows || []) {
        for (const candidate of row) {
          if (candidate !== field.value && candidate.name && candidate.type != 'label' && candidate.type != 'subForm') {
            result.push(candidate);
          }
        }
      }

      return result;
    });

    const controllerFields = computed(() =>
      fieldList.value.map(candidate => ({
        value: candidate.name,
        label: candidate.caption + ' (' + candidate.udn + ')'
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
      {value: '&&', label: i18n.msg('forms.designer.all_conditions')},
      {value: '||', label: i18n.msg('forms.designer.any_condition')}
    ];

    function getField(rule) {
      return fieldList.value.find(candidate => candidate.name == rule.field);
    }

    function isMulti(rule) {
      const controller = getField(rule);
      return controller && MULTI_SELECT_TYPES.indexOf(controller.type) >= 0;
    }

    function getOperators(rule) {
      if (isMulti(rule)) {
        return [{value: 'contains', label: i18n.msg('forms.designer.includes')}];
      }

      return [
        {value: 'equals', label: i18n.msg('forms.designer.equals')},
        {value: 'notEquals', label: i18n.msg('forms.designer.not_equals')}
      ];
    }

    function getValues(rule) {
      const controller = getField(rule);
      if (!controller) {
        return [];
      }

      if (controller.type == BOOLEAN_TYPE) {
        return [
          {value: true, label: i18n.msg('common.buttons.yes')},
          {value: false, label: i18n.msg('common.buttons.no')}
        ];
      }

      if (SELECT_TYPES.indexOf(controller.type) < 0) {
        return [];
      }

      return (controller.pvs || [])
        .filter(pv => pv.value != null && pv.value !== '')
        .map(pv => ({value: pv.value, label: pv.value}));
    }

    function onControllerChange(rule) {
      rule.value = null;
      rule.operator = isMulti(rule) ? 'contains' : 'equals';
      updateExpression();
    }

    function addRule() {
      rules.push({field: null, operator: 'equals', value: null});
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
        .filter(rule => rule.field && rule.value !== null && rule.value !== undefined && rule.value !== '')
        .map(rule => getRuleExpression(rule));
      field.value.showWhen = expressions.length > 0 ? expressions.map(expr => '(' + expr + ')').join(' ' + ctx.join + ' ') : null;
    }

    function getRuleExpression(rule) {
      const controller = getField(rule);
      const value = controller && controller.type == BOOLEAN_TYPE ?
        (rule.value ? 'true' : 'false') : JSON.stringify(rule.value);

      if (rule.operator == 'contains') {
        return rule.field + '.includes(' + value + ')';
      }

      return rule.field + (rule.operator == 'notEquals' ? ' != ' : ' == ') + value;
    }

    return {
      ctx,
      rules,
      joinOptions,
      controllerFields,
      getOperators,
      getValues,
      onControllerChange,
      addRule,
      removeRule,
      updateExpression
    };
  }
};

function parseExpression(expression) {
  if (!expression) {
    return {join: '&&', rules: [], unrecognised: false};
  }

  const join = expression.indexOf('||') >= 0 ? '||' : '&&';
  const parts = expression.split(join).map(part => part.trim().replace(/^\((.*)\)$/, '$1').trim());
  const rules = [];
  for (const part of parts) {
    const contains = part.match(/^([A-Za-z_][A-Za-z0-9_.]*)\.includes\((.+)\)$/);
    const equality = part.match(/^([A-Za-z_][A-Za-z0-9_.]*)\s*(==|!=)\s*(.+)$/);
    const match = contains || equality;
    if (!match) {
      return {join, rules: [], unrecognised: true};
    }

    let value;
    try {
      value = JSON.parse(contains ? match[2] : match[3]);
    } catch (e) {
      return {join, rules: [], unrecognised: true};
    }

    rules.push({
      field: match[1],
      operator: contains ? 'contains' : (match[2] == '!=' ? 'notEquals' : 'equals'),
      value
    });
  }

  return {join, rules, unrecognised: false};
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

@media (max-width: 640px) {
  .rule {
    grid-template-columns: 1fr;
  }
}
</style>
