package com.lcs.star.bpa;

import static com.lcs.star.bpa.AssignmentFlow.BOOK_HOURS;
import static com.lcs.star.bpa.AssignmentFlow.CATCH;
import static com.lcs.star.bpa.AssignmentFlow.END;
import static com.lcs.star.bpa.CamundaSteps.completed;
import static com.lcs.star.bpa.CamundaSteps.elements;
import static com.lcs.star.bpa.CamundaSteps.json;
import static com.lcs.star.bpa.CamundaSteps.poll;
import static com.lcs.star.bpa.CamundaSteps.toJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lcs.star.bpa.AssignmentFlow.Upload;
import com.lcs.star.bpa.AssignmentFlow.Work;
import com.lcs.star.bpa.timesheet.TimesheetProcessStarter;
import com.lcs.star.bpa.timesheet.TimesheetProcessStarter.Outcome;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BPA-49 (E2-01): the end of a Work Pack's assignment process starts its timesheet process, once. One test per Gherkin scenario, named as
 * in the Jira Definition of Done, plus the 404 and 403 of the SOR Notes in the last test. The Background is real, as for BPA-15: a Work Pack
 * taken to Assigned through the API (AssignmentFlow), then {@code publish-workpack-assigned} behind EndEvent_WorkPackAssigned publishes
 * WorkPackAssigned and Process_PPC_TimesheetToProgress starts. Today is 30 Oct 2026 (Asia/Riyadh), the date the shared flow's schedule step
 * is valid on (the story's 18 Nov only sets the Background). Epic 2 instances are found by the process variable workpackNumber and cancelled
 * before each class: all test classes share one Camunda and Work Pack numbers repeat.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AssignmentFileApiTest.TestClockConfig.class)
class TimesheetProcessStartApiTest {

    static final String EPIC2 = "Process_PPC_TimesheetToProgress";
    static final String MESSAGE = "WorkPackAssigned";
    static final String ONE_OFF = "/api/v1/timesheet-processes";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void camunda(DynamicPropertyRegistry registry) {
        IntegrationApp.background();
        registry.add("camunda.client.rest-address", IntegrationCamunda::restUrl);
        registry.add("camunda.client.grpc-address", IntegrationCamunda::grpcUrl);
        registry.add("bpa.camunda.rest-url", IntegrationCamunda::restUrl);
        registry.add("camunda.client.auth.password", () -> IntegrationApp.servicePassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AssignmentFileApiTest.TestClock clock;

    @Autowired
    TimesheetProcessStarter starter;

    private AssignmentFlow flow;

    /** All test classes share one Camunda and Work Pack numbers repeat, so an instance another class left waiting could take our message. */
    @BeforeAll
    static void noInstanceLeftWaitingByAnotherClass() {
        IntegrationApp.background();
        CamundaSteps.cancelInstancesWaitingAt(CATCH);
        CamundaSteps.cancelInstancesWaitingAt(BOOK_HOURS);
    }

    @BeforeEach
    void today() {
        clock.set(AssignmentFileApiTest.TODAY);
        flow = new AssignmentFlow(port, jdbc);
    }

    // --- the scenarios ----------------------------------------------------------------------------------------------------

    @Test
    void BPA49_assignedWorkPackStartsTimesheetProcess() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));

        String instance = oneTimesheetInstance(wp);

        assertThat(CamundaSteps.awaitOpenTask(Long.parseLong(instance), "UserTask_BookHours").path("state").asText()).isEqualTo("CREATED");
        assertThat(candidateGroups(instance)).as("UserTask_BookHours is for group supervisor").containsExactly("supervisor");
    }

    @Test
    void BPA49_retriedPublishStartsNoSecondInstance() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));
        oneTimesheetInstance(wp);

        // a retried job: the record is there, so nothing is published
        assertThat(starter.start(wp.number(), "bpa-service")).isEqualTo(Outcome.ALREADY_RUNNING);
        assertThat(timesheetInstances(wp.number())).as("a retry with the record in place").hasSize(1);

        // a crash between the publish and the record: the retry publishes again, and Camunda drops it while one with the same key is active
        jdbc.update("DELETE FROM bpa.published_message WHERE sha256 = ? AND workpack_number = ?", MESSAGE, wp.number());
        assertThat(starter.start(wp.number(), "bpa-service")).isEqualTo(Outcome.STARTED);
        CamundaSteps.pause();
        CamundaSteps.pause();
        assertThat(timesheetInstances(wp.number())).as("a second publish while the first instance is active").hasSize(1);
        assertThat(flow.claims(MESSAGE, wp.number())).as("the record is back").isEqualTo(1);
    }

    @Test
    void BPA49_replacementStartsNoTimesheetProcess() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));
        oneTimesheetInstance(wp);

        Upload replacement = flow.upload("production-team", "replace-49.xlsx",
                AssignmentFileBuilder.assignment().row(wp.number(), "E1021", 50).row(wp.number(), "E1099", 60).build());
        assertThat(replacement.status()).as("upload: " + replacement.body()).isEqualTo(201);
        flow.awaitReassignmentEnd(wp, AssignmentFlow.END_REPLACED);

        assertThat(timesheetInstances(wp.number())).as("a replacement publishes nothing").hasSize(1);
        assertThat(flow.claims(MESSAGE, wp.number())).isEqualTo(1);
    }

    @Test
    void BPA49_epic1InstanceStillEndsAtWorkPackAssigned() {
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));

        assertThat(completed(wp.instance(), END, 1)).as("the Epic 1 instance completed at " + END).isTrue();
        assertThat(flow.state(wp)).isEqualTo("Assigned");
        assertThat(flow.claims(MESSAGE, wp.number())).as("the hand-off is recorded once").isEqualTo(1);
    }

    @Test
    void BPA49_alreadyAssignedWorkPackCanBeStartedOnce() {
        // a Work Pack assigned before Epic 2 was deployed: assigned, with no timesheet process and no record
        Work wp = flow.assignedWorkPack(Map.of("E1021", 40, "E1099", 60));
        CamundaSteps.cancelInstancesWaitingAt(BOOK_HOURS);
        jdbc.update("DELETE FROM bpa.published_message WHERE sha256 = ? AND workpack_number = ?", MESSAGE, wp.number());
        // Camunda's search lags the cancel: wait until it no longer lists the instance as running (BPA-25)
        assertThat(poll(() -> timesheetInstances(wp.number()), List::isEmpty, "the cancelled timesheet process of " + wp.number() + " to leave the search"))
                .as("no timesheet process any more").isEmpty();

        HttpResponse<String> first = flow.loggedInAs("ppc-team").post(ONE_OFF, toJson(Map.of("workpackNumber", wp.number())));
        assertThat(first.statusCode()).as("first call: " + first.body()).isEqualTo(201);
        oneTimesheetInstance(wp);

        HttpResponse<String> second = flow.loggedInAs("ppc-team").post(ONE_OFF, toJson(Map.of("workpackNumber", wp.number())));
        assertThat(second.statusCode()).as("second call: " + second.body()).isEqualTo(409);
        assertThat(json(second.body()).path("detail").asText()).isEqualTo("Timesheet process already running for " + wp.number());
        assertThat(timesheetInstances(wp.number())).hasSize(1);
    }

    @Test
    void BPA49_oneOffStartRefusedForUnassignedWorkPack() {
        Work draft = flow.draft();

        HttpResponse<String> notAssigned = flow.loggedInAs("ppc-team").post(ONE_OFF, toJson(Map.of("workpackNumber", draft.number())));
        assertThat(notAssigned.statusCode()).as(notAssigned.body()).isEqualTo(409);
        assertThat(json(notAssigned.body()).path("detail").asText()).isEqualTo(draft.number() + " is not assigned yet");
        assertThat(timesheetInstances(draft.number())).isEmpty();

        // the rest of the SOR Notes contract: an unknown Work Pack is 404, any other group is 403
        HttpResponse<String> unknown = flow.loggedInAs("ppc-team").post(ONE_OFF, toJson(Map.of("workpackNumber", "WP-999999")));
        assertThat(unknown.statusCode()).as(unknown.body()).isEqualTo(404);
        assertThat(json(unknown.body()).path("detail").asText()).isEqualTo("Work Pack not found");
        HttpResponse<String> wrongGroup = flow.loggedInAs("supervisor").post(ONE_OFF, toJson(Map.of("workpackNumber", draft.number())));
        assertThat(wrongGroup.statusCode()).as(wrongGroup.body()).isEqualTo(403);
    }

    // --- reading Camunda --------------------------------------------------------------------------------------------------

    /** Waits for the one Process_PPC_TimesheetToProgress instance of this Work Pack and returns its key. */
    private String oneTimesheetInstance(Work wp) {
        List<String> found = poll(() -> timesheetInstances(wp.number()), list -> !list.isEmpty(), "the timesheet process of " + wp.number());
        assertThat(found).as("exactly one timesheet process for " + wp.number()).hasSize(1);
        return found.get(0);
    }

    /** The Process_PPC_TimesheetToProgress instances for this Work Pack number (found by the variable the message carries). */
    private static List<String> timesheetInstances(String workpackNumber) {
        List<String> found = new ArrayList<>();
        for (JsonNode variable : elements(IntegrationApp.admin.post("/v2/variables/search",
                Map.of("filter", Map.of("name", "workpackNumber"), "page", Map.of("limit", 1000))).body().path("items"))) {
            String instance = variable.path("processInstanceKey").asText();
            if (variable.path("value").asText().contains(workpackNumber) && !found.contains(instance)
                    && isActiveTimesheetInstance(instance)) {
                found.add(instance);
            }
        }
        return found;
    }

    /** Running instances only: the variables of a cancelled instance stay searchable, and a cancelled one is no "timesheet process running" (scenario 5). */
    private static boolean isActiveTimesheetInstance(String instance) {
        JsonNode found = IntegrationApp.admin.get("/v2/process-instances/" + instance).body();
        return EPIC2.equals(found.path("processDefinitionId").asText()) && "ACTIVE".equals(found.path("state").asText());
    }

    private static List<String> candidateGroups(String instance) {
        JsonNode task = CamundaSteps.awaitOpenTask(Long.parseLong(instance), "UserTask_BookHours");
        List<String> groups = new ArrayList<>();
        task.path("candidateGroups").forEach(g -> groups.add(g.asText()));
        return groups;
    }
}
