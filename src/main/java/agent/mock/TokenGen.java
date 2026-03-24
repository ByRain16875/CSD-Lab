// Language: Java
package agent.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class TokenGen {

    /* ========== SECTION: RUNTIME TOKEN SOURCE ========== */
    // [INTENT] Deterministic token stream for reproducible START/TOKEN*/DONE demos.
    public static List<String> generate(String prompt) {
        List<String> out = new ArrayList<>();
        out.add("TOKEN:" + prompt);
        out.add("TOKEN:processing");
        out.add("TOKEN:done");
        return out;
    }

    public static void main(String[] args) {
        String reqId = args.length > 0 ? args[0] : "r-demo";
        String prompt = args.length > 1 ? args[1] : "hello";

        int seq = 0;
        for (String token : generate(prompt)) {
            seq++;
            // [OBS] Compatible with gateway evidence chain: reqId/seq are explicit.
            System.out.printf(
                    "ts=%s event=token req=%s seq=%d token=%s qlen=0 writable=true%n",
                    Instant.now(), reqId, seq, token
            );
        }

        // [PROTO] DONE closes semantic stream.
        System.out.printf(
                "ts=%s event=done req=%s seq=%d done=true%n",
                Instant.now(), reqId, seq + 1
        );
    }
}