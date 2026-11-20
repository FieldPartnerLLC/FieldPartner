import { describe, expect, it } from "vitest";
import { decodeJwt } from "jose";
import { setTestEnv } from "./helpers.js";

describe("LiveKit tickets", () => {
  it("technician can publish camera and mic in only their visit room", async () => {
    setTestEnv({ LIVEKIT_URL: "wss://x.livekit.cloud", LIVEKIT_API_KEY: "key", LIVEKIT_API_SECRET: "secret-secret-secret-secret-secret" });
    const { resetConfig } = await import("../src/config.js");
    resetConfig();
    const lk = await import("../src/lib/livekit.js");
    const t = await lk.techTicket({ visitId: "v1", userId: "u1", name: "Nick", metadata: { trade: "hvac" } });
    const claims: any = decodeJwt(t.token);
    expect(t.room).toBe("visit-v1");
    expect(claims.sub).toBe("tech-u1");
    expect(claims.video.room).toBe("visit-v1");
    expect(claims.video.canPublishSources).toEqual(["camera", "microphone"]);
  });

  it("expert can talk but cannot publish video", async () => {
    const lk = await import("../src/lib/livekit.js");
    const t = await lk.expertTicket({ visitId: "v1", userId: "e1", name: "Mitch" });
    const claims: any = decodeJwt(t.token);
    expect(claims.sub).toBe("expert-e1");
    expect(claims.video.canPublishSources).toEqual(["microphone"]);
    expect(lk.identities.isExpert(claims.sub)).toBe(true);
    expect(lk.visitIdFromRoom("visit-abc")).toBe("abc");
    expect(lk.visitIdFromRoom("other")).toBeNull();
  });
});
