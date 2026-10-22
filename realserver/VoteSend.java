import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Тестовый отправитель голоса по протоколу Votifier (только для локальных тестовых серверов).
 *   java VoteSend.java v1 <host> <port> <public.key> <service> <user> <address> <timestamp>
 *   java VoteSend.java v2 <host> <port> <token>      <service> <user> <address> <timestamp> [additionalData]
 * v1: RSA/PKCS1 блок 256 байт "VOTE\nservice\nuser\naddress\ntimestamp\n"; v2: NuVotifier JSON + HMAC-SHA256(token).
 */
public class VoteSend {
    public static void main(String[] a) throws Exception {
        String host = a[1]; int port = Integer.parseInt(a[2]);
        try (Socket s = new Socket(host, port)) {
            s.setSoTimeout(10_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String greet = in.readLine();
            System.out.println("greeting: " + greet);
            OutputStream out = s.getOutputStream();
            if (a[0].equals("v1")) {
                String b64 = new String(Files.readAllBytes(Paths.get(a[3])), StandardCharsets.US_ASCII).replaceAll("\\s", "");
                PublicKey pk = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
                Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                c.init(Cipher.ENCRYPT_MODE, pk);
                String msg = "VOTE\n" + a[4] + "\n" + a[5] + "\n" + a[6] + "\n" + a[7] + "\n";
                out.write(c.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
                out.flush();
                System.out.println("v1 sent");
            } else {
                String challenge = greet.split(" ")[2];
                StringBuilder p = new StringBuilder("{\"serviceName\":" + q(a[4]) + ",\"username\":" + q(a[5]) + ",\"address\":" + q(a[6])
                        + ",\"timestamp\":" + Long.parseLong(a[7]) + ",\"challenge\":" + q(challenge));
                if (a.length > 8) p.append(",\"additionalData\":").append(q(Base64.getEncoder().encodeToString(a[8].getBytes(StandardCharsets.UTF_8))));
                String payload = p.append('}').toString();
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(a[3].getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                String sig = Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
                byte[] msg = ("{\"payload\":" + q(payload) + ",\"signature\":" + q(sig) + "}").getBytes(StandardCharsets.UTF_8);
                DataOutputStream d = new DataOutputStream(out);
                d.writeShort(0x733A); d.writeShort(msg.length); d.write(msg); d.flush();
                System.out.println("v2 response: " + in.readLine());
            }
        }
    }
    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }
}
