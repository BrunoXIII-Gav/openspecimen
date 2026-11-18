<template>
  <div>
    <os-message type="info">
      <span v-t="'common.loading'">Loading...</span>
    </os-message>
  </div>
</template>

<script>
import cpSvc from '@/biospecimen/services/CollectionProtocol.js';
import routerSvc from '@/common/services/Router.js';
import {resolveCpListView} from '@/biospecimen/services/cp-list-view.mjs';

export default {
  inject: ['cpViewCtx'],

  created() {
    const cp = this.cpViewCtx.getCp();
    return cpSvc.getWorkflowProperty(cp.id, 'common', 'listView').then(
      route => {
        route = resolveCpListView(cp, route, this.cpViewCtx.isReadParticipantAllowed(), this.cpViewCtx.isReadSpecimenAllowed());
        routerSvc.goto('ParticipantsList', {cpId: cp.id}, {view: route});
      }
    );
  }
}
</script>
