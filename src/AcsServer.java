import javax.net.ssl.*;
import java.io.*;
import java.security.*;
import java.security.cert.Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class AcsServer {
    private static final int PORT_MONEY = 5050; // Port entre ACS et ACQ
    private static final int PORT_AUTH = 8445; // Port entre ACS et client
    private static final String ACQ_HOST = "localhost";
    private static final String KEYSTORE_PATH = "./keystore/acsKeystore.jks";
    private static final String TRUSTSTORE_PATH = "./truststore/acsTruststore.jks";
    private static final String PASSWORD = "password";
    private static final String ALIAS_ACS_SERVER = "acsserver";
    private static final Map<String, String> authCodeStore = new HashMap<>(); // Stock temporaire pour les codes d'authentification

    public static void main(String[] args) throws Exception {
        // Charger le keystore contenant la clé privée du serveur
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(new FileInputStream(KEYSTORE_PATH), PASSWORD.toCharArray());

        // Charger le truststore contenant le certificat client
        KeyStore trustStore = KeyStore.getInstance("JKS");
        trustStore.load(new FileInputStream(TRUSTSTORE_PATH), PASSWORD.toCharArray());

        // Configurer le gestionnaire de clés pour le serveur ACS
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, PASSWORD.toCharArray());

        // Configurer le gestionnaire de confiance pour valider le certificat client
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        // Initialiser le contexte SSL avec les gestionnaires configurés
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);


        // Lancer le thread pour gérer les communications client
        Thread authThread = new Thread(() -> {
            try {
                startClientServer(sslContext, trustStore, keyStore);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        // Lancer le thread pour gérer les communications avec le serveur ACQ
        Thread moneyThread = new Thread(() -> {
            try {
                startAcqServer(sslContext);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        authThread.start();
        moneyThread.start();
    }

    // Méthode pour gérer les communications avec le client pour valider le code
    private static void startClientServer(SSLContext sslContext, KeyStore trustStore, KeyStore keyStore) throws IOException {
        SSLServerSocketFactory serverSocketFactory = sslContext.getServerSocketFactory();
        SSLServerSocket serverSocket = (SSLServerSocket) serverSocketFactory.createServerSocket(PORT_AUTH);
        System.out.println("ACS en écoute sur le port " + PORT_AUTH);

        while (true) {
            try (SSLSocket clientSocket = (SSLSocket) serverSocket.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                 BufferedWriter out = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()))) {

                String receivedMessage = in.readLine();
                System.out.println("Message reçu : " + receivedMessage);

                // Vérifier la signature
                String[] messageParts = receivedMessage.split(";");
                String dateAndCard = messageParts[0] + ";" + messageParts[1];
                String signature = messageParts[2];

                boolean isValid = verifySignature(dateAndCard, signature, trustStore.getCertificate("client").getPublicKey());
                out.write(isValid ? "Signature valide\n" : "Signature invalide\n");
                out.flush();
                if (isValid) {
                    // Générer un code d'authentification
                    String authCode = generateAuthCode();
                    authCodeStore.put(messageParts[1], authCode);

                    // Signer le code d'authentification avec la clé privée du serveur
                    PrivateKey serverPrivateKey = (PrivateKey) keyStore.getKey(ALIAS_ACS_SERVER, PASSWORD.toCharArray());
                    String signedAuthCode = signMessage(authCode, serverPrivateKey);

                    // Envoyer le code au client
                    out.write("Code d'authentification : " + authCode + "\n");
                    out.flush();

                    // Envoyer la signature du code d'authentification au client
                    out.write("Signature du code : " + signedAuthCode + "\n");
                    out.flush();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // Méthode pour vérifier la signature d'un message
    private static boolean verifySignature(String data, String signatureBase64, PublicKey publicKey) {
        try {
            byte[] signatureBytes = java.util.Base64.getDecoder().decode(signatureBase64);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.initVerify(publicKey);
            signature.update(data.getBytes());
            return signature.verify(signatureBytes);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // Méthode pour générer un code d'authentification (aléatoire)
    private static String generateAuthCode() {
        SecureRandom random = new SecureRandom();
        int code = 100000 + random.nextInt(900000); // Code à 6 chiffres
        return String.valueOf(code);
    }

    // Méthode pour signer un message avec une clé privée
    private static String signMessage(String message, PrivateKey privateKey) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(message.getBytes());
        return java.util.Base64.getEncoder().encodeToString(signature.sign());
    }

    // Démarre le serveur SSL
    private static void startAcqServer(SSLContext sslContext) throws IOException { // Removed unnecessary parameters
        SSLServerSocketFactory serverSocketFactory = sslContext.getServerSocketFactory();
        try (SSLServerSocket serverSocket = (SSLServerSocket) serverSocketFactory.createServerSocket(PORT_MONEY)) {
            System.out.println("ACS en écoute sur le port " + PORT_MONEY);

            while (true) {
                try (SSLSocket clientSocket = (SSLSocket) serverSocket.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                     BufferedWriter out = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()))) {

                    String authCode = in.readLine();
                    System.out.println("Code reçu de ACQ: " + authCode);

                    if (authCodeStore.containsValue(authCode)) { // Check if the code exists
                        out.write("ACK\n");
                        System.out.println("Code validé");
                    } else {
                        out.write("NACK\n");
                        System.out.println("Code invalide");
                    }
                    out.flush();
                }
            }
        }
    }
    // Communique avec le serveur ACQ pour valider le code
    private static boolean contactAcq(String authCode, KeyStore trustStore) {
        try {
            SSLSocketFactory socketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket socket = (SSLSocket) socketFactory.createSocket(ACQ_HOST, PORT_MONEY)) {
                socket.startHandshake();

                if (!verifyAcqCertificate(socket, trustStore)) {
                    throw new SSLException("Certificat du serveur ACQ invalide !");
                }

                try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                     BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                    // Envoyer le code d'authentification à ACQ
                    out.write(authCode + "\n");
                    out.flush();

                    // Lire la réponse d'ACQ
                    String response = in.readLine();
                    System.out.println("Réponse de l'ACQ : " + response);

                    // Traiter la réponse d'ACQ
                    return "ACK".equalsIgnoreCase(response);
                }
            } catch (Exception e) {
                System.err.println("Erreur lors de la communication avec ACQ : " + e.getMessage());
                return false;
            }
        } catch (Exception e) {
            System.err.println("Erreur SSL pour la connexion au serveur ACQ : " + e.getMessage());
            return false;
        } finally {
            System.out.println("Communication avec ACQ terminée");
        }
    }

    private static boolean verifyAcqCertificate(SSLSocket socket, KeyStore trustStore) {
        try {
            Certificate[] serverCerts = socket.getSession().getPeerCertificates();
            Certificate acqCert = trustStore.getCertificate("acqserver");
            if (acqCert != null && serverCerts.length > 0) {
                return acqCert.equals(serverCerts[0]);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
}