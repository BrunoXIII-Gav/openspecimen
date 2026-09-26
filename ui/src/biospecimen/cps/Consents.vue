<template>
  <div class="os-consents">
    <os-page-toolbar v-if="editAllowed && !ctx.hasEc && !cp.consentsSource && ctx.tiers && ctx.tiers.length > 0">
      <template #default>
        <os-button left-icon="plus" :label="$t('common.buttons.add')" @click="showAddEditConsentTierDialog({})" />
      </template>
    </os-page-toolbar>

    <os-message type="info" v-if="ctx.hasDocs == null || ctx.tiers == null">
      <span v-t="'common.loading'">Loading...</span>
    </os-message>

    <div v-if="cp.consentsSource || noDocsAndTiers">
      <os-card v-if="cp.consentsSource">
        <template #body>
          <div>
            <span>
              <span v-t="'cps.consents_sourced_from'"></span>
              <a :href="consentsSourceUrl" target="_blank">{{cp.consentsSource.shortTitle}}</a>
            </span>

            <os-divider v-if="editAllowed" />

            <div v-if="editAllowed">
              <os-button left-icon="edit" :label="$t('common.buttons.edit')" @click="showSelectCpDialog" />

              <os-button left-icon="times" :label="$t('common.buttons.remove')" @click="unsetConsentsCp"
                style="margin-left: 1rem;" />
            </div>
          </div>

          <os-message type="info" v-if="noDocsAndTiers">
            <span v-t="'cps.no_consents_in_source_cp'">No consents to display</span>
          </os-message>
        </template>
      </os-card>
  
      <os-card v-else-if="noDocsAndTiers">
        <template #body v-if="editAllowed && !cp.consentsWaived">
          <div>
            <span v-t="'cps.waive_consents_q'">Do you want to waive the consents?</span>
          </div>

          <os-divider />

          <div>
            <os-button :label="$t('common.buttons.yes')" @click="waiveConsents" />
          </div>
        </template>
        <template #body v-else-if="cp.consentsWaived">
          <div>
            <span v-t="'cps.consents_waived'" v-if="!editAllowed">Consents are waived</span>
            <span v-t="'cps.collect_consents_q'" v-else>Consents are waived. Do you want to start collecting consents?</span>
          </div>

          <os-divider v-if="editAllowed" />

          <div v-if="editAllowed">
            <os-button :label="$t('common.buttons.yes')" @click="undoWaiveConsents" />
          </div>
        </template>
        <template #body v-else>
          <os-message type="info">
            <span v-t="'cps.no_consents'">No consents to show.</span>
          </os-message>
        </template>
      </os-card>

      <os-card v-if="!cp.consentsWaived && !cp.consentsSource && noDocsAndTiers && editAllowed">
        <template #body>
          <div>
            <span v-t="'cps.source_from_another_cp'">
              Do you want to source consents from another collection protocol?
            </span>
          </div>

          <os-divider />

          <div>
            <os-button :label="$t('common.buttons.yes')" @click="showSelectCpDialog" />
          </div>
        </template>
      </os-card>
    </div>

    <os-grid v-if="!ctx.hasEc">
      <os-grid-column :width="12">
        <os-card v-if="editAllowed">
          <template #body>
            <div class="specimen-consents-setting">
              <span v-t="'specimen_consents.cp_setting'">Collect independent consents for each specimen.</span>
              <os-button size="small" :label="$t(cp.specimenConsentsEnabled ? 'specimen_consents.disable' : 'specimen_consents.enable')"
                @click="setSpecimenConsentsEnabled(!cp.specimenConsentsEnabled)" />
            </div>
          </template>
        </os-card>

        <os-button left-icon="plus" :label="$t('cps.add_consent_tier')" @click="showAddEditConsentTierDialog({})"
          v-if="!cp.consentsWaived && !cp.consentsSource && ctx.tiers && ctx.tiers.length == 0 && editAllowed" />

        <os-card v-for="tier of ctx.tiers || []" :key="tier.id">
          <template #body>
            <div class="os-consent-tier">
              <div class="statement">
                <span>{{tier.statement}} ({{tier.statementCode}})</span>
              </div>
              <div class="actions" v-if="!cp.consentsSource && editAllowed">
                <os-button-group>
                  <os-button size="small" left-icon="edit"  @click="showAddEditConsentTierDialog(tier)" />
                  <os-button size="small" left-icon="trash" @click="deleteTier(tier)" />
                </os-button-group>
              </div>
            </div>
          </template>
        </os-card>

        <os-card class="consent-groups" v-if="!ctx.hasEc && !cp.consentsSource && ctx.tiers && ctx.tiers.length > 0">
      <template #header>{{ $t('cps.consent_groups') }}</template>
      <template #body>
        <os-message type="info">{{ $t('cps.consent_groups_help') }}</os-message>
        <div class="consent-group" v-for="(group, index) of ctx.consentGroups" :key="group.id || index">
          <div>
            <strong>{{ group.caption }}</strong>
            <div class="members">{{ group.members.map(member => statementFor(member.statementCode)).join(", ") }}</div>
          </div>
          <os-button-group v-if="editAllowed">
            <os-button size="small" left-icon="edit" @click="showConsentGroupDialog(group, index)" />
            <os-button size="small" left-icon="trash" @click="deleteConsentGroup(index)" />
          </os-button-group>
        </div>
        <os-button left-icon="plus" :label="$t('cps.add_consent_group')" @click="showConsentGroupDialog()" v-if="editAllowed" />
      </template>
    </os-card>
      </os-grid-column>
    </os-grid>

    <os-plugin-views v-else page="cp-detail" view="consents" :viewProps="{cp, filters, ctx}"
      @consent-documents="ctx.hasDocs = $event && $event.length > 0"
      @cp-saved="$emit('cp-saved', $event)" />

    <os-dialog ref="addEditConsentTierDialog">
      <template #header>
        <span v-if="ctx.tier.id > 0" v-t="'cps.edit_consent_tier'">Edit Consent Tier</span>
        <span v-else v-t="'cps.add_consent_tier'">Add Consent Tier</span>
      </template>
      <template #content>
        <os-form ref="addEditConsentTier" :schema="addEditFs" :data="ctx" />
      </template>
      <template #footer>
        <os-button text    :label="$t('common.buttons.cancel')" @click="hideAddEditConsentTierDialog" />
        <os-button primary :label="$t('common.buttons.add')"    @click="saveOrUpdate" v-if="!ctx.tier.id" />
        <os-button primary :label="$t('common.buttons.update')" @click="saveOrUpdate" v-else />
      </template>
    </os-dialog>

    <os-dialog ref="consentGroupDialog">
      <template #header>{{ $t(ctx.consentGroupIndex == null ? 'cps.add_consent_group' : 'cps.edit_consent_group') }}</template>
      <template #content>
        <div class="consent-group-editor">
          <div class="field">
            <os-label><span v-t="'cps.consent_group_caption'">Group title</span></os-label>
            <input class="group-caption" type="text" v-model.trim="ctx.consentGroup.caption" maxlength="200" />
          </div>

          <os-divider />

          <div class="field">
            <os-label><span v-t="'cps.consent_group_members'">Group statements</span></os-label>
            <div class="statement-selector">
              <os-boolean-checkbox v-for="tier of ctx.tiers" :key="tier.statementCode"
                v-model="ctx.consentGroupSelection[tier.statementCode]"
                :inline-label="tier.statement + ' (' + tier.statementCode + ')'"
                @change="syncConsentGroupMembers" />
            </div>
          </div>

          <div class="consent-group-rules" v-if="ctx.consentGroup.members.length > 0">
            <os-divider />
            <os-label><span v-t="'cps.consent_group_rules'">Conditions for each statement</span></os-label>
            <div class="consent-group-member" v-for="member of ctx.consentGroup.members" :key="member.statementCode">
              <strong>{{ statementFor(member.statementCode) }}</strong>
              <div class="condition-grid" v-if="otherMembers(member.statementCode).length > 0">
                <div>
                  <span class="condition-label" v-t="'cps.consent_group_disable_when'">Disable when selected</span>
                  <os-boolean-checkbox v-for="other of otherMembers(member.statementCode)" :key="other.statementCode"
                    :model-value="conditionSelected(member, 'disabledWhen', other.statementCode)"
                    :inline-label="statementFor(other.statementCode)"
                    @change="toggleCondition(member, 'disabledWhen', other.statementCode, $event)" />
                </div>
                <div>
                  <span class="condition-label" v-t="'cps.consent_group_visible_when'">Show only when selected</span>
                  <os-boolean-checkbox v-for="other of otherMembers(member.statementCode)" :key="other.statementCode"
                    :model-value="conditionSelected(member, 'visibleWhen', other.statementCode)"
                    :inline-label="statementFor(other.statementCode)"
                    @change="toggleCondition(member, 'visibleWhen', other.statementCode, $event)" />
                </div>
              </div>
            </div>
          </div>
        </div>
      </template>
      <template #footer>
        <os-button text :label="$t('common.buttons.cancel')" @click="hideConsentGroupDialog" />
        <os-button primary :label="$t('common.buttons.save')" @click="saveConsentGroup" />
      </template>
    </os-dialog>

    <os-delete-object ref="deleteConsentTierDialog" :input="ctx.deleteOpts" v-if="ctx.tier" />

    <os-dialog ref="selectCpDialog">
      <template #header>
        <span v-t="'cps.select_cp'">Select Collection Protocol</span>
      </template>
      <template #content>
        <os-form ref="selectCpForm" :schema="consentCpFs" :data="ctx" />
      </template>
      <template #footer>
        <os-button text    :label="$t('common.buttons.cancel')" @click="hideSelectCpDialog" />
        <os-button primary :label="$t('common.buttons.update')" @click="setConsentsCp" />
      </template>
    </os-dialog>
  </div>
</template>

<script>

import consentSchema from '@/biospecimen/schemas/cps/consent-addedit.js';
import consentCpSchema from '@/biospecimen/schemas/cps/consent-cp.js';

import alertsSvc from '@/common/services/Alerts.js';
import authSvc from '@/common/services/Authorization.js';
import cpSvc from '@/biospecimen/services/CollectionProtocol.js';
import routerSvc from '@/common/services/Router.js';
import util from '@/common/services/Util.js';

import cpResources from './Resources.js';

export default {
  props: ['cp', 'filters'],

  data() {
    const editAllowed = authSvc.isAllowed(cpResources.updateOpts);
    return {
      ctx: {
        tiers: null,

        consentGroups: [],

        consentGroup: {caption: "", members: []},

        consentGroupCodes: [],

        consentGroupSelection: {},

        consentGroupIndex: null,

        tier: null,

        deleteOpts: null,

        cp: null,

        hasEc: !!this.$osSvc.ecDocSvc,

        hasDocs: this.$osSvc.ecDocSvc ? null : false
      },

      addEditFs: consentSchema.layout,

      consentCpFs: consentCpSchema.layout,

      cpResources,

      editAllowed
    }
  },

  created() {
    this._loadConsentTiers();
    this._loadConsentGroups();
  },

  computed: {
    consentsSourceUrl: function() {
      return routerSvc.getUrl('CpDetail.Consents', {cpId: this.cp.consentsSource.id});
    },

    noDocsAndTiers: function() {
      if (this.ctx.hasDocs == false && this.ctx.tiers && this.ctx.tiers.length == 0) {
        return true;
      }

      return false; // might be loading
    }
  },

  methods: {
    showAddEditConsentTierDialog: function(tier) {
      this.ctx.tier = util.clone(tier);
      this.$refs.addEditConsentTierDialog.open();
    },

    hideAddEditConsentTierDialog: function() {
      this.ctx.tier = null;
      this.$refs.addEditConsentTierDialog.close();
    },

    saveOrUpdate: function() {
      if (!this.$refs.addEditConsentTier.validate()) {
        return;
      }

      const {id, statementCode} = this.ctx.tier;
      const toSave = {id, statementCode, cpId: this.cp.id};
      cpSvc.saveOrUpdateConsentTier(toSave).then(
        (saved) => {
          alertsSvc.success({code: 'cps.consent_tier_saved', args: saved});
          this.hideAddEditConsentTierDialog();
          this._loadConsentTiers();
        }
      );
    },

    showConsentGroupDialog: function(group, index) {
      const source = util.clone(group || {caption: "", members: []});
      source.members = (source.members || []).map(member => ({
        statementCode: member.statementCode,
        disabledWhen: [...(member.disabledWhen || [])],
        visibleWhen: [...(member.visibleWhen || [])]
      }));
      this.ctx.consentGroup = source;
      this.ctx.consentGroupCodes = source.members.map(member => member.statementCode);
      this.ctx.consentGroupSelection = Object.fromEntries((this.ctx.tiers || []).map(tier => [
        tier.statementCode, this.ctx.consentGroupCodes.includes(tier.statementCode)
      ]));
      this.ctx.consentGroupIndex = index == null ? null : index;
      this.$refs.consentGroupDialog.open();
    },

    hideConsentGroupDialog: function() {
      this.$refs.consentGroupDialog.close();
    },

    syncConsentGroupMembers: function() {
      this.ctx.consentGroupCodes = (this.ctx.tiers || []).filter(tier =>
        this.ctx.consentGroupSelection[tier.statementCode]
      ).map(tier => tier.statementCode);

      const membersByCode = Object.fromEntries(this.ctx.consentGroup.members.map(member => [member.statementCode, member]));
      this.ctx.consentGroup.members = this.ctx.consentGroupCodes.map(code => membersByCode[code] || {
        statementCode: code, disabledWhen: [], visibleWhen: []
      });
    },

    conditionSelected: function(member, condition, statementCode) {
      return (member[condition] || []).includes(statementCode);
    },

    toggleCondition: function(member, condition, statementCode, selected) {
      const values = new Set(member[condition] || []);
      if (selected) {
        values.add(statementCode);
      } else {
        values.delete(statementCode);
      }

      member[condition] = [...values];
    },

    otherMembers: function(statementCode) {
      return this.ctx.consentGroup.members.filter(member => member.statementCode !== statementCode);
    },

    statementFor: function(statementCode) {
      const tier = (this.ctx.tiers || []).find(item => item.statementCode === statementCode);
      return tier ? tier.statement + " (" + tier.statementCode + ")" : statementCode;
    },

    saveConsentGroup: function() {
      const group = util.clone(this.ctx.consentGroup);
      if (!group.caption || !group.members || group.members.length == 0) {
        return;
      }

      const selected = new Set(group.members.map(member => member.statementCode));
      group.members = group.members.map(member => ({
        statementCode: member.statementCode,
        disabledWhen: [...new Set((member.disabledWhen || []).filter(code => code !== member.statementCode && selected.has(code)))],
        visibleWhen: [...new Set((member.visibleWhen || []).filter(code => code !== member.statementCode && selected.has(code)))]
      }));

      const groups = util.clone(this.ctx.consentGroups);
      if (this.ctx.consentGroupIndex == null) {
        groups.push(group);
      } else {
        groups[this.ctx.consentGroupIndex] = group;
      }

      cpSvc.saveWorkflow(this.cp.id, "consentGroups", {version: 1, groups}).then(saved => {
        this.ctx.consentGroups = this._normaliseConsentGroups(saved);
        this.hideConsentGroupDialog();
        alertsSvc.success({code: "cps.consent_groups_saved"});
      });
    },

    deleteConsentGroup: function(index) {
      const groups = util.clone(this.ctx.consentGroups);
      groups.splice(index, 1);
      cpSvc.saveWorkflow(this.cp.id, "consentGroups", {version: 1, groups}).then(saved => {
        this.ctx.consentGroups = this._normaliseConsentGroups(saved);
        alertsSvc.success({code: "cps.consent_groups_saved"});
      });
    },

    deleteTier: function(tier) {
      if (this.ctx.consentGroups.some(group => group.members.some(member => member.statementCode === tier.statementCode))) {
        alertsSvc.error({code: "cps.consent_group_member_in_use"});
        return;
      }

      this.ctx.tier = tier;
      tier.cpId = this.cp.id;
      this.ctx.deleteOpts = {
        type: this.$t('cps.consent_tier'),
        title: tier.statementCode,
        dependents: () => cpSvc.getConsentTierDependents(tier),
        forceDelete: false,
          askReason: false,
          deleteObj: () => cpSvc.deleteConsentTier(tier)
      };

      setTimeout(
        async () => {
          const resp = await this.$refs.deleteConsentTierDialog.execute();
          if (resp == 'deleted') {
            this._loadConsentTiers();
          }
        }
      );
    },

    waiveConsents: function() {
      cpSvc.waiveConsents(this.cp.id).then(savedCp => this.$emit('cp-saved', savedCp));
    },

    undoWaiveConsents: function() {
      cpSvc.undoWaiveConsents(this.cp.id).then(savedCp => this.$emit('cp-saved', savedCp));
    },

    setSpecimenConsentsEnabled: function(enabled) {
      cpSvc.setSpecimenConsentsEnabled(this.cp.id, enabled).then(savedCp => this.$emit('cp-saved', savedCp));
    },

    showSelectCpDialog: function() {
      this.ctx.cp = null;
      this.$refs.selectCpDialog.open();
    },

    hideSelectCpDialog: function() {
      this.$refs.selectCpDialog.close();
    },

    setConsentsCp: function() {
      if (!this.$refs.selectCpForm.validate()) {
        return;
      }

      this._setConsentsCp(this.cp.id, this.ctx.cp);
    },

    unsetConsentsCp: function() {
      this._setConsentsCp(this.cp.id, {});
    },

    _loadConsentTiers: function() {
      cpSvc.getConsentTiers(this.cp.id).then(tiers => this.ctx.tiers = tiers);
    },

    _loadConsentGroups: function() {
      cpSvc.loadWorkflows(this.cp.id, "consentGroups").then(workflow => workflow && workflow.data).then(config => {
        this.ctx.consentGroups = this._normaliseConsentGroups(config);
      });
    },

    _normaliseConsentGroups: function(config) {
      return ((config && config.groups) || []).map(group => ({
        caption: group.caption,
        members: (group.members || []).map(member => ({
          statementCode: member.statementCode,
          disabledWhen: member.disabledWhen || [],
          visibleWhen: member.visibleWhen || []
        }))
      }));
    },

    _setConsentsCp: function(cpId, sourceCp) {
      cpSvc.setConsentsCp(cpId, sourceCp).then(
        savedCp => {
          this.$emit('cp-saved', savedCp);
          this._loadConsentTiers();
          this.hideSelectCpDialog();
        }
      );
    }
  }
}
</script>

<style scoped>
.os-consents {
  height: 100%;
  padding: 0.5rem 1rem;
}

.os-consents :deep(.os-card) {
  margin-bottom: 1rem;
}

.os-consent-tier {
  display: flex;
  align-items: center;
}

.os-consent-tier .statement {
  flex: 1;
}

.specimen-consents-setting {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

.consent-group-editor {
  min-width: 44rem;
}

.consent-group-editor .field {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.consent-group-editor .group-caption {
  width: 100%;
  height: 2.4rem;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--surface-border);
  border-radius: 0.25rem;
  font: inherit;
}

.statement-selector {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.75rem 1rem;
  padding: 0.75rem;
  border: 1px solid var(--surface-border);
  border-radius: 0.375rem;
  background: var(--surface-ground);
}

.consent-group-member {
  padding: 1rem;
  margin-top: 0.75rem;
  border: 1px solid var(--surface-border);
  border-radius: 0.375rem;
}

.condition-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1rem;
  margin-top: 0.75rem;
}

.condition-label {
  display: block;
  margin-bottom: 0.5rem;
  font-size: 0.875rem;
  font-weight: 600;
}

@media (max-width: 52rem) {
  .consent-group-editor {
    min-width: 0;
  }

  .statement-selector,
  .condition-grid {
    grid-template-columns: 1fr;
  }
}
</style>
