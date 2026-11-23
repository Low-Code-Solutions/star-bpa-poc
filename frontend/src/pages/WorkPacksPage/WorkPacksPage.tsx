import { WorkPackList } from '@/features/workpacks';
import type { Api } from '@/services/apiClient';

/** BPA-72: the Work Packs list under Home, read only. Thin: the feature does the work. */
export function WorkPacksPage({ api }: { api: Api }) {
  return (
    <>
      <h2>Work Packs</h2>
      <WorkPackList api={api} />
    </>
  );
}
