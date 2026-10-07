import { WebSocketServer } from 'ws';

const port = Number(process.env.PORT || 8787);
const rooms = new Map();
const clients = new Map();

function roomRecords(room) {
  if (!rooms.has(room)) rooms.set(room, new Map());
  return rooms.get(room);
}
function send(ws, value) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(value));
}

const wss = new WebSocketServer({ port, host: '0.0.0.0' });
wss.on('connection', ws => {
  ws.on('message', raw => {
    try {
      const msg = JSON.parse(raw.toString());
      if (msg.type === 'hello' && typeof msg.room === 'string' && msg.room.trim()) {
        const room = msg.room.trim();
        clients.set(ws, room);
        send(ws, { type: 'state', records: [...roomRecords(room).values()] });
        return;
      }
      if (msg.type !== 'record' || !msg.record?.syncId) return;
      const room = clients.get(ws);
      if (!room) return;
      const records = roomRecords(room);
      if (!records.has(msg.record.syncId)) records.set(msg.record.syncId, msg.record);
      for (const [peer, peerRoom] of clients) {
        if (peerRoom === room && peer !== ws) send(peer, { type: 'record', record: msg.record });
      }
    } catch { /* odrzuć niepoprawny komunikat */ }
  });
  ws.on('close', () => clients.delete(ws));
});

console.log(`DDMS Ratio sync server listening on ws://0.0.0.0:${port}`);
