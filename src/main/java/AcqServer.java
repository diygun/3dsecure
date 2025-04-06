// Beggining of AcqServer.java //
import javax.net.ssl.*;
import java.io.*;
import java.security.KeyStore;
// Removed unused imports: PrivateKey, Signature

import org.json.JSONObject; // Added for JSON handling
import org.json.JSONException;

public class AcqServer {
    // Port for listening to HttpsServer
    private static final int ACQ_LISTEN_PORT = 8443;
    // Port for connecting to AcsServer
    private static final int ACS_CONNECT_PORT = 5050;
    private static final String ACS_HOST = "localhost"; // Assuming ACS is on the same machine

    // Keystore for ACQ's identity (private key)
    private static final String KEYSTORE_PATH = "./keystore/acqKeystore.jks";
    // Truststore for verifying ACS's certificate when ACQ acts as a client
    private static final String TRUSTSTORE_PATH = "./truststore/acqTruststore.jks";
    private static final String PASSWORD = "password";

    public static void main(String[] args) throws Exception {
        // Load ACQ's KeyStore (contains its private key and certificate)
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(new FileInputStream(KEYSTORE_PATH), PASSWORD.toCharArray());

        // Load ACQ's TrustStore (contains certificates of servers ACQ trusts, e.g., ACS)
        KeyStore trustStore = KeyStore.getInstance("JKS");
        trustStore.load(new FileInputStream(TRUSTSTORE_PATH), PASSWORD.toCharArray());

        // KeyManagerFactory uses ACQ's KeyStore for server-side SSL
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, PASSWORD.toCharArray());

        // TrustManagerFactory uses ACQ's TrustStore for client-side SSL (when connecting to ACS)
        // Also used by the server socket to optionally validate clients (if client auth is required)
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        // SSLContext for both server (listening) and client (connecting to ACS) operations
        SSLContext sslContext = SSLContext.getInstance("TLS");
        // Init with KeyManagers (ACQ's identity) and TrustManagers (who ACQ trusts)
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);

        // Start the server thread to listen for connections from HttpsServer
        Thread serverThread = new Thread(() -> {
            try {
                startAcqServerListener(sslContext);
            } catch (Exception e) {
                System.err.println("ACQ Server Listener failed: " + e.getMessage());
                e.printStackTrace();
            }
        });

        serverThread.start();
    }

    // Listens for connections from HttpsServer (Step 3)
    private static void startAcqServerListener(SSLContext sslContext) throws IOException {
        SSLServerSocketFactory serverSocketFactory = sslContext.getServerSocketFactory();
        try (SSLServerSocket serverSocket = (SSLServerSocket) serverSocketFactory.createServerSocket(ACQ_LISTEN_PORT)) {
            // Optional: Require client authentication if HttpsServer should present a certificate
            // serverSocket.setNeedClientAuth(true);
            System.out.println("ACQ Server listening on port " + ACQ_LISTEN_PORT);

            while (true) {
                try (SSLSocket httpsClientSocket = (SSLSocket) serverSocket.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(httpsClientSocket.getInputStream()));
                     BufferedWriter out = new BufferedWriter(new OutputStreamWriter(httpsClientSocket.getOutputStream()))) {

                    System.out.println("ACQ Server: Connection received from " + httpsClientSocket.getInetAddress());
                    String requestJson = in.readLine(); // Read JSON from HttpsServer
                    System.out.println("ACQ Server: Received data: " + requestJson);

                    if (requestJson == null || requestJson.isEmpty()) {
                        System.err.println("ACQ Server: Received empty request.");
                        continue;
                    }

                    String validationLink = "ERROR: Processing failed"; // Default error response
                    try {
                        JSONObject paymentData = new JSONObject(requestJson);
                        String cardNumber = paymentData.optString("cardNumber", "");

                        // Step 4: Basic card validation and routing
                        if (isCardPlausible(cardNumber)) {
                            // Route to ACS based on card number (simplified)
                            if (shouldRouteToAcs(cardNumber)) {
                                // Step 4 & 5: Contact ACS Server
                                validationLink = contactAcs(sslContext, requestJson); // Pass the original JSON
                            } else {
                                System.out.println("ACQ Server: Card number " + cardNumber + " not routed to known ACS.");
                                // In a real system, might route to a default handler or reject
                                validationLink = "ERROR: Card issuer not supported";
                            }
                        } else {
                            System.out.println("ACQ Server: Card number " + cardNumber + " is not plausible.");
                            validationLink = "ERROR: Invalid card number format";
                        }

                    } catch (JSONException e) {
                        System.err.println("ACQ Server: Invalid JSON received: " + requestJson + " - Error: " + e.getMessage());
                        validationLink = "ERROR: Invalid request format";
                    } catch (Exception e) {
                        System.err.println("ACQ Server: Error processing request: " + e.getMessage());
                        e.printStackTrace();
                        validationLink = "ERROR: Internal ACQ error";
                    }

                    // Step 6: Send the validation link (or error) back to HttpsServer
                    System.out.println("ACQ Server: Sending response back to HttpsServer: " + validationLink);
                    out.write(validationLink + "\n");
                    out.flush();

                } catch (IOException e) {
                    System.err.println("ACQ Server: Error handling connection: " + e.getMessage());
                    // Continue listening for next connection
                } catch (Exception e) {
                    System.err.println("ACQ Server: Unexpected error: " + e.getMessage());
                    e.printStackTrace();
                }
            } // end while(true)
        } // end try-with-resources (serverSocket)
    }

    // Step 4: Basic Plausibility Check (Example)
    private static boolean isCardPlausible(String cardNumber) {
        return cardNumber != null && cardNumber.matches("\\d{13,19}"); // Check if it's 13-19 digits
    }

    // Step 4: Routing Logic (Simplified Example)
    private static boolean shouldRouteToAcs(String cardNumber) {
        // Route cards starting with "1234" to our example ACS
        return cardNumber != null && cardNumber.startsWith("1234");
    }

    // Step 4 & 5: Connects to ACS, sends data, receives validation link
    private static String contactAcs(SSLContext sslContext, String paymentDataJson) {
        // Use the *same* SSLContext, which has KeyManagers and TrustManagers configured
        SSLSocketFactory socketFactory = sslContext.getSocketFactory();
        try (SSLSocket socket = (SSLSocket) socketFactory.createSocket(ACS_HOST, ACS_CONNECT_PORT);
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            System.out.println("ACQ Server: Connecting to ACS Server on port " + ACS_CONNECT_PORT);
            socket.startHandshake(); // Explicitly start handshake
            System.out.println("ACQ Server: Connection with ACS established.");

            // Send payment data (JSON) to ACS
            out.write(paymentDataJson + "\n");
            out.flush();
            System.out.println("ACQ Server: Sent data to ACS: " + paymentDataJson);

            // Receive validation link from ACS (Step 5)
            String response = in.readLine();
            System.out.println("ACQ Server: Received response from ACS: " + response);
            if (response == null) {
                return "ERROR: No response from ACS";
            }
            return response; // This should be the validation link or an error from ACS

        } catch (Exception e) {
            System.err.println("ACQ Server: Error communicating with ACS: " + e.getMessage());
            e.printStackTrace();
            return "ERROR: Failed to connect or communicate with ACS";
        }
    }
}
// End of AcqServer.java //