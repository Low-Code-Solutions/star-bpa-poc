const STEPS = ['Draft', 'Released', 'In Progress', 'Completed'];

/**
 * BPA-73: the lifecycle bar of a Work Pack (FRD-7 §7.8.2.4 statuses): Draft, Released, In Progress, Completed in order. The current status is marked
 * (aria-current="step"), the ones before it are done, the ones after it are not reached. Read only; nothing here changes the status.
 */
export function LifecycleBar({ status }: { status: string }) {
  const current = STEPS.indexOf(status);
  return (
    <ol className="lifecycle" aria-label="Lifecycle">
      {STEPS.map((step, i) => {
        const state = i < current ? 'done' : i === current ? 'current' : 'next';
        return (
          <li
            key={step}
            className={`lifecycle__step lifecycle__step--${state}`}
            aria-current={state === 'current' ? 'step' : undefined}
          >
            {step}
            <span className="visually-hidden">
              {state === 'done' ? ' (done)' : state === 'current' ? ' (current)' : ' (not reached)'}
            </span>
          </li>
        );
      })}
    </ol>
  );
}
