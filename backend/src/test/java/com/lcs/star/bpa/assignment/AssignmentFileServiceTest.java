package com.lcs.star.bpa.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lcs.star.bpa.AssignmentFileBuilder;
import com.lcs.star.bpa.adapter.CamundaProcessClient;
import com.lcs.star.bpa.adapter.CamundaTaskClient.EngineUnavailableException;
import com.lcs.star.bpa.persistence.AssignmentFileRepository;
import com.lcs.star.bpa.persistence.AssignmentFileRepository.WorkPackState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BPA-12: the upload service's routing rules that the process-level tests (AssignmentFileApiTest) cannot reach, with the repository and
 * Camunda mocked (added after the qa-reviewer's findings, L15). Three things: the D73 duplicate check comes before the state check (a Work
 * Pack published from this file may already have moved on to Assigning, which BPA-22 will set and no process path sets yet), a
 * claim that is lost to a concurrent identical upload is a duplicate, and a failed send of any kind undoes its claim and does not stop
 * the next Work Pack (data model 5.12, C2, D62).
 */
class AssignmentFileServiceTest {

    static final String A = "WP-000001";
    static final String B = "WP-000002";
    static final UUID FILE_ID = UUID.fromString("3f0c7a52-9e1c-4d6e-8d52-0a1b2c3d4e5f");

    private final AssignmentFileRepository repository = mock(AssignmentFileRepository.class);
    private final CamundaProcessClient camunda = mock(CamundaProcessClient.class);
    private final AssignmentFileService service = new AssignmentFileService(repository, camunda,
            new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(Instant.parse("2026-10-30T06:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void saved() {
        when(repository.saveFile(anyString(), anyString(), anyString(), any(), any())).thenReturn(FILE_ID);
    }

    private static byte[] file(String... workpackNumbers) {
        AssignmentFileBuilder builder = AssignmentFileBuilder.assignment();
        for (String number : workpackNumbers) {
            builder.row(number, "E1021", 40);
        }
        return builder.build();
    }

    private void awaitingFile(String workpackNumber) {
        when(repository.workPack(workpackNumber)).thenReturn(Optional.of(new WorkPackState("Released", "AwaitingFile")));
        when(repository.claim(anyString(), eq(workpackNumber), anyString(), any())).thenReturn(true);
    }

    @Test
    void aPublishedWorkPackThatHasMovedOnIsStillADuplicate() {
        // A was published from this very content and is now Assigning; B is unknown, so the file is not "already processed" as a whole.
        when(repository.workPack(A)).thenReturn(Optional.of(new WorkPackState("Released", "Assigning")));
        when(repository.published(anyString(), eq(A))).thenReturn(true);
        when(repository.workPack(B)).thenReturn(Optional.empty());

        service.upload("assign-03.xlsx", file(A, B), "production-team");

        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> number = ArgumentCaptor.forClass(String.class);
        verify(repository, org.mockito.Mockito.times(2)).saveRow(eq(FILE_ID), anyInt(), number.capture(), any(), any(), any(), outcome.capture(), anyString(), any());
        assertThat(number.getAllValues()).containsExactly(A, B);
        assertThat(outcome.getAllValues()).as("D73 is checked before the state: not \"Assignment still in progress\"")
                .containsExactly("Already processed in an earlier upload", "Work Pack not found");
        verify(camunda, never()).correlate(anyString(), anyString(), anyMap());
    }

    @Test
    void aClaimLostToAConcurrentUploadIsADuplicateAndNothingIsSent() {
        // Two identical uploads at the same moment: the UNIQUE guard gave the pair to the other one, so this claim returns false.
        awaitingFile(A);
        when(repository.claim(anyString(), eq(A), anyString(), any())).thenReturn(false);
        awaitingFile(B);

        service.upload("assign-04.xlsx", file(A, B), "production-team");

        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        verify(repository, org.mockito.Mockito.times(2)).saveRow(eq(FILE_ID), anyInt(), anyString(), any(), any(), any(), outcome.capture(), anyString(), any());
        assertThat(outcome.getAllValues()).containsExactly("Already processed in an earlier upload", "Pending");
        verify(camunda, never()).correlate(anyString(), eq(A), anyMap());
        verify(camunda).correlate(eq("AssignmentFileReceived"), eq(B), anyMap());
    }

    @Test
    void aFileWhoseEveryWorkPackLostTheClaimIsAlreadyProcessed() {
        awaitingFile(A);
        when(repository.claim(anyString(), eq(A), anyString(), any())).thenReturn(false);

        service.upload("assign-05.xlsx", file(A), "production-team");

        verify(repository).saveFile(eq("assign-05.xlsx"), anyString(), eq("production-team"), any(), eq("File already processed"));
        verify(repository, never()).saveRow(any(), anyInt(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anyFailureOfTheSendUndoesThatClaimAndTheNextWorkPackIsStillSent() {
        awaitingFile(A);
        awaitingFile(B);
        when(camunda.correlate(anyString(), eq(A), anyMap())).thenThrow(new IllegalStateException("Camunda message correlation returned 403"));
        when(camunda.correlate(anyString(), eq(B), anyMap())).thenReturn(true);

        assertThatCode(() -> service.upload("assign-06.xlsx", file(A, B), "production-team")).as("no 500 for the user").doesNotThrowAnyException();

        verify(repository).unclaim(anyString(), eq(A));
        verify(repository).setPendingRowsOutcome(FILE_ID, A, "Not processed: Work Pack not ready; resend the file");
        verify(repository, never()).unclaim(anyString(), eq(B));
        verify(camunda).correlate(eq("AssignmentFileReceived"), eq(B), anyMap());
    }

    @Test
    void anUnreachableCamundaUndoesOnlyTheClaimsThatWereNotDelivered() {
        awaitingFile(A);
        awaitingFile(B);
        when(camunda.correlate(anyString(), eq(A), anyMap())).thenReturn(true);
        when(camunda.correlate(anyString(), eq(B), anyMap())).thenThrow(new EngineUnavailableException("Camunda cannot be reached"));

        service.upload("assign-07.xlsx", file(A, B), "production-team");

        verify(repository, never()).unclaim(anyString(), eq(A));
        verify(repository).unclaim(anyString(), eq(B));
        verify(repository).setPendingRowsOutcome(FILE_ID, B, "Not processed: Work Pack not ready; resend the file");
        verify(repository, never()).setPendingRowsOutcome(any(), eq(A), any());
    }

    @Test
    void aFileProblemRecordsTheUploadWithItsLineAndTouchesNothingElse() {
        service.upload("assign-08.xlsx", AssignmentFileBuilder.csvRenamedAsXlsx(), "production-team");

        verify(repository).saveFile(eq("assign-08.xlsx"), anyString(), eq("production-team"), any(), eq("File cannot be read"));
        verify(repository, never()).workPack(any());
        verify(camunda, never()).correlate(any(), any(), any());
    }

    // --- BPA-13: a file for an Assigned Work Pack is a replacement (D61, C3, D75) -------------------------------------------------

    private void assigned(String workpackNumber) {
        when(repository.workPack(workpackNumber)).thenReturn(Optional.of(new WorkPackState("Released", "Assigned")));
        when(repository.claim(anyString(), eq(workpackNumber), anyString(), any())).thenReturn(true);
        when(repository.startReplacing(eq(workpackNumber), anyString(), any())).thenReturn(true);
    }

    @Test
    void aFileForAnAssignedWorkPackStartsAReplacementThroughTheReplacementMessage() {
        assigned(A);
        when(camunda.correlate(eq("AssignmentFileReplaced"), eq(A), anyMap())).thenReturn(true);

        service.upload("replace-01.xlsx", file(A), "production-team");

        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        verify(repository).saveRow(eq(FILE_ID), anyInt(), eq(A), any(), any(), any(), outcome.capture(), anyString(), any());
        assertThat(outcome.getValue()).as("the rows wait for the worker").isEqualTo("Pending");
        verify(repository).startReplacing(eq(A), eq("production-team"), any());
        verify(camunda).correlate(eq("AssignmentFileReplaced"), eq(A), anyMap());
        verify(camunda, never()).correlate(eq("AssignmentFileReceived"), anyString(), anyMap());
        verify(repository, never()).revertReplacing(anyString(), anyString(), any());
    }

    @Test
    void aReplacementNoInstanceReceivedIsRevertedAndTheRowsSayInProgress() {
        assigned(A);
        when(repository.hasPendingRows(FILE_ID, A)).thenReturn(true);
        when(camunda.correlate(eq("AssignmentFileReplaced"), eq(A), anyMap())).thenReturn(false); // 404: a reassignment with the key is still running (BPA-13 probe)

        service.upload("replace-02.xlsx", file(A), "production-team");

        verify(repository).revertReplacing(eq(A), eq("production-team"), any());
        verify(repository).unclaim(anyString(), eq(A));
        verify(repository).setPendingRowsOutcome(FILE_ID, A, "Replacement already in progress");
    }

    @Test
    void aReplacementWhoseSendFailsIsRevertedAndTheRowsSayItCouldNotBeStarted() {
        assigned(A);
        assigned(B);
        when(repository.hasPendingRows(FILE_ID, A)).thenReturn(true);
        when(repository.hasPendingRows(FILE_ID, B)).thenReturn(true);
        when(camunda.correlate(eq("AssignmentFileReplaced"), eq(A), anyMap())).thenThrow(new EngineUnavailableException("Camunda cannot be reached"));
        when(camunda.correlate(eq("AssignmentFileReplaced"), eq(B), anyMap())).thenThrow(new IllegalStateException("Camunda message correlation returned 403"));

        assertThatCode(() -> service.upload("replace-03.xlsx", file(A, B), "production-team")).as("no 500 for the user").doesNotThrowAnyException();

        for (String number : new String[] {A, B}) {
            verify(repository).revertReplacing(eq(number), eq("production-team"), any());
            verify(repository).unclaim(anyString(), eq(number));
            verify(repository).setPendingRowsOutcome(FILE_ID, number, "Replacement could not be started; try again");
        }
    }

    @Test
    void aReplacementLostToAConcurrentChangeOfTheStateIsAnsweredInProgressAndNothingIsSent() {
        assigned(A);
        when(repository.startReplacing(eq(A), anyString(), any())).thenReturn(false); // the compare-and-set found another state

        service.upload("replace-04.xlsx", file(A), "production-team");

        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        verify(repository).saveRow(eq(FILE_ID), anyInt(), eq(A), any(), any(), any(), outcome.capture(), anyString(), any());
        assertThat(outcome.getValue()).isEqualTo("Replacement already in progress");
        verify(repository).unclaim(anyString(), eq(A));
        verify(camunda, never()).correlate(anyString(), anyString(), anyMap());
    }

    @Test
    void aReplacementThatWasAnsweredAlthoughItsStartLookedFailedIsNotUndone() {
        // the engine did start the instance, the HTTP call failed afterwards, and the worker has already answered the rows: undoing now would lie to the user
        assigned(A);
        when(camunda.correlate(eq("AssignmentFileReplaced"), eq(A), anyMap())).thenThrow(new EngineUnavailableException("Camunda cannot be reached"));
        when(repository.hasPendingRows(FILE_ID, A)).thenReturn(false);

        service.upload("replace-05.xlsx", file(A), "production-team");

        verify(repository, never()).revertReplacing(anyString(), anyString(), any());
        verify(repository, never()).unclaim(anyString(), eq(A));
        verify(repository, never()).setPendingRowsOutcome(any(), eq(A), anyString());
    }

    @Test
    void oneUndoThatFailsDoesNotStopTheUndoOfTheNextWorkPack() {
        assigned(A);
        assigned(B);
        when(repository.hasPendingRows(FILE_ID, A)).thenReturn(true);
        when(repository.hasPendingRows(FILE_ID, B)).thenReturn(true);
        when(camunda.correlate(eq("AssignmentFileReplaced"), anyString(), anyMap())).thenReturn(false);
        Mockito.doThrow(new IllegalStateException("database is down")).when(repository).revertReplacing(eq(A), anyString(), any());

        assertThatCode(() -> service.upload("replace-06.xlsx", file(A, B), "production-team")).as("no 500 for the user").doesNotThrowAnyException();

        verify(repository).revertReplacing(eq(B), eq("production-team"), any());
        verify(repository).setPendingRowsOutcome(FILE_ID, B, "Replacement already in progress");
    }
}
