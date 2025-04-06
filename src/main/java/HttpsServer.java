// Beggining of HttpsServer.java //
import javax.net.ssl.*;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;

import io.javalin.Javalin;
import io.javalin.http.Handler;
import org.json.JSONObject; // Added for JSON handling

public class HttpsServer {
    private static final int ACQ_PORT = 8443;
    private static final String ACQ_HOST = "localhost";
    private static final String TRUSTSTORE_PATH = "./truststore/acsTruststore.jks"; // Assuming HttpsServer has its own truststore
    private static final String PASSWORD = "password";

    public static void main(String[] args) {
        // Lance le thread pour gérer les API
        Thread apiThread = new Thread(() -> {
            try {
                startAPI();
            } catch (Exception e) {
                System.err.println("Failed to start HTTPS API server: " + e.getMessage());
                e.printStackTrace();
            }
        });
        apiThread.start();
    }

    private static String forwardPaymentRequestToAcq(String paymentDataJson) throws Exception {
        // Charger le truststore pour faire confiance à ACQ
        KeyStore trustStore = KeyStore.getInstance("JKS");
        try (FileInputStream trustStoreFile = new FileInputStream(TRUSTSTORE_PATH)) {
            trustStore.load(trustStoreFile, PASSWORD.toCharArray());
        }

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        // HttpsServer acts as a client to ACQ, so it needs TrustManagers
        // It doesn't need KeyManagers unless ACQ requires client authentication from HttpsServer
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);

        SSLSocketFactory socketFactory = sslContext.getSocketFactory();
        try (SSLSocket socket = (SSLSocket) socketFactory.createSocket(ACQ_HOST, ACQ_PORT);
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            System.out.println("HTTPS Server: Connected to ACQ Server.");
            // Envoyer les données de paiement (JSON String) à l'ACQ (Step 3)
            out.write(paymentDataJson + "\n");
            out.flush();
            System.out.println("HTTPS Server: Sent payment data to ACQ: " + paymentDataJson);

            // Lire la réponse (le lien de validation) de l'ACQ (Step 6)
            String validationLink = in.readLine();
            System.out.println("HTTPS Server: Received response from ACQ: " + validationLink);
            if (validationLink == null) {
                throw new IOException("ACQ Server did not return a response.");
            }
            return validationLink;
        } catch (IOException e) {
            System.err.println("HTTPS Server: Error communicating with ACQ: " + e.getMessage());
            throw e; // Re-throw exception to be handled by the API layer
        }
    }

    // ----------------------------------------------

    public static void startAPI() {
        Javalin app = Javalin.create().start(7070); // Port for Merchant Backend to call
        System.out.println("HTTPS Server API listening on port 7070");

        // Endpoint for Step 2: Merchant Backend -> HTTPS Server
        app.post("/initiate-payment", handlePaymentInitiation);

        // Removed old /askToken and /otp endpoints
    }

    /**
     * Handler for Step 2: Receives Token A + Bank Data from Merchant Backend
     * Forwards to ACQ (Step 3), receives Validation Link from ACQ (Step 6)
     * Returns Validation Link to Merchant Backend (Step 7)
     */
    private static Handler handlePaymentInitiation = ctx -> {
        String requestBody = ctx.body();
        System.out.println("HTTPS Server: Received /initiate-payment request: " + requestBody);

        try {
            // Basic validation: check if it's valid JSON and contains tokenA
            JSONObject paymentData = new JSONObject(requestBody);
            if (!paymentData.has("tokenA") || !paymentData.has("cardNumber")) {
                ctx.status(400).result("Missing required fields (tokenA, cardNumber)");
                return;
            }

            // Step 3 & 6: Communicate with ACQ
            String validationLink = forwardPaymentRequestToAcq(requestBody);

            // Step 7: Return validation link to Merchant Backend
            // Check if ACQ returned an error indicator if necessary
            if (validationLink.startsWith("ERROR:")) {
                System.err.println("HTTPS Server: ACQ returned an error: " + validationLink);
                ctx.status(500).result("Error processing payment via ACQ.");
            } else {
                System.out.println("HTTPS Server: Returning validation link to Merchant Backend: " + validationLink);
                ctx.status(200).result(validationLink); // Send the link as plain text response body
            }

        } catch (org.json.JSONException e) {
            System.err.println("HTTPS Server: Invalid JSON received: " + requestBody);
            ctx.status(400).result("Invalid JSON format");
        } catch (Exception e) {
            System.err.println("HTTPS Server: Failed to process payment request: " + e.getMessage());
            e.printStackTrace();
            ctx.status(500).result("Internal server error during payment processing");
        }
    };

    // Removed generateRandomToken() - Token A is generated by Merchant Backend

    // ----------------------------------------------
}
// End of HttpsServer.java //