-- V8__drawing_project.sql (BPA-77, plan Q2; ADR 0010; docs/data-model.md section 3.7).
-- V1 to V7 are frozen, so the change is a new file. A portal user sees the Drawings, Reserved materials and Cutting sheets of their assigned projects only
-- (BPA-77), but drawing held only (drawing_ref, title): nothing linked a drawing to a project except a Work Pack, which does not exist before Epic 1 starts.
-- Now drawing carries the project it belongs to. Nullable (ASSUMPTION): a drawing with no project belongs to no project and is in no project's tabs, and the
-- tests and fixtures that insert drawings without one keep working. Reservations and cutting sheets reach a project through their drawing. No source gives the
-- link: the seeded mapping (R__seed_reference_data.sql) is an ASSUMPTION, and the real SOR of the link is D365 F&O Project Management (A12: an FK column carries the
-- SOR of the entity it identifies).
ALTER TABLE bpa.drawing ADD COLUMN project_ref text;
ALTER TABLE bpa.drawing ADD CONSTRAINT drawing_project_ref_fkey FOREIGN KEY (project_ref) REFERENCES bpa.project_job_code (project_ref) ON DELETE NO ACTION;
COMMENT ON COLUMN bpa.drawing.project_ref IS 'D365 F&O Project Management';
