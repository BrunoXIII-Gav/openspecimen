<template>
  <os-page>
    <os-page-head>
      <span>
        <h3 v-t="'pvs.list'">Permissible Values</h3>
      </span>
      <template #right>
        <os-button left-icon="plus" :label="$t('common.buttons.create')"
          @click="$goto('PvAddEdit', {pvId: -1}, {})" />
      </template>
    </os-page-head>

    <os-page-body>
      <os-grid>
        <os-grid-column :width="3">
          <os-panel>
            <template #header>
              <span v-t="'pvs.attributes'">Attributes</span>
            </template>
            <template #default>
              <div class="modules-list">
                <div class="module"
                  :class="$route.params.attribute === attr.attribute ? 'selected' : ''"
                  v-for="(attr, idx) in ctx.attributes"
                  :key="idx"
                  @click="selectAttribute(attr)">
                  <span>{{ attr.attribute }}</span>
                  <span class="attr-count">{{ attr.count }}</span>
                </div>
              </div>
            </template>
          </os-panel>
        </os-grid-column>

        <os-grid-column :width="9">
          <router-view
            v-if="$route.params.attribute && $route.params.attribute !== '-'"
            :attribute="$route.params.attribute"
            :key="$route.params.attribute"
            @pvChanged="reloadAttributes"
          />
        </os-grid-column>
      </os-grid>
    </os-page-body>
  </os-page>
</template>

<script>
import routerSvc  from '@/common/services/Router.js';
import pvAdminSvc from '@/administrative/services/PermissibleValueAdmin.js';

export default {
  data() {
    return {
      ctx: {
        attributes: [],
        loading: false
      }
    };
  },

  created() {
    this.loadAttributes();
  },

  methods: {
    async loadAttributes() {
      this.ctx.loading = true;
      await this.reloadAttributes();
      this.ctx.loading = false;
      const currentAttr = this.$route.params.attribute;
      if ((!currentAttr || currentAttr === '-') && this.ctx.attributes.length > 0) {
        this.selectAttribute(this.ctx.attributes[0]);
      }
    },

    async reloadAttributes() {
      this.ctx.attributes = await pvAdminSvc.getAttributes({activityStatus: 'all'});
    },

    selectAttribute(attr) {
      routerSvc.goto('PvsListItemValues', {attribute: attr.attribute});
    }
  }
}
</script>

<style scoped>
.modules-list {
  margin: -1rem;
}

.module {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.75rem 1rem;
  border-bottom: 1px solid #ddd;
  cursor: pointer;
}

.module:last-child {
  border-bottom: 0;
}

.module.selected {
  background: #337ab7;
  border-color: #337ab7;
  color: #fff;
}

.module:hover:not(.selected) {
  background: #f5f5f5;
}

.attr-count {
  font-size: 0.8rem;
  opacity: 0.7;
}
</style>
