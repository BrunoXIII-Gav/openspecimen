---
type: "query"
date: "2026-05-28T00:54:05.088080+00:00"
question: "all modules features user interfaces CRUD operations workflows user roles permissions"
contributor: "graphify"
source_nodes: ["AccessCtrlMgr", "CollectionProtocol", "Specimen", "StorageContainer", "Shipment", "DistributionOrder"]
---

# Q: all modules features user interfaces CRUD operations workflows user roles permissions

## Answer

OpenSpecimen has 32 i18n modules in the Vue UI. Core modules: (1) Biospecimen: cps, participants, visits, specimens, consents, participant_consents, cprs, carts, cpgs. (2) Administrative: institutes, sites, users, user_groups, roles, containers, container_types, container_tasks, settings. (3) Distribution/Logistics: orders (distribution), shipments, dps, lab_services. (4) Queries/Reporting: queries, jobs. (5) Forms/Config: forms, import, export, bulk_imports, pvs, audit. RBAC resources: CollectionProtocol, Specimen, PrimarySpecimen, Visit, Consent, StorageContainer, Distribution, ShippingAndTracking, ScheduledJob, Query, PrintRule. Operations: Create, Read, Update, Delete, BulkImport, Export, PHI access. Roles are scoped to (Site, CP) pairs.

## Source Nodes

- AccessCtrlMgr
- CollectionProtocol
- Specimen
- StorageContainer
- Shipment
- DistributionOrder