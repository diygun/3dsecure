import com.sun.net.httpserver.*;

import javax.net.ssl.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import io.javalin.Javalin;
import io.javalin.http.Handler;
import modules.PaymentInfo;

public class HttpsServer {
    private static final int HTTPS_PORT = 8043;
    private static final int ACQ_PORT = 8443;
    // Conteneur pour stocker les utilisateurs (chargé depuis le fichier users.txt)

    /**
     * A map to store user information.
     * The key is the username (String) and the value is a User object.
     * This map is loaded from the 'users.txt' file.
     *
     * The `users` HashMap stores user information, mapping a username string to a corresponding `User` object.
     * It is populated from an external file (e.g., `users.txt`) and is used during login to quickly retrieve and
     * verify a user's credentials.
     */
    private static final Map<String, User> users = new HashMap<>();

    private static final Map<String, String> pendingTokens = new HashMap<>();
    private static final SecureRandom random = new SecureRandom();



    public static void main(String[] args) throws Exception {
        // Charger les utilisateurs depuis users.txt
        loadUsersFromFile("./usersDb/users.txt");

        // Configurer le serveur HTTPS
        char[] keystorePassword = "password".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (FileInputStream keyStoreFile = new FileInputStream("./keystore/httpsServerKeystore.jks")) {
            keyStore.load(keyStoreFile, keystorePassword);
        }

        // Initialiser le SSL avec le keystore
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, keystorePassword);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, null);

        com.sun.net.httpserver.HttpsServer server = com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress(HTTPS_PORT), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters params) {
                try {
                    SSLContext sslContext = getSSLContext();
                    SSLEngine engine = sslContext.createSSLEngine();
                    params.setNeedClientAuth(false);
                    params.setCipherSuites(engine.getEnabledCipherSuites());
                    params.setProtocols(engine.getEnabledProtocols());
                    SSLParameters defaultSSLParams = sslContext.getDefaultSSLParameters();
                    params.setSSLParameters(defaultSSLParams);
                } catch (Exception ex) {
                    throw new RuntimeException("Erreur de configuration SSL", ex);
                }
            }
        });

        // Configurer les routes/handlers
        server.createContext("/login", new LoginHandler());
        server.createContext("/payment", new PaymentHandler());
        server.createContext("/processPayment", new ProcessPaymentHandler());

        // Lancer le serveur
        System.out.println("Serveur HTTPS démarré sur le port: " + HTTPS_PORT);
        server.start();

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

    // Fonction pour charger les utilisateurs depuis un fichier texte
    private static void loadUsersFromFile(String filePath) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(";");
                if (parts.length == 3) { // login;hashpassword;salt
                    String login = parts[0];
                    String hashPassword = parts[1];
                    String salt = parts[2];
                    users.put(login, new User(login, hashPassword, salt));
                } else {
                    System.err.println("Ligne ignorée (mauvais format) : " + line);
                }
            }
        }
    }

    // Gestion des requêtes GET et POST pour /login
    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            System.out.println("Requête POST reçue pour /login");
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                // Réponse avec le formulaire HTML
                String response = """
                        <!DOCTYPE html>
                        <html lang="en">
                        <head>
                            <meta charset="UTF-8">
                            <meta name="viewport" content="width=device-width, initial-scale=1.0">
                            <title>Connexion</title>
                        </head>
                        <body>
                            <h1>Connexion</h1>
                            <form method="POST" action="/login">
                                <label for="login">Nom d'utilisateur :</label><br>
                                <input type="text" id="login" name="login" required><br><br>
                        
                                <label for="password">Mot de passe :</label><br>
                                <input type="password" id="password" name="password" required><br><br>
                        
                                <button type="submit">Se connecter</button>
                            </form>
                        </body>
                        </html>
                        """;
                // Réponse HTTP
                exchange.getResponseHeaders().add("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
                exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().close();
            } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                handlePost(exchange);
            } else {
                exchange.sendResponseHeaders(405, -1); // Méthode non autorisée
            }
        }

        private void handlePost(HttpExchange exchange) throws IOException {
            // Lire le corps de la requête
            String requestBody = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                    .lines()
                    .reduce("", (acc, line) -> acc + line);

            // Extraction des paramètres (décodage URL)
            Map<String, String> formData = extractFormData(requestBody);

            String login = formData.get("login");
            String password = formData.get("password");

            // Vérification des informations
            User user = users.get(login);
            if (user != null && password != null) {
                try {
                    if (PasswordUtils.verifyPassword(password, user.getHashPassword(), user.getSalt())) {
                        // Succès : Redirige vers /payment
                        System.out.println("Authentification réussie pour: " + login);
                        exchange.getResponseHeaders().add("Location", "/payment"); // Ajoute l'en-tête de redirection
                        exchange.sendResponseHeaders(302, -1); // Code 302 pour redirection vers une autre page
                        return;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // Échec de l'authentification : afficher un message d'erreur
            String response = """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="UTF-8">
                        <title>Échec de l'authentification</title>
                    </head>
                    <body>
                        <h1>Échec de l'authentification !</h1>
                        <p>Nom d'utilisateur ou mot de passe incorrect.</p>
                        <a href="/login">Réessayer</a>
                    </body>
                    </html>
                    """;
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(401, response.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().close();
            System.out.println("Échec de l'authentification pour: " + login);
        }

        // Fonction pour extraire les données de formulaire encodées en URL
        private Map<String, String> extractFormData(String formData) {
            Map<String, String> map = new HashMap<>();
            String[] pairs = formData.split("&"); // Exemple : "login=user&password=pass"
            for (String pair : pairs) {
                String[] keyValue = pair.split("=", 2);
                if (keyValue.length == 2) {
                    String key = java.net.URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
                    String value = java.net.URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
                    map.put(key, value);
                }
            }
            return map;
        }
    }

    // Gestion des requêtes GET pour /payment
    static class PaymentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            System.out.println("Requête GET reçue pour /payment");
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String response = """
                        <html><body>
                            <form method="POST" action="/processPayment">
                              <label for="authCode">Entrez votre code d'authentification :</label>
                              <input type="text" id="authCode" name="authCode" required />
                              <button type="submit">Paiement</button>
                            </form>
                        </body></html>
                        """;
                exchange.getResponseHeaders().add("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, response.length());
                exchange.getResponseBody().write(response.getBytes());
                exchange.getResponseBody().close();
            } else {
                exchange.sendResponseHeaders(405, -1); // Méthode non autorisée
            }
        }
    }

    // Gestion des requêtes POST pour /processPayment
    static class ProcessPaymentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {

            System.out.println("Requête POST reçue pour /processPayment");
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                // Lorsque cette requête est reçue, le serveur contacte l’ACQ pour lui transférer le code qui, en
                // retour, renvoie ce code à ACS (sur un port PORT_MONEY, à définir). Les deux communications
                // sont sécurisées via SSL.

                System.out.println("Envoi du code d'authentification à l'ACQ");

                String requestBody = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                        .lines()
                        .reduce("", (acc, line) -> acc + line);

                // Extraire le code d'authentification
                Map<String, String> formData = extractFormData(requestBody);
                String authCode = formData.get("authCode");

                // Contacter l'ACQ pour vérifier le code
                String acqResponse = "NACK";
                try {
                    acqResponse = communicateWithAcq(authCode);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                // Répondre au client en fonction de la réponse de l'ACQ
                String response = acqResponse.equals("ACK") ? "Paiement validé" : "Paiement refusé";
                exchange.getResponseHeaders().add("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
                exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().close();
            } else {
                exchange.sendResponseHeaders(405, -1); // Méthode non autorisée
            }
        }
        // Fonction pour extraire les données de formulaire encodées en URL
        private Map<String, String> extractFormData(String formData) {
            Map<String, String> map = new HashMap<>();
            String[] pairs = formData.split("&");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=", 2);
                if (keyValue.length == 2) {
                    String key = java.net.URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
                    String value = java.net.URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
                    map.put(key, value);
                }
            }
            return map;
        }
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