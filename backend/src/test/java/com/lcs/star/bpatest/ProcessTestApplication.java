package com.lcs.star.bpatest;

import com.lcs.star.bpa.worker.CreateHourJournalLinesWorker;
import com.lcs.star.bpa.worker.CreateDigitalTimesheetsWorker;
import com.lcs.star.bpa.worker.EvaluateReleaseInputsWorker;
import com.lcs.star.bpa.worker.FindRevisionRecipientsWorker;
import com.lcs.star.bpa.worker.SendHoursReminderWorker;
import com.lcs.star.bpa.worker.SendRevisionNoticeWorker;
import com.lcs.star.bpa.worker.ReplaceWorkerAssignmentWorker;
import com.lcs.star.bpa.worker.GenerateWorkPackPrintWorker;
import com.lcs.star.bpa.worker.GenerateFmsHoursFileWorker;
import com.lcs.star.bpa.worker.HourJournalHeaderWorker;
import com.lcs.star.bpa.worker.OpenProgressPeriodWorker;
import com.lcs.star.bpa.worker.ReadCompletionReportWorker;
import com.lcs.star.bpa.worker.PrepareProgressCheckWorker;
import com.lcs.star.bpa.worker.RecordCompletionApprovalWorker;
import com.lcs.star.bpa.worker.RecordProgressResultWorker;
import com.lcs.star.bpa.worker.CalculateEarnedManhoursWorker;
import com.lcs.star.bpa.worker.RecordRetractionDecisionWorker;
import com.lcs.star.bpa.worker.PrepareVarianceCheckWorker;
import com.lcs.star.bpa.worker.SendVarianceAlertsWorker;
import com.lcs.star.bpa.worker.RecordCbsReviewWorker;
import com.lcs.star.bpa.worker.RecordWeekDecisionWorker;
import com.lcs.star.bpa.worker.CompleteWorkPacksWorker;
import com.lcs.star.bpa.worker.LockProgressPeriodWorker;
import com.lcs.star.bpa.worker.RecordFmsHandOffWorker;
import com.lcs.star.bpa.worker.RecordSignOffWorker;
import com.lcs.star.bpa.worker.SaveWorkPackScheduleWorker;
import com.lcs.star.bpa.worker.ValidateWorkerAssignmentWorker;
import com.lcs.star.bpa.worker.SaveWorkPackWorker;
import com.lcs.star.bpa.worker.PrepareHoursSubmissionWorker;
import com.lcs.star.bpa.worker.PublishWorkPackAssignedWorker;
import com.lcs.star.bpa.worker.RecordHoursDecisionWorker;
import com.lcs.star.bpa.worker.SubmitHoursWorker;
import io.camunda.client.annotation.Deployment;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Camunda Process Test application: only the job workers and the models, no database. It sits in a package of its own, as the
 * Camunda Process Test documentation asks, and lists the worker packages it needs. Workers that need the database are left out
 * (BPA-6: SaveWorkPackWorker; BPA-7: HourJournalHeaderWorker; BPA-8: EvaluateReleaseInputsWorker; BPA-9: RecordSignOffWorker; BPA-11: SaveWorkPackScheduleWorker; BPA-10: GenerateWorkPackPrintWorker; BPA-12, BPA-22: ValidateWorkerAssignmentWorker; BPA-14: CreateHourJournalLinesWorker; BPA-15: CreateDigitalTimesheetsWorker; BPA-49: PublishWorkPackAssignedWorker; BPA-51: PrepareHoursSubmissionWorker; BPA-53: SubmitHoursWorker and RecordHoursDecisionWorker; BPA-13: ReplaceWorkerAssignmentWorker; BPA-16: FindRevisionRecipientsWorker and SendRevisionNoticeWorker, which need the database, the Camunda group client and the mail sender; BPA-58: OpenProgressPeriodWorker, GenerateFmsHoursFileWorker and RecordFmsHandOffWorker; BPA-59: ReadCompletionReportWorker; BPA-60: RecordCompletionApprovalWorker; BPA-61: PrepareProgressCheckWorker and RecordProgressResultWorker; BPA-62: CalculateEarnedManhoursWorker; BPA-63: RecordRetractionDecisionWorker; BPA-64: PrepareVarianceCheckWorker, SendVarianceAlertsWorker and RecordCbsReviewWorker; BPA-66: RecordWeekDecisionWorker; BPA-67: CompleteWorkPacksWorker and LockProgressPeriodWorker; BPA-74: SendHoursReminderWorker, which needs the database, the Camunda group client and the mail sender); the tests that use this app complete those jobs themselves.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
@ComponentScan(basePackages = "com.lcs.star.bpa.worker",
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {SaveWorkPackWorker.class, HourJournalHeaderWorker.class, EvaluateReleaseInputsWorker.class, RecordSignOffWorker.class, SaveWorkPackScheduleWorker.class, GenerateWorkPackPrintWorker.class, ValidateWorkerAssignmentWorker.class, CreateHourJournalLinesWorker.class, CreateDigitalTimesheetsWorker.class, PublishWorkPackAssignedWorker.class, PrepareHoursSubmissionWorker.class, SubmitHoursWorker.class, RecordHoursDecisionWorker.class, ReplaceWorkerAssignmentWorker.class, FindRevisionRecipientsWorker.class, SendRevisionNoticeWorker.class, OpenProgressPeriodWorker.class, GenerateFmsHoursFileWorker.class, RecordFmsHandOffWorker.class, ReadCompletionReportWorker.class, RecordCompletionApprovalWorker.class, PrepareProgressCheckWorker.class, RecordProgressResultWorker.class, CalculateEarnedManhoursWorker.class, RecordRetractionDecisionWorker.class, PrepareVarianceCheckWorker.class, SendVarianceAlertsWorker.class, RecordCbsReviewWorker.class, RecordWeekDecisionWorker.class, CompleteWorkPacksWorker.class, LockProgressPeriodWorker.class, SendHoursReminderWorker.class}))
@Deployment(resources = {"classpath*:bpmn/*.bpmn", "classpath*:dmn/*.dmn"})
public class ProcessTestApplication {
}
