// An early_warning.json (CONTRACTS.md) for the tests of the early warning section.
const warnings = (p7, p14, p30) => [
  { horizon_days: 7, p_at_risk: null, p_isolated: p7 },
  { horizon_days: 14, p_at_risk: null, p_isolated: p14 },
  { horizon_days: 30, p_at_risk: null, p_isolated: p30 },
];

export const REPORT = {
  schema_version: "1.0.0",
  aoi_id: "tefe",
  experimental: true,
  issued_at: "2026-09-01",
  method: { summary: "The river twin.", limitations: ["Width is not depth.", "A hindcast is not a promise."] },
  level_forecast: [
    { date: "2026-09-02", p10_cm: 850, p50_cm: 880, p90_cm: 910 },
    { date: "2026-09-03", p10_cm: 840, p50_cm: 875, p90_cm: 905 },
  ],
  observed_level: [
    { date: "2026-08-31", level_cm: 900 },
    { date: "2026-09-02", level_cm: 885 },
  ],
  communities: [
    {
      community_id: "late",
      class: "monitored",
      isolated_below_cm: 700,
      warnings: warnings(0, 0.05, 0.2),
      expected_cutoff_date: "2026-10-20",
    },
    {
      community_id: "never",
      class: "monitored",
      isolated_below_cm: null,
      warnings: warnings(null, null, null),
      expected_cutoff_date: null,
    },
    {
      community_id: "soon",
      class: "monitored",
      at_risk_below_cm: 900,
      isolated_below_cm: 860,
      warnings: warnings(0.4, 0.7, 0.9),
      expected_cutoff_date: "2026-09-08",
    },
    { community_id: "hill", class: "inland", warnings: [], expected_cutoff_date: null },
  ],
  hindcast: {
    issues: ["2026-08-01", "2026-08-15", "2026-09-01"],
    false_alarms: ["late"],
    summary: { median_lead_days: 23.5, warned: 4, observed: 6, false_alarms: 1 },
  },
};
