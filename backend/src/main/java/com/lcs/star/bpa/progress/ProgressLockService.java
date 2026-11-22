package com.lcs.star.bpa.progress;

import com.lcs.star.bpa.persistence.ProgressPeriodRepository;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.Period;
import com.lcs.star.bpa.persistence.ProgressPeriodRepository.State;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks a week (BPA-67; E2-16; FRD-21 §21.9.1), run by {@code ServiceTask_LockProgressPeriod} after the Work Packs are completed: the period goes from CutOff to Locked with
 * {@code locked_at} = now, in one transaction with the period row held, so no write to the week's rows ({@code requireOpen}) interleaves with the lock. From then on the week's
 * {@code workpack_progress} and {@code activity_progress} rows never change: every writer refuses with "Week ending <d MMM yyyy> is locked". A period that is Locked already writes
 * nothing (the re-run key). Reopening a week is out of scope (FRD-21 §21.7 PM reversal is not built).
 */
@Service
public class ProgressLockService {

    private final ProgressPeriodRepository periods;

    public ProgressLockService(ProgressPeriodRepository periods) {
        this.periods = periods;
    }

    /** Returns true when this call locked the week, false when it was Locked already. */
    @Transactional
    public boolean lock(Period period, String username) {
        State state = periods.lockState(period.id()).orElseThrow(() -> new IllegalArgumentException(WeekDecisionService.NO_PERIOD));
        return periods.lock(state, username);
    }
}
