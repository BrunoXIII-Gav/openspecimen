<template>
  <div class="os-specimen-consents">
    <os-page-toolbar v-if="!editMode && isUpdateAllowed">
      <template #default>
        <os-button left-icon="edit" :label="$t('participant_consents.edit_responses')" @click="editResponses" />
        <os-button left-icon="upload" :label="$t('participant_consents.upload_new_form')" @click="showUploadDoc" />
      </template>
    </os-page-toolbar>

    <os-grid v-if="!editMode">
      <os-grid-column width="12">
        <os-section>
          <template #title>
            <span v-t="'participant_consents.signed_consent_form'">Signed consent form</span>
          </template>
          <template #content>
            <div class="signed-form" v-if="consent.documents && consent.documents.length">
              <div v-for="document in consent.documents" :key="document.id" class="document">
                <a :href="documentUrl(document)" target="_blank">{{document.fileName}}</a>
                <os-button v-if="isUpdateAllowed" size="small" text left-icon="trash" @click="deleteDocument(document)" />
              </div>
            </div>
            <span v-else>-</span>
          </template>
        </os-section>

        <os-section>
          <template #title>
            <span v-t="'participant_consents.responses'">Responses</span>
          </template>
          <template #content>
            <div class="responses">
              <div class="response" v-for="response in consent.responses" :key="response.code">
                <div class="statement">
                  <span>{{response.statement}}</span>
                  <span v-if="response.code">&nbsp; ({{response.code}})</span>
                </div>
                <div class="answer">{{responseCaption(response.response) || '-'}}</div>
              </div>
            </div>
          </template>
        </os-section>
      </os-grid-column>
    </os-grid>

    <os-form v-else ref="editForm" :schema="formSchema" :data="{consent: editing}">
      <os-button primary :label="$t('common.buttons.update')" @click="updateResponses" />
      <os-button text :label="$t('common.buttons.cancel')" @click="cancelEditResponses" />
    </os-form>

    <os-dialog ref="uploadDocDialog">
      <template #header>
        <span v-t="'participant_consents.upload_consent_form'">Upload consent form</span>
      </template>
      <template #content>
        <os-label><span v-t="'participant_consents.choose_signed_consent_form'">Choose signed consent form</span></os-label>
        <os-file-upload ref="consentDocUploader" :url="documentsUrl" :auto="false" :headers="reqHeaders" />
      </template>
      <template #footer>
        <os-button text :label="$t('common.buttons.cancel')" @click="$refs.uploadDocDialog.close()" />
        <os-button primary :label="$t('common.buttons.upload')" @click="uploadDocument" />
      </template>
    </os-dialog>
  </div>
</template>

<script>
import alertsSvc from '@/common/services/Alerts.js';
import cpSvc from '@/biospecimen/services/CollectionProtocol.js';
import http from '@/common/services/HttpClient.js';
import pvSvc from '@/common/services/PermissibleValue.js';
import spmnSvc from '@/biospecimen/services/Specimen.js';
import util from '@/common/services/Util.js';

export default {
  props: ['cpr', 'visit', 'specimen'],

  inject: ['cpViewCtx'],

  data() {
    return {consent: {responses: [], documents: []}, tiers: [], responsePvs: [], editMode: false, editing: {responses: []}};
  },

  async created() {
    await Promise.all([this.loadConsent(), this.loadTiers(), this.loadResponsePvs()]);
    if (!this.consent.id) {
      this.consent = this.newConsent();
    }
  },

  computed: {
    reqHeaders: function() { return http.headers; },
    isUpdateAllowed: function() {
      return this.specimen.lineage == 'New' ? this.cpViewCtx.isUpdateSpecimenAllowed(this.cpr) : this.cpViewCtx.isUpdateAllSpecimenAllowed(this.cpr);
    },
    documentsUrl: function() {
      return this.consent.id ? spmnSvc.getConsentDocumentsUrl(this.specimen, this.consent) : null;
    },
    formSchema: function() {
      const fields = [
        {name: 'consent.consentVersion', type: 'text', labelCode: 'specimen_consents.version'},
        {name: 'consent.consentSignatureDate', type: 'datePicker', labelCode: 'participant_consents.sign_date'},
        {name: 'consent.witness', type: 'user', labelCode: 'participant_consents.witness'},
        {name: 'consent.comments', type: 'textarea', labelCode: 'participant_consents.comments'}
      ];

      for (const tier of this.tiers) {
        const index = fields.length;
        fields.push({
          name: 'consent.responses.' + (index - 4) + '.response',
          type: 'radio',
          label: tier.statement + (tier.statementCode ? ' (' + tier.statementCode + ')' : ''),
          options: this.responsePvs,
          optionsPerRow: Math.min(this.responsePvs.length, 5),
          clearOption: true
        });
      }

      return {rows: fields.map(field => ({fields: [field]}))};
    }
  },

  methods: {
    async loadConsent() {
      const consents = await spmnSvc.getConsents(this.specimen);
      this.consent = consents.length > 0 ? consents[0] : {responses: [], documents: []};
    },
    async loadTiers() { this.tiers = await cpSvc.getConsentTiers(this.cpr.cpId); },
    async loadResponsePvs() {
      const pvs = await pvSvc.getPvs('consent_response');
      this.responsePvs = pvs.map(({value}) => ({caption: this.responseCaption(value), value}));
    },
    newConsent() {
      return {
        consentType: this.$t('specimen_consents.default_type'),
        status: 'Complete',
        documents: [],
        responses: this.tiers.map(({statementCode, statement}) => ({code: statementCode, statement, response: null}))
      };
    },
    editResponses() {
      this.editing = util.clone(this.consent);
      this.editMode = true;
    },
    async updateResponses() {
      if (!this.$refs.editForm.validate()) return;
      this.consent = await spmnSvc.saveConsent(this.specimen, this.editing);
      this.editMode = false;
      alertsSvc.success({code: 'participant_consents.consents_updated'});
      await this.loadConsent();
    },
    cancelEditResponses() {
      this.editing = {responses: []};
      this.editMode = false;
    },
    async showUploadDoc() {
      if (!this.consent.id) {
        this.consent = await spmnSvc.saveConsent(this.specimen, this.consent);
      }

      this.$refs.uploadDocDialog.open();
    },
    async uploadDocument() {
      await this.$refs.consentDocUploader.upload();
      await this.loadConsent();
      this.$refs.uploadDocDialog.close();
      alertsSvc.success({code: 'participant_consents.signed_form_uploaded'});
    },
    async deleteDocument(document) {
      await spmnSvc.deleteConsentDocument(this.specimen, this.consent, document);
      await this.loadConsent();
    },
    documentUrl(document) { return spmnSvc.getConsentDocumentUrl(this.specimen, this.consent, document); },
    responseCaption(response) {
      const responseMap = {Yes: 'yes', No: 'no', None: 'none', 'Not Specified': 'not_specified', Withdrawn: 'withdrawn'};
      const key = responseMap[response];
      return key ? this.$t('pvs.consent_response.' + key) : response;
    }
  }
}
</script>

<style scoped>
.responses { margin-top: 1rem; }
.response { padding: 1rem; margin-bottom: 1rem; border-radius: .5rem; box-shadow: 0 1px 2px 0 rgba(60, 64, 67, .3), 0 2px 6px 2px rgba(60, 64, 67, .15); }
.statement { font-style: italic; font-weight: 600; color: #777; }
.answer { margin-top: .75rem; }
.document { display: flex; align-items: center; gap: .5rem; margin-bottom: .5rem; }
</style>
