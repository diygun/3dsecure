import javax.net.ssl.*;
import java.io.*;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;

public class AcqServer {
    private static final int MONEY_PORT = 5050;
    private static final int ACQ_PORT = 8443;
    private static final String ACS_HOST = "localhost";
    private static final String KEYSTORE_PATH = "./Projet2/Recap/keystore/acqKeystore.jks";
    private static final String TRUSTSTORE_PATH = "./Projet2/Recap/truststore/acqTruststore.jks";
    private static final String PASSWORD = "password";


    public static void main(String[] args) throws Exception {
// Charger le keystore contenant la clé privée du serveur
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(new FileInputStream(KEYSTORE_PATH), PASSWORD.toCharArray());

        // Charger le truststore contenant le certificat client
        KeyStore trustStore = KeyStore.getInstance("JKS");
        trustStore.load(new FileInputStream(TRUSTSTORE_PATH), PASSWORD.toCharArray());

        // Configurer le gestionnaire de clés pour le serveur ACQ
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, PASSWORD.toCharArray());

        // Configurer le gestionnaire de confiance pour valider le certificat client
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        // Initialiser le contexte SSL avec les gestionnaires configurés
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);


        // Lancer le thread pour gérer les communications client
//        Thread authThread = new Thread(() -> {
//            try {
//                startAcsConnection(sslContext, trustStore, keyStore);
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//        });

        // Lancer le thread pour gérer les communications avec le serveur ACQ
        Thread moneyThread = new Thread(() -> {
            try {
                startAcqServer(sslContext);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

//        authThread.start();
        moneyThread.start();
    }


    // Démarre le serveur SSL
    private static void startAcqServer(SSLContext sslContext) throws IOException {
        SSLServerSocketFactory serverSocketFactory = sslContext.getServerSocketFactory();
        try (SSLServerSocket serverSocket = (SSLServerSocket) serverSocketFactory.createServerSocket(ACQ_PORT)) {
            System.out.println("ACQ en écoute sur le port " + ACQ_PORT);

            while (true) {
                try (SSLSocket clientSocket = (SSLSocket) serverSocket.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                     BufferedWriter out = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()))) {

                    String authCode = in.readLine();
                    System.out.println("Code reçu : " + authCode);

                    // Connect to ACS to validate the code
                    boolean isValid = contactAcs(sslContext, authCode); // Use SSLContext directly
                    out.write(isValid ? "ACK\n" : "NACK\n");
                    out.flush();
                }
            }
        }
    }

    private static boolean contactAcs(SSLContext sslContext, String authCode) {
        try (SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket(ACS_HOST, MONEY_PORT);
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            System.out.println("Connexion avec ACS établie");

            out.write(authCode + "\n");
            out.flush();

            String response = in.readLine();
            return "ACK".equals(response);
        } catch (Exception e) {
            System.err.println("Erreur lors de la communication avec ACS : " + e.getMessage());
            return false;
        }
    }
}