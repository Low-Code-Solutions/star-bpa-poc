# Form specs

These `.form` files are the field specs for the React task screens (09 S14). Camunda forms are not
used: nothing deploys these files, and the BPMN user tasks have no form links. Each file lists the
fields, required flags and display conditions a screen must implement; validity rules are re-checked
by the backend (S14).

Read them with two things in mind:
- They refer to process variables (`refActivities`, `refDrawings`, `refMaterials`, `refDepartments`,
  `lookAhead`, `earliestPlannedDate`) that the dropped loading steps used to fill (G12, dropped by S14).
  React gets the same data from the backend's reference-list endpoints instead (S20: `GET activities`,
  `drawings`, `materials`, `departments`, `workpacks/{n}/look-ahead`).
- They still say `executionPlatformVersion` 8.10.0. They were moved unchanged (L1); the 8.10.1 rule (G1)
  applies to the deployed BPMN and DMN, not to these specs.
