const origins = new Set([
  'ParticipantsList',
  'ParticipantsListItemVisitDetail.Overview',
  'ParticipantsListItemSpecimenDetail.Overview'
]);

export function matrixOrigin(route) {
  if (!origins.has(route?.name)) return undefined;
  const query = {...route.query};
  delete query.returnTo;
  return JSON.stringify({name: route.name, params: route.params, query});
}

export function matrixReturnRoute(cpId, value) {
  try {
    const route = JSON.parse(value);
    if (!origins.has(route.name) || String(route.params?.cpId) !== String(cpId)) return null;
    return {name: route.name, params: route.params, query: route.query || {}};
  } catch {
    return null;
  }
}
