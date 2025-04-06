import javax.net.ssl.*;
import java.io.*;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import io.javalin.Javalin;
import io.javalin.http.Handler;
import modules.PaymentInfo;

public class HttpsServer {
    private static final int ACQ_PORT = 8443;
    // Conteneur pour stocker les utilisateurs (chargé depuis le fichier users.txt)
    private static final Map<String, String> pendingTokens = new HashMap<>();
    private static final SecureRandom random = new SecureRandom();

    public static void main(String[] args) throws Exception {
        // Lance le thread pour gérer les API
        Thread apiThread = new Thread(() -> {
            try {
                startAPI();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        apiThread.start();
    }

    private static String communicateWithAcq(String authCode) throws Exception {
        // Charger le truststore
        KeyStore trustStore = KeyStore.getInstance("JKS");
        try (FileInputStream trustStoreFile = new FileInputStream("./truststore/acsTruststore.jks")) {
            trustStore.load(trustStoreFile, "password".toCharArray());
        }

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);

        SSLSocketFactory socketFactory = sslContext.getSocketFactory();
        try (SSLSocket socket = (SSLSocket) socketFactory.createSocket("localhost", ACQ_PORT);
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            // Envoyer le code d'authentification à l'ACQ
            out.write(authCode + "\n");
            out.flush();

            // Lire la réponse
            return in.readLine();
        }
    }

    // ----------------------------------------------

    public static void startAPI() {
        Javalin app = Javalin.create().start(7070);
        app.post("/askToken", handleTokenRequest);
        app.post("/otp", handleOtpRequest);


    }


    /**
     * Handler for the /askToken endpoint.
     * This handler processes a POST request containing payment information,
     * validates the information, generates a random token if the information is valid,
     * and stores the token with a dummy OTP in a pending tokens map.
     */
    private static Handler handleTokenRequest = ctx -> {
        PaymentInfo paymentInfo = ctx.bodyAsClass(PaymentInfo.class);

        if (paymentInfo.getClientName() == null || paymentInfo.getClientName().isEmpty() ||
                paymentInfo.getCardNumber() == null || paymentInfo.getCardNumber().isEmpty() ||
                paymentInfo.getMonth() <= 0 || paymentInfo.getYear() <= 0 ||
                paymentInfo.getCvv() <= 0 ||
                paymentInfo.getDueAmount() <= 0 || paymentInfo.getReceiverIdToken() == null || paymentInfo.getReceiverIdToken().isEmpty()) {

            ctx.status(400).result("All fields are required");
            return;
        }

        System.out.println("Payment information received successfully : " + paymentInfo.getClientName());
        System.out.println("Card Number: " + paymentInfo.getCardNumber());
        System.out.println("Month: " + paymentInfo.getMonth() + "/" + paymentInfo.getYear());
        System.out.println("CVV: " + paymentInfo.getCvv());
        System.out.println("Due Amount: " + paymentInfo.getDueAmount());
        System.out.println("Receiver ID Token: " + paymentInfo.getReceiverIdToken());

        if (paymentInfo.getCardNumber().equals("1234123412341234") && paymentInfo.getMonth() == 12 && paymentInfo.getYear() == 2025) {
            // Generate a random token
            String token = generateRandomToken();
            // Store the token with a dummy OTP for now
            pendingTokens.put(token, "123456"); // Replace "123456" with actual OTP generation logic

            ctx.status(200).result(token);
        } else {
            System.out.println("Invalid card number or month");
            ctx.status(400).result("Invalid card number or month");
        }
    };

    /**
     * Handler for the /otp endpoint.
     * This handler processes a GET request with a token query parameter,
     * retrieves the corresponding OTP from the pending tokens map,
     * and returns it in the response.
     */
    private static Handler handleOtpRequest = ctx -> {
        String token = ctx.queryParam("token");
        String otp = ctx.queryParam("otp");

        if (token == null || token.isEmpty()) {
            ctx.status(400).json(Map.of("result", false, "message", "Token is required"));
            return;
        }

        if (otp == null || otp.isEmpty()) {
            ctx.status(400).json(Map.of("result", false, "message", "OTP is required"));
            return;
        }

        System.out.println("OTP received successfully : " + otp);
        System.out.println("Token received successfully : " + token);

        // check if the token is valid
        if (pendingTokens.containsKey(token)) {
            try {
                String acqResponse = communicateWithAcq(otp);

                System.out.println("ACQ response: " + acqResponse);

                if ("ACK".equalsIgnoreCase(acqResponse.toString())) {
                    System.out.println("OTP verified successfully");
                    ctx.status(200).json(Map.of("result", true, "message", "OTP verified successfully"));
                } else {
                    System.out.println("Invalid OTP");
                    ctx.status(400).json(Map.of("result", false, "message", "Invalid or expired token/OTP"));
                }
            } catch (Exception e) {
                System.out.println("Error communicating with ACQ: " + e.getMessage());
                ctx.status(400).json(Map.of("result", false, "message", "Invalid or expired token/OTP"));
            }
        } else {
            System.out.println("Invalid or expired token/OTP");
            ctx.status(400).json(Map.of("result", false, "message", "Invalid or expired token/OTP"));
        }
    };

    private static String generateRandomToken() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder token = new StringBuilder();
        for (byte b : bytes) {
            token.append(String.format("%02x", b));
        }
        return token.toString();
    }

    // ----------------------------------------------
}