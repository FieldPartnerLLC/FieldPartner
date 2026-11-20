import {
  AccessToken,
  AgentDispatchClient,
  EgressClient,
  EncodedFileOutput,
  EncodedFileType,
  RoomServiceClient,
  S3Upload,
  TrackSource,
  WebhookReceiver,
  type WebhookEvent,
} from "livekit-server-sdk";
import { config, features } from "../config.js";

function httpHost(): string {
  return config().LIVEKIT_URL.replace(/^wss:/, "https:").replace(/^ws:/, "http:");
}

export const identities = {
  tech: (userId: string) => `tech-${userId}`,
  expert: (userId: string) => `expert-${userId}`,
  isExpert: (identity: string) => identity.startsWith("expert-"),
  isTech: (identity: string) => identity.startsWith("tech-"),
};

export function roomNameForVisit(visitId: string): string {
  return `visit-${visitId}`;
}

export function visitIdFromRoom(room: string): string | null {
  return room.startsWith("visit-") ? room.slice("visit-".length) : null;
}

export interface Ticket {
  url: string;
  room: string;
  token: string;
  identity: string;
}

/** Technician: publishes camera + mic, subscribes to the AI and the expert. */
export async function techTicket(opts: { visitId: string; userId: string; name: string; metadata: object }): Promise<Ticket> {
  const room = roomNameForVisit(opts.visitId);
  const identity = identities.tech(opts.userId);
  const at = new AccessToken(config().LIVEKIT_API_KEY, config().LIVEKIT_API_SECRET, {
    identity,
    name: opts.name,
    ttl: "6h",
    metadata: JSON.stringify(opts.metadata),
  });
  at.addGrant({
    roomJoin: true,
    room,
    canPublish: true,
    canSubscribe: true,
    canPublishData: true,
    canPublishSources: [TrackSource.CAMERA, TrackSource.MICROPHONE],
  });
  return { url: config().LIVEKIT_URL, room, token: await at.toJwt(), identity };
}

/** Expert: watches the technician's video and talks. Cannot publish video. */
export async function expertTicket(opts: { visitId: string; userId: string; name: string }): Promise<Ticket> {
  const room = roomNameForVisit(opts.visitId);
  const identity = identities.expert(opts.userId);
  const at = new AccessToken(config().LIVEKIT_API_KEY, config().LIVEKIT_API_SECRET, {
    identity,
    name: opts.name,
    ttl: "2h",
    metadata: JSON.stringify({ role: "expert" }),
  });
  at.addGrant({
    roomJoin: true,
    room,
    canPublish: true,
    canSubscribe: true,
    canPublishData: true,
    canPublishSources: [TrackSource.MICROPHONE],
  });
  return { url: config().LIVEKIT_URL, room, token: await at.toJwt(), identity };
}

export async function ensureRoom(room: string): Promise<void> {
  const svc = new RoomServiceClient(httpHost(), config().LIVEKIT_API_KEY, config().LIVEKIT_API_SECRET);
  // A visit room survives short phone dropouts (basements, elevators): 10 minutes
  // with nobody in it before LiveKit closes it.
  await svc.createRoom({ name: room, emptyTimeout: 600, departureTimeout: 600, maxParticipants: 6 });
}

export async function dispatchAgent(room: string, metadata: object): Promise<void> {
  const client = new AgentDispatchClient(httpHost(), config().LIVEKIT_API_KEY, config().LIVEKIT_API_SECRET);
  const existing = await client.listDispatch(room).catch(() => []);
  if (existing.some((d) => d.agentName === config().LIVEKIT_AGENT_NAME)) return;
  await client.createDispatch(room, config().LIVEKIT_AGENT_NAME, { metadata: JSON.stringify(metadata) });
}

export async function listParticipantIdentities(room: string): Promise<string[]> {
  const svc = new RoomServiceClient(httpHost(), config().LIVEKIT_API_KEY, config().LIVEKIT_API_SECRET);
  try {
    const parts = await svc.listParticipants(room);
    return parts.map((p) => p.identity);
  } catch {
    return [];
  }
}

export async function closeRoom(room: string): Promise<void> {
  const svc = new RoomServiceClient(httpHost(), config().LIVEKIT_API_KEY, config().LIVEKIT_API_SECRET);
  await svc.deleteRoom(room).catch(() => {});
}

/** Starts a cloud recording of the room straight into S3. Returns the egress id. */
export async function startCloudRecording(room: string, s3Key: string, audioOnly: boolean): Promise<string | null> {
  if (!features.livekit() || !features.s3() || !config().CLOUD_RECORDING) return null;
  const c = config();
  const egress = new EgressClient(httpHost(), c.LIVEKIT_API_KEY, c.LIVEKIT_API_SECRET);
  const output = new EncodedFileOutput({
    fileType: audioOnly ? EncodedFileType.OGG : EncodedFileType.MP4,
    filepath: s3Key,
    output: {
      case: "s3",
      value: new S3Upload({
        accessKey: c.S3_ACCESS_KEY_ID ?? "",
        secret: c.S3_SECRET_ACCESS_KEY ?? "",
        region: c.S3_REGION,
        bucket: c.S3_BUCKET,
        endpoint: c.S3_ENDPOINT ?? "",
        forcePathStyle: Boolean(c.S3_ENDPOINT),
      }),
    },
  });
  const info = await egress.startRoomCompositeEgress(room, output, { audioOnly, layout: "speaker" });
  return info.egressId;
}

export async function stopCloudRecording(egressId: string): Promise<void> {
  const c = config();
  const egress = new EgressClient(httpHost(), c.LIVEKIT_API_KEY, c.LIVEKIT_API_SECRET);
  await egress.stopEgress(egressId).catch(() => {});
}

export async function receiveWebhook(body: string, auth: string | undefined): Promise<WebhookEvent> {
  const receiver = new WebhookReceiver(config().LIVEKIT_API_KEY, config().LIVEKIT_API_SECRET);
  return receiver.receive(body, auth);
}
