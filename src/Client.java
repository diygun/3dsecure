import javax.net.ssl.*;
import java.io.*;
import java.security.*;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;

public class Client {
    private static final String KEYSTORE_PATH = "./keystore/clientKeystore.jks";
    private static final String TRUSTSTORE_PATH = "./truststore/clientTruststore.jks";
    private static final String STORE_PASSWORD = "password";
    private static final String CARD_NUMBER = "1234-5678-8765-4321";
    private static final int PORT_AUTH = 8445;

    public static void main(String[] args) throws Exception {
        // Charger le truststore (certificat client)
        KeyStore trustStore = KeyStore.getInstance("JKS");
        trustStore.load(new FileInputStream(TRUSTSTORE_PATH), STORE_PASSWORD.toCharArray());

        SSLContext sslContext = setupSSLContext();
        SSLSocketFactory factory = sslContext.getSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket("localhost", PORT_AUTH);
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            // Générer un message : date + numéro de carte
            String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String message = date + ";" + CARD_NUMBER;

            // Signer le message avec la clé privée du client
            PrivateKey privateKey = loadPrivateKey();
            String signature = signMessage(message, privateKey);

            // Envoyer le message (numero de carte) en claire et le message signé
            out.write(message + ";" + signature + "\n");
            out.flush();

            // Lire la réponse
            System.out.println("Réponse du serveur : " + in.readLine());

            // Lire le code d'authentification par le serveur
            String authCodeResponse = in.readLine();

            // Extraire le code d'authentification
            String authCode = null;
            if (authCodeResponse.startsWith("Code d'authentification : ")) {
                authCode = authCodeResponse.substring("Code d'authentification : ".length());
                System.out.println("Code d'authentification reçu : " + authCode);
            }

            // Lire la signature du code d'authentification
            String signatureResponse = in.readLine();

            // Extraire la signature
            String signedAuthCode = null;
            if (signatureResponse.startsWith("Signature du code : ")) {
                signedAuthCode = signatureResponse.substring("Signature du code : ".length());
                System.out.println("Signature reçue : " + signedAuthCode);
            }

            // Vérifier la signature du code d'authentification avec la clé publique du serveur
            PublicKey serverPublicKey = trustStore.getCertificate("acs").getPublicKey();
            if (verifyAuthCode(authCode, signedAuthCode, serverPublicKey)) {
                System.out.println("Code d'authentification vérifié avec succès !");
            } else {
                System.err.println("Erreur : La signature du code d'authentification est invalide !");
            }
        }
    }

    private static PrivateKey loadPrivateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(new FileInputStream(KEYSTORE_PATH), STORE_PASSWORD.toCharArray());
        return (PrivateKey) keyStore.getKey("client", STORE_PASSWORD.toCharArray());
    }

    private static String signMessage(String message, PrivateKey privateKey) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(message.getBytes());
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static SSLContext setupSSLContext() throws Exception {
        KeyStore trustStore = KeyStore.getInstance("JKS");
        trustStore.load(new FileInputStream(TRUSTSTORE_PATH), STORE_PASSWORD.toCharArray());

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
        return sslContext;
    }

    private static boolean verifyAuthCode(String authCode, String signedAuthCode, PublicKey publicKey) throws Exception {
        // Décoder la signature encodée en Base64
        byte[] signatureBytes = Base64.getDecoder().decode(signedAuthCode);

        // Créer une instance de vérification de signature
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(publicKey);

        // Vérifier le hachage du code avec la signature
        signature.update(authCode.getBytes()); // Utiliser le code d'authentification en clair
        return signature.verify(signatureBytes); // Renvoie true si valide
    }
}