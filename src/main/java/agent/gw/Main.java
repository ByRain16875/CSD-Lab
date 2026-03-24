// Language: Java
package agent.gw;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

public class Main {

    /* ========== SECTION: ENTRY ========== */
    // [INTENT] Teaching skeleton for START/TOKEN*/DONE semantics over WS JSON text frames.
    public static void main(String[] args) {
        Session session = new Session("s-01", "w-1", 3);
        String reqId = "r-" + UUID.randomUUID();

        // [PROTO] START carries request identity; server decides worker route.
        logRoute(reqId, session.sessionId, session.workerId, 1, false);

        MockTokenSource src = new MockTokenSource(new String[]{"你", "好", "CSD"});
        int seq = 0;
        while (src.hasNext()) {
            seq++;
            Envelope tokenEvt = Envelope.token(reqId, seq, src.next());
            streamToken(session, tokenEvt);
        }

        // [PROTO] DONE is terminal. No TOKEN should be emitted after DONE.
        Envelope doneEvt = Envelope.done(reqId, seq + 1);
        streamToken(session, doneEvt);
    }

    /* ========== SECTION: STREAM PIPELINE ========== */
    private static void streamToken(Session session, Envelope evt) {
        if (evt.type.equals("TOKEN")) {
            if (!session.writable) {
                // [BACKPRESSURE] Queue when channel is not writable.
                if (session.outbound.size() >= session.queueLimit) {
                    emitErrorAndClose(session, evt.reqId, "OVERLOADED", false, "queue_limit");
                    return;
                }
                session.outbound.add(evt);
            } else {
                flushToWire(session, evt);
            }
        } else {
            flushToWire(session, evt);
        }
    }

    private static void flushToWire(Session session, Envelope evt) {
        // [OBS] Keep log format stable for evidence-chain parsing.
        System.out.printf(
                "ts=%s event=token session=%s req=%s seq=%d worker=%s qlen=%d writable=%s type=%s%n",
                Instant.now(), session.sessionId, evt.reqId, evt.seq, session.workerId,
                session.outbound.size(), session.writable, evt.type
        );
    }

    private static void emitErrorAndClose(Session s, String reqId, String code, boolean retryable, String msg) {
        // [EDGE] ERROR terminal semantic: send once then close stream.
        System.out.printf(
                "ts=%s event=error req=%s code=%s retryable=%s reason=%s close=true%n",
                Instant.now(), reqId, code, retryable, msg
        );
        s.writable = false;
    }

    private static void logRoute(String reqId, String sessionId, String workerId, int inflight, boolean overloaded) {
        System.out.printf(
                "ts=%s event=route req=%s session=%s worker=%s inflight=%d overloaded=%s%n",
                Instant.now(), reqId, sessionId, workerId, inflight, overloaded
        );
    }

    /* ========== SECTION: DATA TYPES ========== */
    static class Session {
        final String sessionId;
        final String workerId;
        final int queueLimit;
        final Queue<Envelope> outbound = new ArrayDeque<>();
        boolean writable = true;

        Session(String sessionId, String workerId, int queueLimit) {
            this.sessionId = sessionId;
            this.workerId = workerId;
            this.queueLimit = queueLimit;
        }
    }

    static class Envelope {
        final String type;
        final String reqId;
        final int seq;
        final String token;

        private Envelope(String type, String reqId, int seq, String token) {
            this.type = type;
            this.reqId = reqId;
            this.seq = seq;
            this.token = token;
        }

        static Envelope token(String reqId, int seq, String token) {
            return new Envelope("TOKEN", reqId, seq, token);
        }

        static Envelope done(String reqId, int seq) {
            return new Envelope("DONE", reqId, seq, "");
        }
    }

    static class MockTokenSource {
        private final String[] tokens;
        private int idx = 0;

        MockTokenSource(String[] tokens) {
            this.tokens = tokens;
        }

        boolean hasNext() {
            return idx < tokens.length;
        }

        String next() {
            return tokens[idx++];
        }
    }
}