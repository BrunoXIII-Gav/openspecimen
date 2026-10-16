<template>
  <os-page>
    <os-page-head>
      <h3 v-t="'audit.reports.title'"></h3>
    </os-page-head>

    <os-page-body>
      <div class="audit-report-page">
        <os-message type="error" v-if="!isAdmin">
          <span v-t="'audit.reports.admin_only'"></span>
        </os-message>

        <template v-else>
          <p class="description" v-t="'audit.reports.description'"></p>
          <os-message type="warn" v-if="auditDisabled">
            <span v-t="'audit.reports.disabled'"></span>
          </os-message>
          <os-message type="error" v-if="loadError">
            <span v-t="'audit.reports.load_error'"></span>
            <os-button text :label="$t('audit.reports.retry')" @click="loadOptions" />
          </os-message>
          <os-message type="info" v-else-if="loading">
            <span v-t="'common.loading'"></span>
          </os-message>

          <div class="audit-workspace" v-else>
          <form class="report-form" @submit.prevent="searchRecords" @input="onFiltersChange">
            <fieldset :disabled="busy">
              <div class="field">
                <label for="audit-report-type">{{$t('audit.reports.report_type')}}</label>
                <Dropdown input-id="audit-report-type" v-model="form.reportType" :options="reportTypes"
                  option-label="label" option-value="value" :disabled="busy" @change="onFiltersChange" />
              </div>

              <template v-if="form.reportType === 'data'">
                <div class="field">
                  <label for="audit-scope">{{$t('audit.reports.scope')}}</label>
                  <Dropdown input-id="audit-scope" v-model="form.scope" :options="scopes"
                    option-label="label" option-value="value" :disabled="busy" @change="onScopeChange" />
                </div>
                <div class="field" v-if="form.scope === 'core'">
                  <label for="audit-entity">{{$t('audit.reports.entity')}}</label>
                  <Dropdown input-id="audit-entity" v-model="form.entity" :options="entities" filter show-clear
                    option-label="value" option-value="name" :placeholder="$t('audit.reports.all_entities')"
                    :disabled="busy" @change="onEntityChange" />
                  <small v-t="'audit.reports.entity_help'"></small>
                </div>
                <div class="field" v-else>
                  <label for="audit-form-name">{{$t('audit.reports.form_name')}}</label>
                  <InputText id="audit-form-name" v-model="form.formName" :disabled="busy" />
                </div>
                <div class="field" v-if="form.scope !== 'form'">
                  <label for="audit-record-ids">{{$t('audit.reports.record_ids')}}</label>
                  <InputText id="audit-record-ids" v-model="form.recordIds" :disabled="busy"
                    placeholder="11, 12" aria-describedby="audit-record-ids-help" />
                  <small id="audit-record-ids-help" v-t="'audit.reports.ids_help'"></small>
                </div>
              </template>

              <div class="dates">
                <div class="field">
                  <label for="audit-start-date">{{$t('audit.reports.start_date')}}</label>
                  <input id="audit-start-date" type="date" v-model="form.startDate" :max="today" required />
                </div>
                <div class="field">
                  <label for="audit-end-date">{{$t('audit.reports.end_date')}}</label>
                  <input id="audit-end-date" type="date" v-model="form.endDate" :max="today" required />
                </div>
              </div>
              <p class="help">{{$t('audit.reports.period_help', {days: maxPeriod})}}</p>

              <div class="field" role="group" aria-labelledby="audit-user-label">
                <span id="audit-user-label">{{$t('audit.reports.user')}}</span>
                <os-user-dropdown v-model="form.user" :disabled="busy" @update:model-value="onFiltersChange" />
                <small v-t="'audit.reports.user_help'"></small>
              </div>

              <os-message type="error" v-if="validationError">
                <span role="alert">{{$t('audit.reports.' + validationError, {days: maxPeriod})}}</span>
              </os-message>

              <div class="actions">
                <button v-if="form.reportType === 'data'" class="query-button" type="submit" :disabled="busy">
                  <os-icon :name="searching ? 'spinner' : 'search'" />
                  <span>{{$t(searching ? 'audit.reports.searching' : 'audit.reports.search')}}</span>
                </button>
                <button class="export-button" type="button" :disabled="busy" @click="exportReport">
                  <os-icon :name="status === 'generating' ? 'spinner' : 'download'" />
                  <span>{{$t(status === 'generating' ? 'audit.reports.generating' : 'audit.reports.export')}}</span>
                </button>
              </div>
            </fieldset>
          </form>

          <section class="audit-results" :aria-label="$t('audit.reports.results')" :aria-busy="searching">
            <h4 v-t="'audit.reports.results'"></h4>
            <os-message type="info" v-if="form.reportType !== 'data'">
              <span v-t="'audit.reports.export_activity_help'"></span>
            </os-message>
            <template v-else>
              <os-message type="info" v-if="searching">
                <span role="status" v-t="'audit.reports.searching'"></span>
              </os-message>
              <os-message type="error" v-if="searchError">
                <span role="alert" v-t="'audit.reports.search_failed'"></span>
              </os-message>
              <p class="help" v-if="!hasSearched && !searching && !searchError" v-t="'audit.reports.search_help'"></p>
              <os-message type="info" v-if="hasSearched && currentPage.records.length === 0 && !searching">
                <span v-t="'audit.reports.no_results'"></span>
              </os-message>
              <template v-if="currentPage.records.length">
                <div class="results-table-wrapper">
                  <table class="os-table audit-table">
                    <thead><tr>
                      <th scope="col" v-t="'audit.reports.date'"></th>
                      <th scope="col" v-t="'audit.reports.actor'"></th>
                      <th scope="col" v-t="'audit.reports.operation'"></th>
                      <th scope="col" v-t="'audit.reports.record'"></th>
                      <th scope="col" v-t="'audit.reports.changes'"></th>
                    </tr></thead>
                    <tbody>
                      <tr v-for="row in currentPage.records" :key="row.id">
                        <td>{{$filters.dateTime(row.changedOn)}}</td>
                        <td>{{actorLabel(row.changedBy)}}</td>
                        <td>{{operationLabel(row.operation)}}</td>
                        <td>{{recordLabel(row.recordType)}}<br><strong>#{{row.recordId}}</strong></td>
                        <td>
                          <div class="change-preview">{{row.changeLog || $t('audit.reports.no_changes')}}</div>
                          <button class="detail-button" type="button" @click="showDetails(row)">
                            {{$t('audit.reports.view_details')}}
                          </button>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
                <nav class="pagination" :aria-label="$t('audit.reports.pages')">
                  <button type="button" :disabled="busy || pageIndex === 0" @click="previousPage">
                    {{$t('audit.reports.previous')}}
                  </button>
                  <span>{{$t('audit.reports.page_number', {page: pageIndex + 1})}}</span>
                  <button type="button" :disabled="busy || !currentPage.hasMore" @click="nextPage">
                    {{$t('audit.reports.next')}}
                  </button>
                </nav>
              </template>
            </template>
          </section>
          </div>

          <div role="status" aria-live="polite">
            <os-message type="info" v-if="status === 'generating'">
              <span v-t="'audit.reports.generating_help'"></span>
            </os-message>
            <os-message type="info" v-if="status === 'queued'">
              <span v-t="'audit.reports.queued'"></span>
            </os-message>
            <os-message type="success" v-if="status === 'downloaded'">
              <span v-t="'audit.reports.downloaded'"></span>
            </os-message>
            <os-message type="error" v-if="status === 'failed'">
              <span v-t="'audit.reports.failed'"></span>
            </os-message>
            <os-message type="error" v-if="status === 'download_failed'">
              <span v-t="'audit.reports.download_failed'"></span>
            </os-message>
            <os-button v-if="fileId" left-icon="download" :label="$t('audit.reports.download_again')"
              :disabled="busy" @click="downloadReport" />
          </div>
        </template>
      </div>
    </os-page-body>
  </os-page>

  <os-dialog ref="detailsDialog" size="lg">
    <template #header><span v-t="'audit.reports.detail_title'"></span></template>
    <template #content>
      <template v-if="selectedRecord">
        <dl class="record-summary">
          <dt>{{$t('audit.reports.date')}}</dt><dd>{{$filters.dateTime(selectedRecord.changedOn)}}</dd>
          <dt>{{$t('audit.reports.actor')}}</dt><dd>{{actorLabel(selectedRecord.changedBy)}}</dd>
          <dt>{{$t('audit.reports.operation')}}</dt><dd>{{operationLabel(selectedRecord.operation)}}</dd>
          <dt>{{$t('audit.reports.record')}}</dt><dd>{{recordLabel(selectedRecord.recordType)}} #{{selectedRecord.recordId}}</dd>
          <dt>{{$t('audit.reports.revision')}}</dt><dd>{{selectedRecord.revisionId}}</dd>
          <dt>{{$t('audit.reports.ip_address')}}</dt><dd>{{selectedRecord.ipAddress || '—'}}</dd>
        </dl>
        <h4 v-t="'audit.reports.changes'"></h4>
        <p class="help" v-t="'audit.reports.values_help'"></p>
        <pre class="change-log">{{selectedRecord.changeLog || $t('audit.reports.no_changes')}}</pre>
      </template>
    </template>
    <template #footer>
      <os-button :label="$t('common.buttons.done')" @click="$refs.detailsDialog.close()" />
    </template>
  </os-dialog>
</template>

<script>
import Dropdown from 'primevue/dropdown';
import InputText from 'primevue/inputtext';

import auditSvc from '@/common/services/AuditLogs.js';
import settingSvc from '@/common/services/Setting.js';
import {buildReportCriteria} from './report.js';

function localDate(date) {
  return [date.getFullYear(), String(date.getMonth() + 1).padStart(2, '0'),
    String(date.getDate()).padStart(2, '0')].join('-');
}

export default {
  components: {Dropdown, InputText},

  data() {
    const start = new Date();
    start.setDate(start.getDate() - 30);
    return {
      form: {reportType: 'data', scope: 'core', entity: null, formName: '', recordIds: '', user: null,
        startDate: localDate(start), endDate: localDate(new Date())},
      entities: [], maxPeriod: 90, loading: true, loadError: false, busy: false,
      validationError: null, status: null, fileId: null,
      pages: [], pageIndex: 0, searching: false, searchError: false, hasSearched: false,
      queryCriteria: null, selectedRecord: null
    };
  },

  computed: {
    isAdmin() { return this.$ui.currentUser?.admin === true; },

    auditDisabled() {
      const enabled = this.$ui.global.appProps.auditEnabled;
      return enabled === false || enabled === 'false';
    },

    today() { return localDate(new Date()); },

    currentPage() { return this.pages[this.pageIndex] || {records: [], hasMore: false}; },

    scopes() {
      return ['core', 'form', 'form_data'].map(value =>
        ({value, label: this.$t('audit.reports.scope_' + value)}));
    },

    reportTypes() {
      return ['data', 'query_exim', 'auth', 'api_calls'].map(value =>
        ({value, label: this.$t('audit.reports.type_' + value)}));
    }
  },

  created() {
    if (this.isAdmin) this.loadOptions();
  },

  methods: {
    async loadOptions() {
      this.loading = true;
      this.loadError = false;
      try {
        const [entities, settings] = await Promise.all([
          auditSvc.getEntities(), settingSvc.getSetting('common', 'max_audit_report_period')
        ]);
        this.entities = entities;
        this.maxPeriod = Number(settings[0]?.value) > 0 ? Number(settings[0].value) : 90;
        const start = new Date();
        start.setDate(start.getDate() - Math.min(30, this.maxPeriod));
        this.form.startDate = localDate(start);
        const {entity, recordId} = this.$route.query;
        if (entity && entities.some(e => e.name === entity)) {
          this.form.entity = entity;
          this.form.recordIds = typeof recordId === 'string' ? recordId : '';
        }
      } catch {
        this.loadError = true;
      } finally {
        this.loading = false;
      }
    },

    clearResult() {
      this.validationError = null;
      this.status = null;
      this.fileId = null;
    },

    onFiltersChange() {
      this.clearResult();
      this.pages = [];
      this.pageIndex = 0;
      this.hasSearched = false;
      this.searchError = false;
      this.queryCriteria = null;
    },

    onScopeChange() {
      this.form.entity = null;
      this.form.formName = '';
      this.form.recordIds = '';
      this.onFiltersChange();
    },

    onEntityChange() {
      this.form.recordIds = '';
      this.onFiltersChange();
    },

    getCriteria() {
      // Date inputs represent local calendar days, rather than UTC midnight.
      const dates = {startDate: this.form.startDate && this.form.startDate + 'T00:00:00',
        endDate: this.form.endDate && this.form.endDate + 'T00:00:00'};
      const {criteria, error} = buildReportCriteria({...this.form, ...dates}, this.maxPeriod);
      if (error) {
        this.validationError = error;
        return null;
      }
      return criteria;
    },

    async searchRecords() {
      if (this.busy || !this.isAdmin || this.form.reportType !== 'data') return;
      const criteria = this.getCriteria();
      if (!criteria) return;
      this.pages = [];
      this.pageIndex = 0;
      this.hasSearched = false;
      this.queryCriteria = criteria;
      await this.fetchPage(null);
    },

    async fetchPage(cursor) {
      this.busy = this.searching = true;
      this.searchError = false;
      try {
        const page = await auditSvc.searchRecords({...this.queryCriteria, maxResults: 25, lastId: cursor});
        this.pages.push(page);
        this.pageIndex = this.pages.length - 1;
        this.hasSearched = true;
      } catch {
        this.searchError = true;
      } finally {
        this.busy = this.searching = false;
      }
    },

    previousPage() { if (!this.busy && this.pageIndex > 0) --this.pageIndex; },

    async nextPage() {
      if (this.busy || !this.currentPage.hasMore) return;
      if (this.pageIndex + 1 < this.pages.length) ++this.pageIndex;
      else await this.fetchPage(this.currentPage.nextCursor);
    },

    recordLabel(type) {
      return this.entities.find(e => e.name === type)?.value || type || '—';
    },

    actorLabel(user) {
      return user && (user.firstName || user.lastName || user.loginName) ? this.$filters.username(user) : '—';
    },

    operationLabel(operation) {
      const key = 'audit.reports.op_' + operation;
      return this.$te(key) ? this.$t(key) : operation;
    },

    showDetails(row) {
      this.selectedRecord = row;
      this.$refs.detailsDialog.open();
    },

    async exportReport() {
      if (this.busy || !this.isAdmin) return;
      this.clearResult();
      const criteria = this.getCriteria();
      if (!criteria) return;

      this.busy = true;
      this.status = 'generating';
      try {
        const {fileId} = await auditSvc.exportReport(criteria);
        this.fileId = fileId;
        if (fileId) {
          await this.downloadReport();
        } else {
          this.status = 'queued';
        }
      } catch {
        this.status = 'failed';
      } finally {
        this.busy = false;
      }
    },

    async downloadReport() {
      if (!this.fileId) return;
      this.busy = true;
      try {
        await auditSvc.downloadReport(this.fileId);
        this.status = 'downloaded';
      } catch {
        this.status = 'download_failed';
      } finally {
        this.busy = false;
      }
    }
  }
};
</script>

<style scoped>
.audit-report-page { padding-bottom: 2rem; }
.audit-workspace { display: grid; grid-template-columns: minmax(300px, 380px) minmax(0, 1fr); gap: 2rem; }
.description { margin: 0.5rem 0 1.5rem; }
.report-form { max-width: 650px; }
fieldset { border: 0; padding: 0; margin: 0; min-width: 0; }
.field { display: flex; flex-direction: column; gap: 0.4rem; margin-bottom: 1.1rem; }
.field label, #audit-user-label { font-weight: 600; }
.field small, .help { color: #666; }
.dates { display: flex; gap: 1rem; }
.dates .field { flex: 1; min-width: 0; }
input[type=date] { padding: 0.5rem; border: 1px solid #ced4da; border-radius: 4px; background: white; }
.actions { margin: 1rem 0; display: flex; gap: 0.5rem; flex-wrap: wrap; }
.export-button, .query-button { display: inline-flex; align-items: center; gap: 0.5rem; background: #67271a;
  color: white; border: 0; border-radius: 1.125rem; padding: 0.5rem 1rem; cursor: pointer; }
.export-button { background: #f5f5f5; color: #444; border: 1px solid #ccc; }
button:disabled { opacity: 0.65; cursor: default; }
button:focus-visible { outline: 2px solid #007bff; outline-offset: 3px; }
.results-table-wrapper { overflow-x: auto; }
.audit-table { min-width: 680px; }
.audit-table th, .audit-table td { padding: 0.75rem; }
.change-preview { white-space: pre-wrap; overflow-wrap: anywhere; display: -webkit-box;
  -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; max-width: 450px; }
.detail-button { border: 0; background: transparent; padding: 0.25rem 0; color: #337ab7; cursor: pointer; }
.pagination { display: flex; align-items: center; gap: 1rem; margin: 1rem 0; }
.pagination button { padding: 0.35rem 0.75rem; border: 1px solid #ccc; border-radius: 1rem; background: #f5f5f5; cursor: pointer; }
.record-summary { display: grid; grid-template-columns: minmax(90px, auto) minmax(0, 1fr); gap: 0.5rem 1rem; }
.record-summary dd { margin: 0; overflow-wrap: anywhere; }
.change-log { white-space: pre-wrap; overflow-wrap: anywhere; padding: 1rem; background: #f5f5f5; max-height: 50vh; overflow-y: auto; }
@media (max-width: 1000px) { .audit-workspace { grid-template-columns: 1fr; } }
@media (max-width: 550px) { .dates { flex-direction: column; gap: 0; } }
</style>
