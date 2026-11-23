import { useParams } from 'react-router';
import { WorkPackDetail } from '@/features/workpacks';
import type { Api } from '@/services/apiClient';

/** BPA-73: the Work Pack detail at /workpacks/:workpackNumber (replaces BPA-72's placeholder). Thin: the feature does the work. */
export function WorkPackDetailPage({ api }: { api: Api }) {
  const { workpackNumber = '' } = useParams();
  return <WorkPackDetail api={api} workpackNumber={workpackNumber} />;
}
