// Participant protocols use PPID -> visit -> specimens; specimen-centric protocols retain their list.
export function resolveCpListView(cp) {
  return cp.specimenCentric ? 'specimens_list' : 'participants_list';
}

export function isAutoPpid(cp) {
  return !!cp?.ppidFmt?.trim() && cp.manualPpidEnabled !== true && cp.manualPpidEnabled !== 'true';
}

// Change only the displayed list; routing and selection retain the server's internal IDs.
export function hideAutoPpidColumn(list, cp) {
  if (!isAutoPpid(cp)) return list;

  const columns = list?.columns || [];
  const rows = list?.rows || [];
  const ppidIndex = columns.findIndex(column => column.expr === 'Participant.ppid');
  if (ppidIndex < 0) return list;

  const readable = value => value != null && String(value).trim() !== '' &&
    !String(value).startsWith('###') && value !== 'Not Specified';
  const usable = index => index >= 0 && rows.every(row => readable(row.data?.[index]));
  const mpiIndex = columns.findIndex(column => column.expr === 'Participant.empi');
  const nameIndex = columns.findIndex(column => column.expr !== 'Participant.ppid' &&
    (column.captionCode === 'participants.name' ||
      /Participant\.(firstName|lastName)/.test(column.expr || '')));
  const linkIndex = usable(mpiIndex) ? mpiIndex : usable(nameIndex) ? nameIndex : -1;

  // Keep the permitted identifier when names/MPI are unavailable or masked.
  if (linkIndex < 0) return list;

  return {
    ...list,
    columns: columns.map((column, index) => index === linkIndex ?
      {...column, metainfo: {...column.metainfo, showLink: 'true'}} : column)
      .filter((column, index) => index !== ppidIndex),
    rows: rows.map(row => ({...row, data: row.data.filter((value, index) => index !== ppidIndex)}))
  };
}
