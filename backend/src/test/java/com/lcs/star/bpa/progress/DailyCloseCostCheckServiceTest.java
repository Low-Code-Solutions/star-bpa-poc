package com.lcs.star.bpa.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcs.star.bpa.adapter.CamundaDecisionClient;
import com.lcs.star.bpa.config.AlertSettings;
import com.lcs.star.bpa.progress.VarianceAlertService.CostOver;
import com.lcs.star.bpa.progress.VarianceCheckService.Check;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * BPA-65, QA review: one CBS that cannot be checked or alerted must not cost the other CBS their alert for the day. The daily close check has no Camunda retries and runs once
 * a day at 06:00 (plan Q1 A), so a failure for one CBS (the decision cannot be evaluated, or the alert row cannot be written) is logged and the next CBS is still checked and
 * alerted; at the end the run fails once, naming the CBS that failed, so the job logs it as an error. Mocks only: no database, no Camunda.
 */
class DailyCloseCostCheckServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 28); // inside the close of lock day 1
    private static final ObjectMapper JSON = new ObjectMapper();

    private final VarianceCheckService checks = mock(VarianceCheckService.class);
    private final CamundaDecisionClient decisions = mock(CamundaDecisionClient.class);
    private final VarianceAlertService alerts = mock(VarianceAlertService.class);
    private final AlertSettings settings = mock(AlertSettings.class);
    private DailyCloseCostCheckService service;

    @BeforeEach
    void service() throws Exception {
        when(settings.monthLockDay()).thenReturn(1);
        when(checks.checks(DAY)).thenReturn(List.of(check("CBS-100-AAA-01", "100.00", "200.00"), check("CBS-139-PIP-01", "250000.00", "300000.00")));
        service = new DailyCloseCostCheckService(checks, decisions, alerts, settings);
    }

    @Test
    void aCbsWhoseDecisionCannotBeEvaluatedDoesNotStopTheOthers() throws Exception {
        when(decisions.evaluate(eq("Decision_CostVariance"), anyMap())).thenThrow(new IllegalStateException("decision not deployed yet"))
                .thenReturn(JSON.readTree("{\"costAlert\":true,\"costResult\":\"OVER_10_PCT\"}"));

        assertThatThrownBy(() -> service.runFor(DAY)).isInstanceOf(IllegalStateException.class).hasMessageContaining("CBS-100-AAA-01");

        ArgumentCaptor<List<CostOver>> sent = ArgumentCaptor.forClass(List.class);
        verify(alerts).sendCostAlerts(isNull(), eq(DAY), sent.capture(), anyString());
        assertThat(sent.getValue()).extracting(CostOver::cbsCodeRef).as("the second CBS is still alerted").containsExactly("CBS-139-PIP-01");
    }

    @Test
    void aCbsWhoseAlertCannotBeSentDoesNotStopTheOthers() throws Exception {
        when(decisions.evaluate(eq("Decision_CostVariance"), anyMap())).thenReturn(JSON.readTree("{\"costAlert\":true,\"costResult\":\"OVER_10_PCT\"}"));
        doThrow(new IllegalStateException("numeric field overflow")).doNothing().when(alerts).sendCostAlerts(any(), any(), anyList(), anyString());

        assertThatThrownBy(() -> service.runFor(DAY)).isInstanceOf(IllegalStateException.class).hasMessageContaining("CBS-100-AAA-01");

        verify(alerts, times(2)).sendCostAlerts(isNull(), eq(DAY), anyList(), anyString());
    }

    private static Check check(String cbs, String budget, String cost) {
        return new Check(cbs, BigDecimal.ZERO, null, new BigDecimal(budget), new BigDecimal(cost));
    }
}
