<template>
  <router-view :cpr="cpr" :visit="visit" :specimen="specimen" :key="viewKey" v-if="viewKey && loaded" />
</template>

<script>

import {provide, ref} from 'vue';

import cpSvc    from '@/biospecimen/services/CollectionProtocol.js';
import formUtil from '@/common/services/FormUtil.js';
import spmnSvc  from '@/biospecimen/services/Specimen.js';

export default {
  props: ['cpr', 'visit', 'specimenId', 'reqId'],

  async setup() {
    const specimen = ref({});
    provide('specimen', specimen);

    const loaded = ref(false);
    return { specimen, loaded };
  },

  created() {
    if (this.specimenId > 0) {
      this._loadSpecimen(this.specimenId);
    } else if (this.reqId > 0) {
      this._loadRequirement(this.reqId);
    } else {
      this._loadBlank();
    }
  },

  computed: {
    viewKey: function() {
      if (this.specimenId > 0) {
        return 's-' + this.specimenId;
      } else if (this.reqId > 0) {
        return 'sr-' + this.cpr.cpId + '-' + this.visit.eventId + '-' + this.reqId;
      }

      const parentId = this.$route && this.$route.query && this.$route.query.parentId;
      return 'new-' + this.cpr.id + '-' + this.visit.id + '-' + (parentId || '0');
    }
  },

  watch: {
    '$route.params.specimenId': function(newVal, oldVal) {
      if (newVal == oldVal || newVal == this.specimen.id) {
        return;
      }

      if (newVal > 0) {
        this._loadSpecimen(newVal);
      }
    },

    '$route.query.reqId': function(newVal, oldVal) {
      if (this.specimenId > 0 || newVal == oldVal || newVal == this.specimen.reqId) {
        return;
      }

      this._loadRequirement(newVal);
    }
  },

  methods: {
    _loadSpecimen: async function(specimenId) {
      this.specimen = {};
      this.specimen = await spmnSvc.getById(specimenId);
      formUtil.createCustomFieldsMap(this.specimen, true);
      this.loaded = true;
    },

    _loadRequirement: async function(reqId) {
      this.specimen = {};
      const req = await cpSvc.getSpecimenRequirement(reqId);
      this.specimen = this._toSpecimen(req);
      this.loaded = true;
    },

    _loadBlank: async function() {
      const cp = await cpSvc.getCpById(this.cpr.cpId);
      
      // Leer query params directamente del hash para mayor compatibilidad
      const hash = window.location.hash;
      const queryStr = hash.split('?')[1] || '';
      const params = new URLSearchParams(queryStr);
      const parentId = params.get('parentId') ? parseInt(params.get('parentId')) : null;
      const lineage = params.get('lineage') || 'New';
      console.log('_loadBlank lineage:', lineage, 'parentId:', parentId);

      let labelFmt = cp.specimenLabelFmt || '';
      if (lineage === 'Aliquot') {
        labelFmt = cp.aliquotLabelFmt || cp.aliquotLabelFmtToUse || '';
      } else if (lineage === 'Derived') {
        labelFmt = cp.derivativeLabelFmt || '';
      }

      this.specimen = {
        cpId: this.cpr.cpId,
        cprId: this.cpr.id,
        ppid: this.cpr.ppid,
        cpShortTitle: this.cpr.cpShortTitle,
        visitId: this.visit.id,
        visitName: this.visit.name,
        visitStatus: this.visit.status,
        visitDate: this.visit.visitDate,
        eventId: this.visit.eventId,
        eventLabel: this.visit.eventLabel,
        lineage,
        parentId,
        status: 'Collected',
        labelFmt,
        children: []
      };
      this.loaded = true;
    },

    _toSpecimen: function(req) {
      const specimen = {};
      specimen.reqId = req.id;
      specimen.cpId = this.cpr.cpId;
      specimen.cprId = this.cpr.id;
      specimen.ppid = this.cpr.ppid;
      specimen.eventId = this.visit.eventId;
      specimen.eventCode = this.visit.eventCode;
      specimen.eventLabel = this.visit.eventLabel;
      specimen.visitId = this.visit.id;
      specimen.visitName = this.visit.name;
      specimen.visitStatus = this.visit.status;
      specimen.sprNo = this.visit.surgicalPathologyNumber;
      specimen.visitDate = this.visit.visitDate;
      specimen.cpShortTitle = this.cpr.cpShortTitle;
      specimen.reqId = req.id;
      specimen.sortOrder = req.sortOrder;
      specimen.type = req.type;
      specimen.specimenClass = req.specimenClass;
      specimen.lineage = req.lineage;
      specimen.anatomicSite = req.anatomicSite;
      specimen.laterality = req.laterality;
      specimen.status = 'Pending';
      specimen.reqLabel = req.name;
      specimen.pathology = req.pathology;
      specimen.initialQty = req.initialQty;
      specimen.concentration = req.concentration;
      specimen.collectionContainer = req.collectionContainer;
      specimen.extensionDetail = req.extensionDetail;
      if (specimen.extensionDetail) {
        formUtil.createCustomFieldsMap(specimen, true);
      }

      delete req.id;

      specimen.children = [];
      for (let child of (req.children || [])) {
        specimen.children.push(this._toSpecimen(child));
      }

      return specimen;
    }
  }
}
</script>