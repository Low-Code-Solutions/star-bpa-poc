package com.lcs.star.bpa.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lcs.star.bpa.progress.CompletionApprovalService.Approval;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * BPA-60: reading the {@code completionApprovals} payload of the Approve completion % task, pure. Not Gherkin scenarios themselves;
 * CompletionApprovalApiTest proves the rules through the task API. The texts are ASSUMPTIONs (plan Q11).
 */
class CompletionApprovalServiceTest {

    private static List<Approval> parse(Object pct) {
        return CompletionApprovalService.parse(List.of(Map.of("workpackNumber", "WP-000457", "approvedPct", pct)));
    }

    @Test
    void aNumberOrADecimalTextIsRead() {
        assertThat(parse(40).get(0).approvedPct()).isEqualByComparingTo("40");
        assertThat(parse(new BigDecimal("38.10")).get(0).approvedPct()).isEqualByComparingTo("38.1");
        assertThat(parse("38.1").get(0).approvedPct()).isEqualByComparingTo("38.1");
        assertThat(parse("+5").get(0).approvedPct()).isEqualByComparingTo("5");
    }

    /** QA review: new BigDecimal("1e1") is 10; a % is a plain decimal, so an exponent, a comma or text is "must be a number". */
    @Test
    void anExponentACommaOrTextIsNotANumber() {
        for (String bad : new String[] {"1e1", "0x10", "40,5", "forty", " ", ""}) {
            assertThatThrownBy(() -> parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class).hasMessage("Completion % must be a number");
        }
    }

    @Test
    void aMissingWorkPackNumberIsNotCovered() {
        assertThatThrownBy(() -> CompletionApprovalService.parse(List.of(Map.of("approvedPct", 40)))).hasMessage("Approvals must cover every Work Pack of the week");
    }
}
