/** SVG paths of the docs/ui-prototype templates (24 x 24 viewBox, line icons). Decorative: aria-hidden. */
export const ICON_PATHS = {
  menu: 'M4 6h16M4 12h16M4 18h16',
  moon: 'M21 12.8A9 9 0 1111.2 3a7 7 0 009.8 9.8z',
  logout: 'M9 21H5V3h4M16 17l5-5-5-5M21 12H9',
  dash: 'M3 3h8v8H3zM13 3h8v8h-8zM3 13h8v8H3zM13 13h8v8h-8z',
  alert: 'M12 21a9 9 0 100-18 9 9 0 000 18zM12 8v4M12 16h.01',
} as const;

export type IconName = keyof typeof ICON_PATHS;

export function Icon({
  name,
  size = 18,
  strokeWidth = 2,
  stroke = 'currentColor',
}: {
  name: IconName;
  size?: number;
  strokeWidth?: number;
  stroke?: string;
}) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={stroke} strokeWidth={strokeWidth} aria-hidden="true">
      <path d={ICON_PATHS[name]} />
    </svg>
  );
}
