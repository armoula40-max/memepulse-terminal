import { COOKIE_NAME } from "../shared/const.js";
import { getSessionCookieOptions } from "./_core/cookies";
import { systemRouter } from "./_core/systemRouter";
import { publicProcedure, router } from "./_core/trpc";
import { fetchHistoricalOhlcv, fetchLatestMarketSnapshot, fetchRiskReport, fetchTokensByAddresses, fetchTokenSafety, forecastToken, getTokenHistory, videoStrategySignal, type MarketToken } from "./market-data";
import { z } from "zod";
import { getFirstTradeEvents, getNewTokenEvents, getNewTokenStreamStatus, startNewTokenStream } from "./new-token-stream";
import { analyzeLaunch } from "../lib/launch-analysis";
import { evaluateLaunchFilter } from "../lib/launch-filters";

let latestTokens: MarketToken[] = [];
function latestToken(address: string) { return latestTokens.find((item) => item.address === address); }
startNewTokenStream();

export const appRouter = router({
  // if you need to use socket.io, read and register route in server/_core/index.ts, all api should start with '/api/' so that the gateway can route correctly
  system: systemRouter,
  auth: router({
    me: publicProcedure.query((opts) => opts.ctx.user),
    logout: publicProcedure.mutation(({ ctx }) => {
      const cookieOptions = getSessionCookieOptions(ctx.req);
      ctx.res.clearCookie(COOKIE_NAME, { ...cookieOptions, maxAge: -1 });
      return {
        success: true,
      } as const;
    }),
  }),

  market: router({
    latest: publicProcedure.query(async () => ({
      source: "Dexscreener public API",
      chain: "solana",
      observedAt: new Date().toISOString(),
      tokens: (latestTokens = (await fetchLatestMarketSnapshot()).filter((token) => { const created = token.pairCreatedAt ? new Date(token.pairCreatedAt).getTime() : 0; const age = Date.now() - created; return created > 0 && age >= 0 && age <= 60 * 60 * 1000; }).slice(0, 150)),
    })),
    history: publicProcedure.input(z.object({ address: z.string().min(20).max(64) })).query(async ({ input }) => ({
      address: input.address,
      source: "server_snapshot_history",
      points: await getTokenHistory(input.address),
    })),
    ohlcv: publicProcedure.input(z.object({ address: z.string().min(20).max(64) })).query(async ({ input }) => ({
      address: input.address,
      source: "geckoterminal_public_api",
      points: await fetchHistoricalOhlcv(input.address),
    })),
    safety: publicProcedure.input(z.object({ address: z.string().min(20).max(64) })).query(({ input }) => fetchTokenSafety(input.address)),
    quality: publicProcedure.input(z.object({ addresses: z.array(z.string().min(20).max(64)).max(24) })).query(async ({ input }) => { const reports = await Promise.all(input.addresses.map(async (address) => [address, await fetchTokenSafety(address)] as const)); return Object.fromEntries(reports); }),
    strategy: publicProcedure.input(z.object({ address: z.string().min(20).max(64) })).query(async ({ input }) => { const token = latestToken(input.address); return token ? videoStrategySignal(token, await getTokenHistory(input.address)) : null; }),
    newTokens: publicProcedure.query(async () => { const events = getNewTokenEvents(); const firstTrades = getFirstTradeEvents(); const streamTokens = await fetchTokensByAddresses(events.slice(0, 150).map((event) => event.mint), 150); const tokens = latestTokens.length ? latestTokens : await fetchLatestMarketSnapshot(); const cutoff = Date.now() - 24 * 60 * 60 * 1000; const combined = Array.from(new Map([...streamTokens, ...tokens].map((token) => [token.address, token])).values()); const candidates = combined.filter((token) => { const created = token.pairCreatedAt ? new Date(token.pairCreatedAt).getTime() : 0; const age = Date.now() - created; const filter = evaluateLaunchFilter({ ageMinutes: Math.max(0, age / 60_000), volumeUsd: token.volume1hUsd, marketCapUsd: token.marketCapUsd, liquidityUsd: token.liquidityUsd, buys: token.buys1h, sells: token.sells1h, holders: null, migrated: false, globalFeesSol: null }); return created >= cutoff && filter.eligible; }); const holderChecks = await Promise.all(candidates.slice(0, 500).map(async (token) => ({ token, safety: await fetchTokenSafety(token.address) }))); const filtered = holderChecks.filter(({ token, safety }) => { const age = token.pairCreatedAt ? Date.now() - new Date(token.pairCreatedAt).getTime() : 0; return age <= 3 * 60 * 60 * 1000 || safety.topHolders.length > 0; }).map(({ token }) => token); const sortNewest = (list: MarketToken[]) => list.sort((a, b) => new Date(b.pairCreatedAt ?? 0).getTime() - new Date(a.pairCreatedAt ?? 0).getTime()); return { source: "PumpPortal stream plus Dexscreener enrichment", observedAt: new Date().toISOString(), stream: getNewTokenStreamStatus(), events, firstTrades: firstTrades.map((trade) => ({ ...trade, analysis: analyzeLaunch({ ...trade, initialBuy: events.find((event) => event.mint === trade.mint)?.initialBuy ?? null }) })), tokens: sortNewest(filtered), allTokens: sortNewest(combined.filter((token) => new Date(token.pairCreatedAt ?? 0).getTime() >= cutoff)) }; }),
    risk: publicProcedure.input(z.object({ address: z.string().min(20).max(64) })).query(({ input }) => fetchRiskReport(input.address)),
    forecast: publicProcedure.input(z.object({ address: z.string().min(20).max(64) })).query(({ input }) => {
      const token = latestToken(input.address);
      return token ? forecastToken(token) : null;
    }),
  }),

  // TODO: add feature routers here, e.g.
  // todo: router({
  //   list: protectedProcedure.query(({ ctx }) =>
  //     db.getUserTodos(ctx.user.id)
  //   ),
  // }),
});

export type AppRouter = typeof appRouter;
