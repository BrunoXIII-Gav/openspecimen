export function matchesSpecimenContext(context, {cpId, cprId, visitId, specimenId}) {
  return !!context && Number(context.specimenId) === Number(specimenId) && Number(context.cpId) === Number(cpId) &&
    Number(context.cpr?.id) === Number(cprId) && Number(context.visit?.id) === Number(visitId) &&
    Number(specimenId) > 0 && Number(visitId) > 0 && Number(cprId) > 0;
}
