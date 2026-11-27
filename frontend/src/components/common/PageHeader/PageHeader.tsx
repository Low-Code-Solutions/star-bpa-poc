import type { ReactNode } from 'react';
import { Icon, type IconName } from '../Icon';

/**
 * The page header of the docs/ui-prototype templates (docs/ui-screen-map.md 11.3): a 52 px icon tile, the h1, a sub-title and, at the right, the page's actions.
 * Breadcrumb, "Data as of", the date range and Export come with the stories that need them.
 */
export function PageHeader({
  title,
  subtitle,
  icon,
  actions,
}: {
  title: string;
  subtitle?: string;
  icon: IconName;
  actions?: ReactNode;
}) {
  return (
    <div className="page-header">
      <span className="page-header__tile" aria-hidden="true">
        <Icon name={icon} size={26} strokeWidth={1.8} stroke="var(--primary)" />
      </span>
      <div className="page-header__title">
        <h1>{title}</h1>
        {subtitle ? <div className="page-header__sub">{subtitle}</div> : null}
      </div>
      {actions ? <div className="page-header__actions">{actions}</div> : null}
    </div>
  );
}
