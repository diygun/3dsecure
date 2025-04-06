// Beggining of MerchantSimulator.java //

import io.javalin.Javalin;
import io.javalin.http.Handler;
import org.json.JSONObject;
import org.json.JSONException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

public class MerchantSimulator {

    // --- Configuration ---
    // URL of your HttpsServer API endpoint
    private static final String HTTPS_SERVER_INITIATE_URL = "http://localhost:7070/initiate-payment";

    // Port and Path where this simulator will listen for the callback from AcsServer
    private static final int CALLBACK_LISTEN_PORT = 9090; // Make sure AcsServer points here
    private static final String CALLBACK_LISTEN_PATH = "/payment-callback"; // Make sure AcsServer points here
    // --- End Configuration ---

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) {

        System.out.println("--- Merchant Simulator Starting ---");

        // 1. Start the callback listener first
        Javalin callbackListener = startCallbackListener();

        // 2. Generate unique Token A for this transaction
        String tokenA = "MERCH_TOK_" + UUID.randomUUID().toString();
        System.out.println("Generated Token A: " + tokenA);

        // 3. Simulate payment details (Use the valid card for success)
        JSONObject paymentDetails = new JSONObject();
        try {
            paymentDetails.put("tokenA", tokenA); // Include the generated token
            paymentDetails.put("clientName", "Joe");
            paymentDetails.put("cardNumber", "1234123412341234"); // Use the valid card number from ACS/HTTPS
            paymentDetails.put("month", 12);
            paymentDetails.put("year", 2025);
            paymentDetails.put("cvv", 123);
            paymentDetails.put("dueAmount", 99.99);
            paymentDetails.put("receiverIdToken", "merchantXYZ"); // Some merchant identifier
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        // 4. Initiate payment flow (Step 2: Merchant -> HttpsServer)
        System.out.println("\nSending payment initiation request to HttpsServer...");
        System.out.println("Payload: " + paymentDetails.toString());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(HTTPS_SERVER_INITIATE_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(paymentDetails.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // 5. Process response from HttpsServer (Step 7: HttpsServer -> Merchant)
            System.out.println("\nReceived response from HttpsServer:");
            System.out.println("Status Code: " + response.statusCode());
            System.out.println("Response Body (Validation Link/Error): " + response.body());

            if (response.statusCode() >= 200 && response.statusCode() < 300 && !response.body().startsWith("ERROR:")) {
                System.out.println("\n--- ACTION REQUIRED ---");
                System.out.println("1. Open the link above in your browser: " + response.body());
                System.out.println("2. Follow the steps on the page (click 'Confirm Payment').");
                System.out.println("3. Check this console for the callback message received from AcsServer.");
                System.out.println("----------------------");
            } else {
                System.err.println("\nFailed to initiate payment or received error from HttpsServer.");
                callbackListener.stop(); // Stop the listener if initiation failed
            }

        } catch (Exception e) {
            System.err.println("\nError sending request to HttpsServer: " + e.getMessage());
            e.printStackTrace();
            callbackListener.stop(); // Stop the listener on error
        }

        System.out.println("\nMerchant Simulator is running and waiting for callback on port " + CALLBACK_LISTEN_PORT + "...");
        // Keep the main thread alive (or rely on Javalin's thread)
        // The Javalin server runs in its own thread(s), so main can exit,
        // but adding a join or sleep can make it clearer it's waiting.
        // For simplicity, we'll just let the Javalin thread keep it alive.
    }

    /**
     * Starts the Javalin server to listen for the callback POST request from AcsServer.
     *
     * @return The running Javalin instance.
     */
    private static Javalin startCallbackListener() {
        Javalin app = Javalin.create().start(CALLBACK_LISTEN_PORT);
        System.out.println("Callback listener started on http://localhost:" + CALLBACK_LISTEN_PORT + CALLBACK_LISTEN_PATH);

        // Define the handler for the callback endpoint (Step 10: AcsServer -> Merchant)
        app.post(CALLBACK_LISTEN_PATH, handlePaymentCallback);

        return app;
    }

    /**
     * Javalin Handler for processing the callback from AcsServer.
     */
    private static Handler handlePaymentCallback = ctx -> {
        System.out.println("\n--- CALLBACK RECEIVED ---");
        String requestBody = ctx.body();
        System.out.println("Received callback body: " + requestBody);

        try {
            JSONObject callbackData = new JSONObject(requestBody);
            String tokenA = callbackData.optString("tokenA", "N/A");
            boolean isSuccessful = callbackData.optBoolean("isSuccessful", false);

            System.out.println("Callback details:");
            System.out.println("  Token A: " + tokenA);
            System.out.println("  Payment Successful: " + isSuccessful);

            // In a real application, you would:
            // 1. Verify the tokenA matches an expected transaction.
            // 2. Update your order database based on the isSuccessful status.
            // 3. Possibly redirect the user (though ACS usually handles the final user page).

            ctx.status(200).result("Callback received by MerchantSimulator. OK."); // Acknowledge receipt

        } catch (JSONException e) {
            System.err.println("Error parsing callback JSON: " + e.getMessage());
            ctx.status(400).result("Invalid JSON format in callback.");
        } catch (Exception e) {
            System.err.println("Error processing callback: " + e.getMessage());
            ctx.status(500).result("Internal error processing callback.");
        }
        System.out.println("--- END CALLBACK ---");

        // Optional: Stop the simulator after receiving one callback
        // System.out.println("Stopping simulator after callback.");
        // ctx.appData(Javalin.class).stop();
    };
}
// End of MerchantSimulator.java //