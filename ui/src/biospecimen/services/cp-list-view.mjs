// Participant protocols use PPID -> visit -> specimens; specimen-centric protocols retain their list.
export function resolveCpListView(cp) {
  return cp.specimenCentric ? 'specimens_list' : 'participants_list';
}
