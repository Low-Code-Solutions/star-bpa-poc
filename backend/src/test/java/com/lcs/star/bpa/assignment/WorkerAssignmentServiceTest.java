package com.lcs.star.bpa.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lcs.star.bpa.assignment.WorkerAssignmentService.NotAwaitingFileException;
import com.lcs.star.bpa.persistence.WorkerAssignmentRepository;
import com.lcs.star.bpa.persistence.WorkerAssignmentRepository.FileRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BPA-22: the routing rules of {@code validate-worker-assignment} that the process-level tests (WorkerAssignmentApiTest) cannot reach, with the
 * repository mocked (added after the qa-reviewer's findings, L15): a Worker that is already assigned must not read "Assigned" and must roll
 * the transaction back, a retried job answers without writing (also for a file whose rows were all rejected), and a Work Pack that is not
 * AwaitingFile is refused with its state in the text (D61).
 */
class WorkerAssignmentServiceTest {

    static final UUID WORK_PACK = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID FILE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID E1021 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final String NUMBER = "WP-000001";

    private final WorkerAssignmentRepository repository = mock(WorkerAssignmentRepository.class);
    private final WorkerAssignmentService service = new WorkerAssignmentService(repository,
            new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(Instant.parse("2026-10-30T06:00:00Z"), ZoneOffset.UTC));

    private static FileRow row(String badge, String hours, String outcome) {
        return new FileRow(UUID.randomUUID(), 2, badge, hours, null, outcome);
    }

    private void given(String state, FileRow... rows) {
        when(repository.lockState(WORK_PACK)).thenReturn(Optional.of(state));
        when(repository.rows(FILE, NUMBER)).thenReturn(List.of(rows));
        when(repository.uploader(FILE)).thenReturn(Optional.of("production-team"));
        when(repository.workersByBadge(any())).thenReturn(Map.of("E1021", E1021));
    }

    @Test
    void aWorkerAlreadyAssignedToTheWorkPackIsNotAssignedAgainAndTheTransactionFails() {
        given("AwaitingFile", row("E1021", "40", "Pending"));
        when(repository.assign(eq(WORK_PACK), eq(E1021), any(), any(), eq(FILE), anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.validate(WORK_PACK, NUMBER, FILE)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(NUMBER).hasMessageContaining("already assigned");

        verify(repository, never()).setAssigning(any(), anyString(), any());
    }

    @Test
    void everyRowRejectedSavesNothingAndLeavesTheState() {
        given("AwaitingFile", row("E9999", "40", "Pending"));

        int assigned = service.validate(WORK_PACK, NUMBER, FILE);

        assertThat(assigned).isZero();
        verify(repository).setOutcome(any(), eq("Worker not in worker master data"), eq("production-team"), any());
        verify(repository, never()).assign(any(), any(), any(), any(), any(), anyString(), any());
        verify(repository, never()).setAssigning(any(), anyString(), any());
    }

    @Test
    void aRetriedJobAnswersTheSameCountWithoutWritingWhateverTheOutcomes() {
        // the file's rows already read Assigned, or all read a rejection: nothing is Pending, so nothing is written
        given("Assigning", row("E1021", "40", "Assigned"));
        when(repository.assignedFrom(WORK_PACK, FILE)).thenReturn(1);
        assertThat(service.validate(WORK_PACK, NUMBER, FILE)).isEqualTo(1);

        given("AwaitingFile", row("E9999", "40", "Worker not in worker master data"));
        when(repository.assignedFrom(WORK_PACK, FILE)).thenReturn(0);
        assertThat(service.validate(WORK_PACK, NUMBER, FILE)).as("a retried all-rejected file").isZero();

        verify(repository, never()).setOutcome(any(), anyString(), anyString(), any());
        verify(repository, never()).assign(any(), any(), any(), any(), any(), anyString(), any());
        verify(repository, never()).setAssigning(any(), anyString(), any());
    }

    @Test
    void pendingRowsOnAWorkPackThatIsNotAwaitingFileAreRefusedWithItsState() {
        given("Assigning", row("E1021", "40", "Pending"));
        assertThatThrownBy(() -> service.validate(WORK_PACK, NUMBER, FILE)).isInstanceOf(NotAwaitingFileException.class)
                .hasMessage("WP-000001 is Assigning, not AwaitingFile, so its assignment rows cannot be validated (D61)");

        given("", row("E1021", "40", "Pending"));
        assertThatThrownBy(() -> service.validate(WORK_PACK, NUMBER, FILE)).isInstanceOf(NotAwaitingFileException.class)
                .hasMessage("WP-000001 is without an assignment state, not AwaitingFile, so its assignment rows cannot be validated (D61)");

        verify(repository, never()).setOutcome(any(), anyString(), anyString(), any());
    }

    @Test
    void anAcceptedRowIsSavedWithTheValidatedHoursAndTheStateMoves() {
        given("AwaitingFile", row("E1021", "40", "Pending"));
        when(repository.assign(eq(WORK_PACK), eq(E1021), eq(new BigDecimal("40.00")), any(), eq(FILE), eq("production-team"), any())).thenReturn(true);

        assertThat(service.validate(WORK_PACK, NUMBER, FILE)).isEqualTo(1);

        verify(repository).setOutcome(any(), eq("Assigned"), eq("production-team"), any());
        verify(repository).setAssigning(eq(WORK_PACK), eq("production-team"), any());
    }
}
