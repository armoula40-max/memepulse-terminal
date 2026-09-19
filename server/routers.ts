import { COOKIE_NAME } from "../shared/const.js";
import { getSessionCookieOptions } from "./_core/cookies";
import { systemRouter } from "./_core/systemRouter";
import { publicProcedure, router } from "./_core/trpc";
import {
  fetchHistoricalOhlcv,
  fetchLatestMarketSnapshot,
  fetchRiskReport,
  fetchTokensByAddresses,
  fetchTokenSafety,
  forecastToken,
  getTokenHistory,
  videoStrategySignal,
  type MarketToken,
} from "./market-data";
import { z } from "zod";
import {
  getNewTokenEvents,
  getNewTokenMints,
  getNewTokenStreamStatus,
  startNewTokenStream,
} from "./new-token-stream";

let latestTokens: MarketToken[] = [];
function latestToken(address: string) {
  return latestTokens.find((item) => item.address === address);
}
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
    latest: publicProcedure.query(async () => {
      const dexTokens = fetchLatestMarketSnapshot();
      const streamTokens = fetchTokensByAddresses(getNewTokenMints(150), 150);
      const [dex, stream] = await Promise.all([dexTokens, streamTokens]);
      const combined = Array.from(
        new Map(
          [...stream, ...dex].map((token) => [token.address, token]),
        ).values(),
      );
      latestTokens = combined.slice(0, 150);
      return {
        source: stream.length
          ? "PumpPortal live stream + Dexscreener enrichment"
          : "Dexscreener public API",
        chain: "solana",
        observedAt: new Date().toISOString(),
        tokens: latestTokens,
      };
    }),
    history: publicProcedure
      .input(z.object({ address: z.string().min(20).max(64) }))
      .query(async ({ input }) => ({
        address: input.address,
        source: "server_snapshot_history",
        points: await getTokenHistory(input.address),
      })),
    ohlcv: publicProcedure
      .input(z.object({ address: z.string().min(20).max(64) }))
      .query(async ({ input }) => ({
        address: input.address,
        source: "geckoterminal_public_api",
        points: await fetchHistoricalOhlcv(input.address),
      })),
    safety: publicProcedure
      .input(z.object({ address: z.string().min(20).max(64) }))
      .query(({ input }) => fetchTokenSafety(input.address)),
    strategy: publicProcedure
      .input(z.object({ address: z.string().min(20).max(64) }))
      .query(async ({ input }) => {
        const token = latestToken(input.address);
        return token
          ? videoStrategySignal(token, await getTokenHistory(input.address))
          : null;
      }),
    newTokens: publicProcedure.query(async () => {
      const events = getNewTokenEvents();
      const streamTokens = await fetchTokensByAddresses(
        getNewTokenMints(150),
        150,
      );
      const tokens = latestTokens.length
        ? latestTokens
        : await fetchLatestMarketSnapshot();
      const cutoff = Date.now() - 24 * 60 * 60 * 1000;
      const combined = Array.from(
        new Map(
          [...streamTokens, ...tokens].map((token) => [token.address, token]),
        ).values(),
      );
      return {
        source: "PumpPortal stream plus Dexscreener enrichment",
        observedAt: new Date().toISOString(),
        stream: getNewTokenStreamStatus(),
        events,
        tokens: combined
          .filter(
            (token) =>
              token.pairCreatedAt &&
              new Date(token.pairCreatedAt).getTime() >= cutoff,
          )
          .sort(
            (a, b) =>
              new Date(b.pairCreatedAt ?? 0).getTime() -
              new Date(a.pairCreatedAt ?? 0).getTime(),
          )
          .slice(0, 150),
      };
    }),
    risk: publicProcedure
      .input(z.object({ address: z.string().min(20).max(64) }))
      .query(({ input }) => fetchRiskReport(input.address)),
    forecast: publicProcedure
      .input(z.object({ address: z.string().min(20).max(64) }))
      .query(({ input }) => {
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
