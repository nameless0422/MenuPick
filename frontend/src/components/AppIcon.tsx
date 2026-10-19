const paths = {
  pick: "m12 3 1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3Zm7 12 1 3 3 1-3 1-1 3-1-3-3-1 3-1 1-3Z",
  menu: "M4 4h6v16H4V4Zm10 0h6v7h-6V4Zm0 11h6v5h-6v-5Z",
  pin: "M20 10c0 6-8 11-8 11S4 16 4 10a8 8 0 1 1 16 0ZM15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z",
  history: "M3 11a9 9 0 1 1 2 7M3 4v7h7m2-5v6l4 2",
  settings: "M4 7h16M4 17h16M8 4v6m8 4v6",
  logout: "M9 4H4v16h5m6-13 5 5-5 5M9 12h11",
  arrow: "M5 12h14m-5-5 5 5-5 5",
  leaf: "M20 4C8 2 2 10 7 16s15 2 13-12ZM5 21 16 9",
};
export default function AppIcon({ name }: { name: keyof typeof paths }) {
  return <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor"
    strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
    <path d={paths[name]} />
  </svg>;
}
