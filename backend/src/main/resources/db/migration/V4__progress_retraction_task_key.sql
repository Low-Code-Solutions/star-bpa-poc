-- V4__progress_retraction_task_key.sql (BPA-63, plan Q1; ADR 0010; docs/data-model.md section 13.3.5).
-- V1 to V3 are frozen, so the change is a new file. One UserTask_ApproveRetraction decides every Work Pack that waits for a retraction, so one task key is
-- written on several progress_retraction rows (one per Work Pack). UNIQUE (task_key) allowed only one; the key is now unique per Work Pack row, and stays the
-- re-run key of ServiceTask_RecordRetractionDecision (a retried job finds its rows by task_key and writes nothing again).
ALTER TABLE bpa.progress_retraction DROP CONSTRAINT progress_retraction_task_key_key;
ALTER TABLE bpa.progress_retraction ADD CONSTRAINT progress_retraction_task_key_workpack_progress_id_key UNIQUE (task_key, workpack_progress_id);
