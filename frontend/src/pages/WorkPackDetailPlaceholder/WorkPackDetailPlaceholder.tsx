import { Link, useParams } from 'react-router';

/** BPA-72 placeholder for /workpacks/:workpackNumber: the page a row of the list opens until BPA-73 builds the Work Pack detail (plan BPA-72 Q2). */
export function WorkPackDetailPlaceholder() {
  const { workpackNumber } = useParams();
  return (
    <>
      <h2>Work Pack {workpackNumber}</h2>
      <p>The Work Pack detail comes with BPA-73.</p>
      <p>
        <Link to="/workpacks">Back to the Work Packs list</Link>
      </p>
    </>
  );
}
