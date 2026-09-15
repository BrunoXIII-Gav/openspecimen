<template>
  <os-page>
    <os-page-head>
      <template #breadcrumb>
        <os-breadcrumb :items="ctx.bcrumb" />
      </template>

      <span class="os-title">
        <h3>
          <span v-if="query.title">{{query.title}}</span>
          <span v-else v-t="'queries.unsaved_query'">Unsaved Query</span>
        </h3>
      </span>

      <template #right>
        <div v-if="ctx.loadingCounters">
          <span v-t="'common.loading'">Loading</span>
        </div>
        <div class="counters" v-else-if="ctx.counters">
          <os-card class="counter">
            <template #body>
              <span>
                <os-icon name="users" />
                <span>{{ctx.counters.cprs}}</span>
              </span>
            </template>
          </os-card>
          <os-card class="counter">
            <template #body>
              <span>
                <os-icon name="calendar" />
                <span>{{ctx.counters.visits}}</span>
              </span>
            </template>
          </os-card>
          <os-card class="counter">
            <template #body>
              <span>
                <os-icon name="flask" />
                <span>{{ctx.counters.specimens}}</span>
              </span>
            </template>
          </os-card>
        </div>
      </template>
    </os-page-head>

    <os-page-body>
      <os-page-toolbar>
        <template #default>
          <os-menu :label="$t('queries.actions')" :options="actionsMenuOpts" />

          <span v-if="ctx.showAddSpecimens && ctx.selectedRows.length > 0">
            <os-specimen-actions label="queries.specimen_actions"
              :specimens="selectedSpecimens" @reloadSpecimens="rerun" @route-change="saveQueryLocally($event)" />

            <os-add-to-cart :specimens="selectedSpecimens" />
          </span>

          <os-plugin-views page="query-results" view="toolbar" :view-props="{query}" />

          <os-button-link left-icon="question-circle" :label="$t('common.buttons.help')"
            url="https://openspecimen.atlassian.net/l/cp/WNtmFmh3" :new-tab="true" />
        </template>

        <template #right v-if="selectedSpecimens.length > 0">
          <os-message class="selected-rows-msg" type="info">
            <span v-t="{path: 'queries.specimens_selected', args: {count: selectedSpecimens.length}}" />
          </os-message>
        </template>
      </os-page-toolbar>

      <os-grid v-if="query.selectList && query.selectList.length > 0">
        <os-grid-column :width="3" v-show="ctx.hasFacets">
          <Facets ref="facetsList" :query="query" @facets-loaded="onFacetsLoad" @facets-selected="onFacetsSelection" />
        </os-grid-column>

        <os-grid-column class="results-panel" :width="ctx.hasFacets ? 9 : 12" :style="{'--ag-spacing': '6px'}">
          <os-message type="info" v-if="ctx.loadingRecords">
            <span v-t="'queries.loading_records'">Loading records...</span>
          </os-message>

          <os-message type="warn" v-if="!ctx.loadingRecords && !pagingEnabled && ctx.dbHasMoreRecords">
            <span v-t="'queries.export_to_get_all'"></span>
            <a href="https://openspecimen.atlassian.net/wiki/x/ogYR" target="_blank">
              <span>&nbsp;</span>
              <span v-t="'queries.click_export_has_more_records'"></span>
            </a>
          </os-message>

          <AgGridVue class="results-grid" :theme="theme"
            :row-data="ctx.records" :column-defs="ctx.columns" :suppressFieldDotNotation="true"
            :rowSelection="rowSelection" :pinnedBottomRowData="ctx.footerRow" :enableCellTextSelection="true"
            :tooltipShowMode="'whenTruncated'" :tooltipShowDelay="500"
            @gridReady="onGridReady" @selectionChanged="onRowSelection" @sortChanged="onSortChanged"
            v-if="!ctx.loadingRecords" />

          <div class="query-pager" v-if="pagingEnabled && (ctx.startAt > 0 || ctx.haveMoreRecords)">
            <span>{{$t('queries.page', {page: ctx.pageNo + 1})}}</span>
            <os-pager :start-at="ctx.startAt" :have-more="ctx.haveMoreRecords"
              @previous="previousPage" @next="nextPage" />
          </div>
        </os-grid-column>
      </os-grid>
      <div v-else>
        <os-message type="info">
          <span v-t="'queries.select_columns'">Select one or more columns to show the records...</span>
        </os-message>
      </div>

      <os-column-url />
    </os-page-body>
  </os-page>

  <SaveQuery ref="saveQueryDialog" />

  <DefineView ref="defineViewDialog" />
</template>

<script>
import { themeQuartz } from 'ag-grid-community';
import { AgGridVue }   from 'ag-grid-vue3'

import alertsSvc from '@/common/services/Alerts.js';
import authSvc   from '@/common/services/Authorization.js';
import i18n      from '@/common/services/I18n.js';
import querySvc  from '@/queries/services/Query.js';
import routerSvc from '@/common/services/Router.js';
import savedQuerySvc from '@/queries/services/SavedQuery.js';

import queryResources from './Resources.js';

import ColumnUrl from './ColumnUrl.vue';
import DefineView from './DefineView.vue';
import Facets from './Facets.vue';
import SaveQuery from './SaveQuery.vue';

const PAGE_SIZE = 100;

export default {
  props: ['query'],

  emits: ['query-saved'],

  components: {
    AgGridVue,

    'os-column-url': ColumnUrl,

    Facets,

    DefineView,

    SaveQuery
  },

  data() {
    return {
      theme: themeQuartz,

      ctx: {
        bcrumb: [
          {url: routerSvc.getUrl('QueriesList', {}), label: i18n.msg('queries.list')}
        ],

        loadingCounters: false,

        counters: null,

        loadingRecords: false,

        columns: null,

        records: null,

        showAddSpecimens: false,

        allRowsSelected: false,

        selectedRows: [],

        hasFacets: false,

        startAt: 0,

        pageNo: 0,

        haveMoreRecords: false,

        sortBy: [],

        dbHasMoreRecords: false
      }
    }
  },

  mounted() {
    this._loadCounters();
    this.ctx.sortBy = this._getDefaultSortBy();

    if (!this.query.selectList || this.query.selectList.length == 0) {
      this.showDefineViewDialog();
    } else {
      this._loadRecords();
    }
  },

  computed: {
    actionsMenuOpts: function() {
      const options = [];
      if (authSvc.isAllowed(queryResources.updateOpts)) {
        options.push({icon: 'edit', caption: this.$t('common.buttons.edit'), onSelect: () => this.editQuery()});
      }

      if (authSvc.isAllowed(queryResources.createOpts) || authSvc.isAllowed(queryResources.updateOpts)) {
        options.push({icon: 'save', caption: this.$t('common.buttons.save'), onSelect: () => this.showSaveQueryDialog()});
      }

      options.push({icon: 'columns', caption: this.$t('queries.columns'), onSelect: () => this.showDefineViewDialog()});
      options.push({icon: 'redo', caption: this.$t('queries.rerun'), onSelect: () => this.rerun()});

      if (authSvc.isAllowed(queryResources.importOpts)) {
        options.push({icon: 'download', caption: this.$t('common.buttons.export'), onSelect: () => this.exportQueryData()});
      }

      return options;
    },

    rowSelection: function() {
      if (!this.ctx.showAddSpecimens) {
        return null;
      }

      return {mode: 'multiRow', headerCheckbox: true, enableClickSelection: false, checkboxes: true};
    },

    selectedSpecimens: function() {
      return this.ctx.selectedRows.map(row => ({id: +row['$specimenId'], cpId: +row['$cpId']}));
    },

    pagingEnabled: function() {
      const {type} = this.query.reporting || {};
      return (!type || type == 'none') && !this.query.havingClause &&
        !(this.query.selectList || []).some(field => field.aggFns && field.aggFns.length > 0);
    }
  },

  methods: {
    saveQueryLocally: function(route) {
      window['osQuery'] = {route, query: JSON.stringify(this.query)};
    },

    editQuery: function() {
      if (this.query.id > 0) {
        routerSvc.goto('QueryDetail.AddEdit', {queryId: this.query.id});
      } else {
        routerSvc.back();
      }
    },

    showSaveQueryDialog: function() {
      this.$refs.saveQueryDialog.save(this.query).then(
        ({status, query}) => {
          if (status != 'saved') {
            return;
          }

          alertsSvc.success({code: 'queries.saved', args: query});
          this.$emit('query-saved', query);
        }
      );
    },

    rerun: function() {
      this._loadCounters();
      this._resetRecords();
    },

    showDefineViewDialog: function() {
      this.$refs.defineViewDialog.open(this.query).then(
        async resp => {
          if (resp == 'cancel') {
            return;
          }

          let query = {...this.query, ...resp};
          if (query.id > 0 && authSvc.isAllowed(queryResources.updateOpts)) {
            query = await savedQuerySvc.saveOrUpdate(query);
          }

          this.$emit('query-saved', query);
          setTimeout(
            () => {
              this.ctx.sortBy = this._getDefaultSortBy();
              this._resetRecords(this._getSelectedFacets());
            }
          ); // to allow the query to be updated
        }
      );
    },

    exportQueryData: function() {
      querySvc.exportData(this.query, this._getSelectedFacets(), this.ctx.sortBy);
    },

    onGridReady: function({api}) {
      this.ctx.api = api;
    },

    onRowSelection: function({api}) {
      const {ctx} = this;
      ctx.selectedRows = api.getSelectedRows();
      ctx.allRowsSelected = (ctx.selectedRows.length == ctx.records.length)
    },

    onSortChanged: function({api}) {
      if (!this.pagingEnabled || !this.ctx.columns) {
        return;
      }

      const sortBy = api.getColumnState()
        .filter(column => !!column.sort)
        .sort((column1, column2) => (column1.sortIndex || 0) - (column2.sortIndex || 0))
        .map(
          column => {
            const columnDef = this.ctx.columns.find(def => def.field == column.colId);
            return columnDef && {expr: columnDef.name, direction: column.sort};
          }
        )
        .filter(sort => !!sort);

      const nextSortBy = sortBy.length > 0 ? sortBy : this._getDefaultSortBy();
      if (this._isSameSortBy(nextSortBy, this.ctx.sortBy)) {
        return;
      }

      this.ctx.sortBy = nextSortBy;
      this._resetRecords();
    },

    selectAllRows: function() {
      this.ctx.api.selectAll();
      this.ctx.allRowsSelected = true;
    },

    unselectAllRows: function() {
      this.ctx.api.deselectAll();
      this.ctx.allRowsSelected = false;
    },

    onFacetsLoad: function(facets) {
      this.ctx.hasFacets = facets && facets.length > 0;
    },

    onFacetsSelection: function(selectedFacets) {
      const facets = selectedFacets.map(({facet, values}) => ({id: facet.id, type: facet.type, values}));
      this._loadCounters(facets);
      this._resetRecords(facets);
    },

    previousPage: function() {
      this._loadRecords(this._getSelectedFacets(), Math.max(0, this.ctx.startAt - PAGE_SIZE));
    },

    nextPage: function() {
      this._loadRecords(this._getSelectedFacets(), this.ctx.startAt + PAGE_SIZE);
    },

    _loadCounters: async function(facets) {
      this.ctx.loadingCounters = true;
      const {cprs, visits, specimens} = await querySvc.getCount(this.query, facets);

      this.ctx.loadingCounters = false;
      const formatter = new Intl.NumberFormat();
      this.ctx.counters = {
        cprs:      formatter.format(cprs),
        visits:    formatter.format(visits),
        specimens: formatter.format(specimens)
      };
    },

    _resetRecords: function(facets) {
      this._loadRecords(facets, 0);
    },

    _loadRecords: async function(facets, startAt = this.ctx.startAt) {
      this.ctx.loadingRecords = true;
      const {
        columnLabels, columnMetadata, columnTypes, columnUrls, rows, dbRowsCount,
        rootIds, haveMoreRecords
      } = await this._getData(facets, startAt);

      const {type, params} = this.query.reporting || {type: 'none', params: {}};
      let pinnedColumns = 0;
      if (type == 'crosstab') {
        pinnedColumns = (params.groupRowsBy || []).length;
      }

      const pagedRows = rows.slice();
      this.ctx.haveMoreRecords = this.pagingEnabled && haveMoreRecords;

      this.ctx.dbHasMoreRecords = !this.pagingEnabled && dbRowsCount >= 1000;

      this.ctx.startAt = startAt;
      this.ctx.pageNo = Math.floor(startAt / PAGE_SIZE);
      this.ctx.columns = columnLabels
        .map((label, idx) => {
          const column = {
            field: label,
            name: columnMetadata[idx].expr,
            pinned: idx < pinnedColumns && 'left',
            // tooltipField: label,
            wrapHeaderText: true,
            autoHeaderHeight: true,
            headerName: label.substring(label.lastIndexOf('#') + 1),
            wrapText: true,
            autoHeight: true,
            url: columnUrls[idx],
            sortable: this.pagingEnabled
          };
          const sortIndex = this.ctx.sortBy.findIndex(sort => sort.expr == column.name);
          if (sortIndex >= 0) {
            column.sort = this.ctx.sortBy[sortIndex].direction;
            column.sortIndex = sortIndex;
          }

          if (columnTypes[idx] == 'INTEGER' || columnTypes[idx] == 'FLOAT') {
            column.comparator = (valueA, valueB) => this._compareNum(valueA, valueB);
          }

          if (columnUrls[idx]) {
            column.cellRenderer = 'os-column-url';
          } else if (columnTypes[idx] == 'DATE') {
            column.valueFormatter = this._formatDate;
          }

          return column;
        })
        .filter(column => column.field.indexOf('$') != 0);

      this.ctx.records = pagedRows.map(
        row =>
          row.reduce(
            (record, value, idx) => {
              record[columnLabels[idx]] = value;
              return record;
            },
            {}
          )
      );

      if (this.pagingEnabled && rootIds) {
        const positions = new Map(rootIds.map((id, index) => [String(id), index]));
        this.ctx.records.sort(
          (record1, record2) => positions.get(String(record1.$cprId)) - positions.get(String(record2.$cprId))
        );
      }

      this.ctx.allRowsSelected = false;
      this.ctx.selectedRows = [];

      if (type == 'columnsummary') {
        this.ctx.footerRow = this.ctx.records.splice(this.ctx.records.length - 1, 1);
      } else {
        this.ctx.footerRow = [];
      }

      this.ctx.showAddSpecimens = false;
      if (type != 'crosstab' && columnMetadata && columnMetadata.length) {
        for (const {expr} of columnMetadata) {
          if (expr == 'Specimen.label' || expr == 'Specimen.barcode') {
            this.ctx.showAddSpecimens = true;
            break;
          }
        }
      }

      this.ctx.loadingRecords = false;
    },

    _getData: async function(facets, startAt) {
      if (!this.pagingEnabled) {
        return querySvc.getData(this.query, facets, {addPropIds: true, maxResults: 1000});
      }

      // First fetch just one stable, globally-sorted list of participant IDs. Then
      // fetch the wide rows for that page; otherwise DEEP/SHALLOW rows override the
      // AQL sort with ID ordering before the limit is applied.
      const page = await querySvc.getPageIds(this.query, facets, {
        startAt,
        maxResults: PAGE_SIZE + 1,
        orderBy: this.ctx.sortBy
      });

      const rootIds = page.rows.map(row => row[0]);
      const haveMoreRecords = rootIds.length > PAGE_SIZE;
      if (haveMoreRecords) {
        rootIds.pop();
      }

      const result = await querySvc.getData(this.query, facets, {addPropIds: true, rootIds});
      return {...result, rootIds, haveMoreRecords};
    },

    _formatDate: function(params) {
      if (!params.value) {
        return null;
      } else if (params.value.indexOf('T') != -1) {
        return this.$filters.dateTime(new Date(params.value));
      } else if (params.value.indexOf('#####') == 0) {
        return params.value;
      } else {
        if (typeof params.value == 'string') {
          const parts = params.value.split('-');
          if (parts.length == 3) {
            const [year, month, date] = parts;
            const localDate = new Date();
            localDate.setFullYear(+year);
            localDate.setMonth((+month - 1));
            localDate.setDate(+date);
            localDate.setHours(0, 0, 0, 0);
            return this.$filters.date(localDate);
          }
        }

        return this.$filters.date(new Date(params.value));
      }
    },

    _compareNum: function(valueA, valueB) {
      const blankValueA = this._isBlank(valueA);
      const blankValueB = this._isBlank(valueB);
      if (blankValueA && blankValueB) {
        return 0;
      } else if (blankValueA) {
        return -1;
      } else if (blankValueB) {
        return 1;
      } else {
        return +valueA - +valueB;
      }
    },

    _isBlank: function(value) {
      return value == null || value == undefined || value == '';
    },

    _getDefaultSortBy: function() {
      const selectedFields = (this.query.selectList || []).map(field => typeof field == 'string' ? field : field.name);
      return selectedFields.indexOf('Participant.creationTime') >= 0 ?
        [{expr: 'Participant.creationTime', direction: 'desc'}] :
        [{expr: 'Participant.id', direction: 'desc'}];
    },

    _isSameSortBy: function(sortBy1, sortBy2) {
      if (sortBy1.length != sortBy2.length) {
        return false;
      }

      return sortBy1.every(
        (sort, index) => sort.expr == sortBy2[index].expr && sort.direction == sortBy2[index].direction
      );
    },

    _getSelectedFacets: function() {
      const facets = this.ctx.hasFacets && this.$refs.facetsList ? this.$refs.facetsList.getSelectedFacets() : [];
      return facets.map(({facet: {id, type}, values}) => ({id, type, values}));
    }
  }
}
</script>

<style scoped>
.counters {
  display: flex;
  flex-direction: row;
  margin-top: -1.25rem;
  opacity: 0.8;
  font-size: 1.25rem;
  font-weight: 700;
}

.counters .counter {
  margin-right: 1rem;
  padding: 0.5rem 1rem;
}

.counters .counter :deep(.os-icon-wrapper) {
  margin-right: 0.5rem;
}

.results-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.results-panel :deep(.os-message) {
  margin-top: 0;
}

.results-grid {
  width: 100%;
  flex: 1;
}

.results-grid :deep(.ag-dnd-ghost),
.results-grid :deep(.ag-popup),
.results-grid :deep(.ag-header),
.results-grid :deep(.ag-root-wrapper) {
  font-family: Arial;
}

.query-pager {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-top: 1rem;
}

.selected-rows-msg {
  margin: 0;
}

.selected-rows-msg :deep(.p-message-wrapper) {
  padding: 0.25rem;
}
</style>
