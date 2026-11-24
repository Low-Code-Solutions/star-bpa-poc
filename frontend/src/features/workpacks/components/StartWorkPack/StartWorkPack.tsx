import { useState } from 'react';
import { Button } from '@/components/common/Button';
import { ErrorMessage } from '@/components/common/ErrorMessage';
import { Field } from '@/components/common/Field';
import { Spinner } from '@/components/common/Spinner';
import { useMyProjects } from '@/features/projects';
import type { Api } from '@/services/apiClient';

/**
 * BPA-78: the Time & Method Analyst's "Start Work Pack request" with a Project picker that offers only the user's own projects (GET /api/v1/me/projects, BPA-77;
 * the first is preselected, ASSUMPTION). The start carries the project; the backend checks it with ProjectScope before an instance starts (403 otherwise, and the
 * caller shows its text). A user with no project has nothing to start for: "No projects assigned. Ask the PPC Team." and no button.
 */
export function StartWorkPack({ api, onStart }: { api: Api; onStart: (projectRef: string) => void }) {
  const projects = useMyProjects(api);
  const [picked, setPicked] = useState('');

  if (projects.data === undefined) {
    return <Spinner />;
  }
  if (projects.data === null) {
    return <ErrorMessage text="The projects could not be loaded" />;
  }
  if (projects.data.length === 0) {
    return <p>No projects assigned. Ask the PPC Team.</p>;
  }
  const selected = projects.data.some((p) => p.projectRef === picked) ? picked : projects.data[0].projectRef;
  return (
    <form className="filters" onSubmit={(e) => e.preventDefault()}>
      <Field label="Project">
        <select value={selected} onChange={(e) => setPicked(e.target.value)}>
          {projects.data.map((p) => (
            <option key={p.projectRef} value={p.projectRef}>
              {p.projectRef}
            </option>
          ))}
        </select>
      </Field>
      <Button onClick={() => onStart(selected)}>Start Work Pack request</Button>
    </form>
  );
}
