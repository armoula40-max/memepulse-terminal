export type LaunchObservation = { marketCapSol: number | null; initialBuy: number | null; txType: "buy" | "sell"; solAmount: number | null };
export type LaunchSignal = { score: number; verdict: "WATCH" | "PROMISING" | "DANGER"; reasons: string[] };

export function analyzeLaunch(observation: LaunchObservation): LaunchSignal {
  let score = 50;
  const reasons: string[] = [];
  if (observation.txType === "buy") { score += 15; reasons.push("first observed flow is a buy"); } else { score -= 25; reasons.push("first observed flow is a sell"); }
  if ((observation.initialBuy ?? 0) > 0) { score += Math.min(20, observation.initialBuy! / 2); reasons.push("creator initial buy observed"); }
  if ((observation.solAmount ?? 0) >= 1) { score += 10; reasons.push("first trade has meaningful SOL size"); }
  if ((observation.marketCapSol ?? 0) < 5) { score -= 15; reasons.push("very early and highly volatile market cap"); }
  const bounded = Math.max(0, Math.min(100, Math.round(score)));
  return { score: bounded, verdict: bounded >= 70 ? "PROMISING" : bounded < 40 ? "DANGER" : "WATCH", reasons };
}
