# 01 Glossary: STAR BPA POC

Status: DRAFT. Owner: Khitaba. Confirm entries marked (confirm); add process terms once the slice is chosen.

## Systems
- D365 F&O: Microsoft Dynamics 365 Finance & Operations, the new ERP, deployed strictly on-premises
  (no Azure, cloud Power BI or cloud data lake).
- SAP: existing system, retained (decision of 1 October 2026).
- FMS: partner-side system, retained. Integration with D365 is batch, file-based, via Cloudera and Informatica.
- myPeople: HR system, retained. SOR for employee data.
- GHRS: HR-related system referenced in FRDs (confirm role).
- Informatica: ETL tool used for batch integration.
- Cloudera: data lake platform used in the batch integration path.
- Tekla: CAD tool, not governed by any FRD.
- EDMS: electronic document management system (satellite system).
- Partner-hosted legacy system: spans at least ten domains including inventory, asset maintenance (AMOS),
  quality and production planning; integration with D365 is undocumented.

## Business areas
- HSE: Health, Safety and Environment.
- QC: Quality Control (FRD series).
- PPC: Production Planning and Control (FRD series) (confirm).
- Satellite systems: Canteen, Clinic, HSE, Transport, EDMS; candidates for best-of-breed tools rather than D365.

## Method terms
- FRD: Functional Requirements Document.
- SOR: system of record; the one system that masters a data item.
- POC database: the PostgreSQL schema (bpa) owned by this POC.
- BPMN / DMN: process and decision modelling standards (Camunda 8).
- Gherkin: Given / When / Then acceptance criteria format.
- ADR: architecture decision record.
- Ready / Done: see 02-story-standard.

## Slice-specific terms
(Add after the workshop.)
