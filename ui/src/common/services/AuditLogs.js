
import http from '@/common/services/HttpClient.js';

class AuditLogs {

  async getSummary(objDetail) {
    return http.post('audit', objDetail);
  }

  async getRevisions(objDetail) {
    return http.post('audit/revisions', objDetail);
  }

  getEntities() {
    return http.get('audit/revision-entities');
  }

  searchRecords(criteria) {
    return http.post('audit/records', criteria);
  }

  exportReport(criteria) {
    return http.post('audit/export-revisions', criteria);
  }

  async downloadReport(fileId) {
    const blob = await http.get('audit/revisions-file', {fileId}, {responseType: 'blob'}, (_resolve, error) => {
      // The page handles binary download errors; retain the normal session-expiry flow.
      if (error.status == 401) void http.handleError(error);
    });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'openspecimen-audit.zip';
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
  }

}

export default new AuditLogs();
