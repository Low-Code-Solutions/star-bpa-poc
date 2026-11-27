/**
 * The eleven personas (docs/04; personas.yaml): a user's one Camunda group names the role. The display names, departments and initials are the ones the ui-design
 * templates show (ASSUMPTION: no source names them, docs/ui-screen-map.md Q7 and Q18). Unknown groups fall back to the group ID.
 */
export type Persona = { name: string; department: string; initials: string };

export const PERSONAS: Record<string, Persona> = {
  'time-method-analyst': { name: 'Time & Method Analyst', department: 'PPC', initials: 'TM' },
  'project-engineer': { name: 'Project Engineer', department: 'Engineering', initials: 'PE' },
  'production-planner': { name: 'Production Planner', department: 'PPC', initials: 'PL' },
  'production-team': { name: 'Production Team', department: 'Production', initials: 'PT' },
  'ppc-team': { name: 'PPC Team', department: 'PPC', initials: 'PC' },
  supervisor: { name: 'Supervisor', department: 'Production', initials: 'SV' },
  'workpack-owner': { name: 'Workpack Owner', department: 'Production', initials: 'WO' },
  timekeeper: { name: 'Timekeeper', department: 'Production', initials: 'TK' },
  'production-supervisor': { name: 'Production Supervisor', department: 'Production', initials: 'PS' },
  'project-manager': { name: 'Project Manager', department: 'Project Management', initials: 'PM' },
  'cost-controller': { name: 'Cost Controller', department: 'PCON', initials: 'CC' },
};

/** The persona of a session: the first of its groups that is a known persona, else the first group (or the username) as it is. */
export function personaOf(me: { username: string; groups: string[] }): Persona {
  const known = me.groups.find((g) => PERSONAS[g]);
  if (known) {
    return PERSONAS[known];
  }
  const label = me.groups[0] ?? me.username;
  return { name: label, department: '', initials: label.slice(0, 2).toUpperCase() };
}
