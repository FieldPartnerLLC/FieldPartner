import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  LiveKitRoom,
  RoomAudioRenderer,
  StartAudio,
  TrackToggle,
  VideoTrack,
  useConnectionState,
  useParticipants,
  useRoomContext,
  useTracks,
} from "@livekit/components-react";
import { ConnectionState, Track, type RemoteParticipant } from "livekit-client";
import { api } from "./api";

/** The expert's live view: the technician's glasses video, two-way audio, snapshots. */
export function LiveVisit() {
  const { id } = useParams();
  const nav = useNavigate();
  const [ticket, setTicket] = useState<{ url: string; token: string } | null>(null);
  const [err, setErr] = useState("");
  const [detail, setDetail] = useState<any>(null);

  useEffect(() => {
    api(`/visits/${id}/expert-ticket`, { method: "POST" })
      .then(setTicket)
      .catch((e) => setErr(e.message));
    api(`/visits/${id}`).then(setDetail).catch(() => {});
  }, [id]);

  if (err)
    return (
      <div className="center">
        <div className="card narrow">
          <p className="error">{err}</p>
          <Link to="/">Back</Link>
        </div>
      </div>
    );
  if (!ticket) return <div className="center">Connecting...</div>;

  return (
    <LiveKitRoom
      serverUrl={ticket.url}
      token={ticket.token}
      connect
      audio
      video={false}
      onDisconnected={() => nav(`/visits/${id}`)}
      className="live"
    >
      <LiveInner visitId={id!} detail={detail} />
      <RoomAudioRenderer />
    </LiveKitRoom>
  );
}

function LiveInner({ visitId, detail }: { visitId: string; detail: any }) {
  const room = useRoomContext();
  const state = useConnectionState();
  const participants = useParticipants();
  const tracks = useTracks([Track.Source.Camera], { onlySubscribed: true });
  const techTrack = tracks.find((t) => t.participant.identity.startsWith("tech-"));
  const videoBox = useRef<HTMLDivElement>(null);
  const [saved, setSaved] = useState("");
  const [note, setNote] = useState("");
  const tech = participants.find((p) => p.identity.startsWith("tech-")) as RemoteParticipant | undefined;
  const aiHere = participants.some((p) => p.identity.startsWith("agent") || (p as any).isAgent);

  async function snapshot() {
    const video = videoBox.current?.querySelector("video");
    if (!video || !video.videoWidth) return;
    const canvas = document.createElement("canvas");
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    canvas.getContext("2d")!.drawImage(video, 0, 0);
    const jpeg = canvas.toDataURL("image/jpeg", 0.85).split(",")[1];
    await api(`/visits/${visitId}/snapshots`, { body: { jpegBase64: jpeg, note: note || undefined } });
    setSaved(`Snapshot saved ${new Date().toLocaleTimeString()}`);
    setNote("");
  }

  async function endHelp() {
    await api(`/visits/${visitId}/escalation/end`, { method: "POST" }).catch(() => {});
    await room.disconnect();
  }

  return (
    <div className="livegrid">
      <div className="videopane" ref={videoBox}>
        {techTrack ? (
          <VideoTrack trackRef={techTrack} />
        ) : (
          <div className="novideo">{tech ? "Technician connected. Waiting for video (camera may be paused for privacy)." : "Waiting for the technician to connect..."}</div>
        )}
      </div>
      <aside className="side">
        <h2>{detail?.job?.title ?? "Live visit"}</h2>
        <p className="muted">{detail?.job?.trade} · {detail?.tech?.name} · {detail?.job?.address}</p>
        <p>
          Connection: <b>{state === ConnectionState.Connected ? "connected" : state}</b>
          <br />
          AI partner: <b>{aiHere ? "in the call (muted while you're here)" : "not in the call"}</b>
        </p>
        <StartAudio label="Click to enable audio" />
        <div className="row">
          <TrackToggle source={Track.Source.Microphone}>Mic</TrackToggle>
          <button className="primary" onClick={snapshot} disabled={!techTrack}>Snapshot</button>
        </div>
        <input placeholder="Note for this snapshot (optional)" value={note} onChange={(e) => setNote(e.target.value)} />
        {saved && <p className="muted small">{saved}</p>}
        <h3>Problem</h3>
        <p>{detail?.job?.complaint ?? "-"}</p>
        <h3>Readings so far</h3>
        <ul>
          {(detail?.events ?? [])
            .filter((e: any) => e.type === "reading")
            .map((e: any) => (
              <li key={e.id}>{e.data.name}: {e.data.value} {e.data.unit}</li>
            ))}
        </ul>
        <button className="danger" onClick={endHelp}>Done helping, leave call</button>
      </aside>
    </div>
  );
}
